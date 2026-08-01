package com.duabiskuttelur.client;

import com.duabiskuttelur.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the production bug where a SocketTimeoutException while reading
 * the Gemini response body (after the connection succeeded) surfaced as a
 * plain RestClientException, unrecognized by the existing 429/5xx/
 * ResourceAccessException fallback branches, and propagated as a hard failure
 * instead of falling through to the next model.
 */
class GeminiClientTest {

    private static final byte[] FAKE_IMAGE = {1, 2, 3};

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AppProperties propsFor(int port, int readTimeoutMs, List<String> models) {
        AppProperties props = new AppProperties();
        props.setGeminiApiKeys(List.of("test-key"));
        props.setGeminiBaseUrl("http://localhost:" + port);
        props.setGeminiVisionModels(models);
        props.setConnectTimeoutMs(5_000);
        props.setReadTimeoutMs(readTimeoutMs);
        return props;
    }

    private static void respondOk(com.sun.net.httpserver.HttpExchange exchange, String text) throws java.io.IOException {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + text + "}]}}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void fallsBackToNextModelWhenResponseBodyReadTimesOut() throws Exception {
        int readTimeoutMs = 500;

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        // model-a: accepts the connection but never sends a response in time,
        // reproducing the SocketTimeoutException thrown from readWithMessageConverters.
        server.createContext("/v1beta/models/model-a:generateContent", exchange -> {
            try {
                Thread.sleep(readTimeoutMs + 1_500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respondOk(exchange, "\"[]\"");
        });
        // model-b: responds immediately with a valid (empty) food list.
        server.createContext("/v1beta/models/model-b:generateContent", exchange ->
                respondOk(exchange, "\"[]\""));
        server.start();

        AppProperties props = propsFor(server.getAddress().getPort(), readTimeoutMs, List.of("model-a", "model-b"));
        GeminiClient client = new GeminiClient(props, new ObjectMapper());

        assertDoesNotThrow(() -> {
            var foods = client.identifyFoods(FAKE_IMAGE, "image/jpeg");
            org.junit.jupiter.api.Assertions.assertTrue(foods.isEmpty());
        });
    }

    /**
     * A model that burned the read timeout is written off for the rest of the
     * call. Re-dialling it once per backoff round would multiply the request's
     * latency by the number of rounds and push a real menu scan past the CDN's
     * proxy timeout, so the call has to give up as soon as every model is dead
     * rather than sleeping through the 2s/4s/8s schedule first.
     */
    @Test
    void aTimedOutModelIsNotDialledAgainDuringTheSameCall() throws Exception {
        int readTimeoutMs = 400;
        AtomicInteger attempts = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1beta/models/model-a:generateContent", exchange -> {
            attempts.incrementAndGet();
            try {
                Thread.sleep(readTimeoutMs + 1_200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respondOk(exchange, "\"[]\"");
        });
        server.start();

        AppProperties props = propsFor(server.getAddress().getPort(), readTimeoutMs, List.of("model-a"));
        GeminiClient client = new GeminiClient(props, new ObjectMapper());

        long startedAt = System.currentTimeMillis();
        assertThrows(ProviderBusyException.class, () -> client.identifyFoods(FAKE_IMAGE, "image/jpeg"));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertEquals(1, attempts.get(), "the hung model should be dialled once, not once per backoff round");
        assertTrue(elapsedMs < 5_000,
                "should fail fast when every model is dead instead of sleeping the 14s backoff; took " + elapsedMs + "ms");
    }

    /** Menu scans run their own model chain and read timeout, not the vision ones. */
    @Test
    void menuScansUseTheMenuModelChain() throws Exception {
        AtomicInteger menuHits = new AtomicInteger();
        AtomicInteger visionHits = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1beta/models/menu-model:generateContent", exchange -> {
            menuHits.incrementAndGet();
            respondOk(exchange, "\"[]\"");
        });
        server.createContext("/v1beta/models/vision-model:generateContent", exchange -> {
            visionHits.incrementAndGet();
            respondOk(exchange, "\"[]\"");
        });
        server.start();

        AppProperties props = propsFor(server.getAddress().getPort(), 5_000, List.of("vision-model"));
        props.setGeminiMenuModels(List.of("menu-model"));
        props.setMenuReadTimeoutMs(5_000);
        GeminiClient client = new GeminiClient(props, new ObjectMapper());

        client.identifyMenuDishes(FAKE_IMAGE, "image/jpeg");

        assertEquals(1, menuHits.get(), "a menu scan should call the menu model");
        assertEquals(0, visionHits.get(), "a menu scan should not fall back to the plate-photo chain");
    }

    /**
     * A long menu can exhaust the output budget mid-array. Losing the whole scan
     * over a half-written trailing entry throws away every dish that did come
     * back, so the complete ones are kept.
     */
    @Test
    void aTruncatedArrayKeepsTheEntriesThatCompleted() {
        String truncated = """
                [{"name":"Nasi Lemak","grams":400},
                 {"name":"Char Kway Teow","grams":350},
                 {"name":"Roti Ca""";

        String salvaged = GeminiClient.extractJson(truncated, '[', ']');

        assertTrue(salvaged.endsWith("]"), "salvaged array must be closed: " + salvaged);
        assertTrue(salvaged.contains("Nasi Lemak") && salvaged.contains("Char Kway Teow"));
        assertTrue(!salvaged.contains("Roti Ca"), "the half-written entry should be dropped");
    }

    /** A brace or bracket inside a dish name must not be mistaken for structure. */
    @Test
    void salvageIgnoresBracketsInsideStrings() {
        String truncated = """
                [{"name":"Set A [rice + drink]","grams":500},
                 {"name":"Broken""";

        String salvaged = GeminiClient.extractJson(truncated, '[', ']');

        assertTrue(salvaged.contains("Set A [rice + drink]"), "string contents shouldn't confuse the scan");
        assertTrue(salvaged.endsWith("]"));
        assertTrue(!salvaged.contains("Broken"));
    }

    @Test
    void aResponseWithNoUsableJsonStillFails() {
        assertThrows(IllegalStateException.class,
                () -> GeminiClient.extractJson("I'm sorry, I can't read that menu.", '[', ']'));
        // An opening bracket but not one complete element is not recoverable.
        assertThrows(IllegalStateException.class,
                () -> GeminiClient.extractJson("[{\"name\":\"Half", '[', ']'));
    }

    @Test
    void nonTimeoutResponseErrorsAreNotSwallowed() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        // Malformed JSON body -> Jackson conversion failure wrapped as
        // RestClientException, but with no timeout anywhere in the cause chain.
        server.createContext("/v1beta/models/model-a:generateContent", exchange -> {
            byte[] bytes = "not valid json".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AppProperties props = propsFor(server.getAddress().getPort(), 5_000, List.of("model-a"));
        GeminiClient client = new GeminiClient(props, new ObjectMapper());

        assertThrows(RestClientException.class, () -> client.identifyFoods(FAKE_IMAGE, "image/jpeg"));
    }
}
