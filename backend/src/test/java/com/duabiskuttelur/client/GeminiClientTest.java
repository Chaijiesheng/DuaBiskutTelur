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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
