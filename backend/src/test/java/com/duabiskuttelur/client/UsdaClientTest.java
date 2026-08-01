package com.duabiskuttelur.client;

import com.duabiskuttelur.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the lookup memoization against a real local HTTP server, so the
 * request count being asserted is genuinely the number of network calls made.
 */
class UsdaClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** One food whose nutrient ids match the ones parseFood tracks. */
    private static final String ONE_MATCH = """
            {"foods":[{"description":"Rice, white, cooked","foodNutrients":[
              {"nutrientId":1008,"value":130},
              {"nutrientId":1003,"value":2.4},
              {"nutrientId":1005,"value":28},
              {"nutrientId":1004,"value":0.3}]}]}""";

    private UsdaClient clientFor(int port) {
        AppProperties props = new AppProperties();
        props.setUsdaApiKey("test-key");
        props.setUsdaBaseUrl("http://localhost:" + port);
        props.setConnectTimeoutMs(5_000);
        props.setReadTimeoutMs(5_000);
        return new UsdaClient(props);
    }

    private AtomicInteger startServer(java.util.function.BiConsumer<HttpExchange, Integer> handler) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/fdc/v1/foods/search", exchange ->
                handler.accept(exchange, requests.incrementAndGet()));
        server.start();
        return requests;
    }

    @Test
    void repeatedLookupOfTheSameTermHitsTheNetworkOnce() throws Exception {
        AtomicInteger requests = startServer((exchange, n) -> {
            try {
                send(exchange, 200, ONE_MATCH);
            } catch (IOException ignored) {
                // test server; nothing to recover
            }
        });
        UsdaClient client = clientFor(server.getAddress().getPort());

        var first = client.lookup("white rice");
        var second = client.lookup("White Rice");   // case/whitespace shouldn't miss the cache
        var third = client.lookup("  white rice ");

        assertEquals(1, requests.get(), "the same search term should only be fetched once");
        assertTrue(first.isPresent());
        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(130, first.orElseThrow().calories());
    }

    @Test
    void differentTermsAreFetchedSeparately() throws Exception {
        AtomicInteger requests = startServer((exchange, n) -> {
            try {
                send(exchange, 200, ONE_MATCH);
            } catch (IOException ignored) {
                // test server; nothing to recover
            }
        });
        UsdaClient client = clientFor(server.getAddress().getPort());

        client.lookup("white rice");
        client.lookup("fried chicken");

        assertEquals(2, requests.get());
    }

    /** A term USDA genuinely doesn't know still costs a round trip to discover, so remember that too. */
    @Test
    void aGenuineNoMatchIsCachedAsWell() throws Exception {
        AtomicInteger requests = startServer((exchange, n) -> {
            try {
                send(exchange, 200, "{\"foods\":[]}");
            } catch (IOException ignored) {
                // test server; nothing to recover
            }
        });
        UsdaClient client = clientFor(server.getAddress().getPort());

        assertTrue(client.lookup("laksa penang").isEmpty());
        assertTrue(client.lookup("laksa penang").isEmpty());

        assertEquals(1, requests.get(), "a known-missing term shouldn't be re-fetched");
    }

    /**
     * The inverse of the case above: a term that failed because USDA was
     * unreachable must NOT be remembered as "no match", or one network blip
     * would blank that dish's nutrition for the life of the process.
     */
    @Test
    void aTransientFailureIsNotCached() throws Exception {
        AtomicInteger requests = startServer((exchange, n) -> {
            try {
                // Default usdaRetries=2 means 3 attempts, all failing, then recovery.
                send(exchange, n <= 3 ? 500 : 200, n <= 3 ? "{}" : ONE_MATCH);
            } catch (IOException ignored) {
                // test server; nothing to recover
            }
        });
        UsdaClient client = clientFor(server.getAddress().getPort());

        assertTrue(client.lookup("white rice").isEmpty(), "all attempts failed");
        assertTrue(client.lookup("white rice").isPresent(), "should retry rather than serve a cached failure");
        assertEquals(4, requests.get());
    }
}
