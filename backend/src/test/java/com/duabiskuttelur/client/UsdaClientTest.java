package com.duabiskuttelur.client;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.duabiskuttelur.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dataType filter is the difference between a Malaysian dish resolving
 * against generic reference food and resolving against whatever branded product
 * happens to rank first: dropping it turns "chocolate milk" from 841 curated
 * hits into 150k results that are entirely Branded. Nothing in the response
 * says the filter was ignored, so a mis-encoded parameter would degrade every
 * nutrition lookup in the app without producing a single error.
 *
 * <p>These assertions are on the request this client puts on the wire, checked
 * against a local server so they need no key and no network.
 */
class UsdaClientTest {

    private HttpServer server;
    private final List<String> receivedQueries = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a stub FDC that records each request and answers with the given status/body. */
    private AppProperties serverReturning(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/fdc/v1/foods/search", exchange -> {
            requests.incrementAndGet();
            receivedQueries.add(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AppProperties props = new AppProperties();
        props.setUsdaApiKey("test-key");
        props.setUsdaBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setConnectTimeoutMs(2_000);
        props.setUsdaReadTimeoutMs(2_000);
        return props;
    }

    private static String oneFood() {
        return "{\"foods\":[{\"description\":\"Coconut rice\",\"foodNutrients\":["
                + "{\"nutrientId\":1008,\"value\":180},{\"nutrientId\":1003,\"value\":3}]}]}";
    }

    @Test
    void sendsEachDataTypeAsItsOwnParameter() throws IOException {
        AppProperties props = serverReturning(200, oneFood());

        Optional<UsdaClient.NutrientsPer100g> result = new UsdaClient(props, new SimpleMeterRegistry()).lookup("coconut rice");

        assertTrue(result.isPresent(), "the stub returned a usable food");
        String query = receivedQueries.get(0);
        assertEquals(3, query.split("dataType=", -1).length - 1,
                "expected three separate dataType parameters, got: " + query);
        assertTrue(query.contains("Survey"), query);
        assertTrue(query.contains("Legacy"), query);
        assertTrue(query.contains("Foundation"), query);
    }

    /**
     * Comma-joined is the form the FDC docs describe and it does work — but only
     * with percent-encoded spaces; with '+' for spaces it is a 400, which this
     * client swallows into an empty result. That failure mode is invisible: every
     * food quietly falls back to the vision model's estimate. Sending the values
     * separately sidesteps the whole question.
     */
    @Test
    void doesNotCommaJoinTheDataTypes() throws IOException {
        AppProperties props = serverReturning(200, oneFood());

        new UsdaClient(props, new SimpleMeterRegistry()).lookup("coconut rice");

        String query = receivedQueries.get(0);
        assertTrue(!query.contains("%2C") && !query.matches(".*dataType=[^&]*,.*"),
                "dataType was comma-joined: " + query);
    }

    @Test
    void aRejectedRequestIsNotRetried() throws IOException {
        // A 400 or 403 means the request itself is unacceptable — the key, the
        // query. Retrying spends the caller's latency budget to be told no again,
        // and a menu scan pays that per dish.
        AppProperties props = serverReturning(400, "{\"error\":\"bad request\"}");
        props.setUsdaRetries(2);

        Optional<UsdaClient.NutrientsPer100g> result = new UsdaClient(props, new SimpleMeterRegistry()).lookup("coconut rice");

        assertTrue(result.isEmpty());
        assertEquals(1, requests.get(), "a 4xx should not be retried");
    }

    @Test
    void aServerErrorIsRetriedUpToTheConfiguredLimit() throws IOException {
        AppProperties props = serverReturning(500, "{\"error\":\"upstream\"}");
        props.setUsdaRetries(1); // one retry -> two attempts total

        Optional<UsdaClient.NutrientsPer100g> result = new UsdaClient(props, new SimpleMeterRegistry()).lookup("coconut rice");

        assertTrue(result.isEmpty());
        assertEquals(2, requests.get(), "a 5xx is worth one retry, and only one");
    }

    @Test
    void noKeyMeansNoRequestAtAll() {
        AppProperties props = new AppProperties(); // usdaApiKey defaults to blank

        assertTrue(new UsdaClient(props, new SimpleMeterRegistry()).lookup("coconut rice").isEmpty());
        assertEquals(0, requests.get());
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
        return new UsdaClient(props, new SimpleMeterRegistry());
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

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

}
