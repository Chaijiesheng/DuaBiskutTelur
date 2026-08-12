package com.duabiskuttelur.client;

import com.duabiskuttelur.config.AppMetrics;
import com.duabiskuttelur.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI layer used to emit exactly one signal: an INFO line saying a food fell
 * back to a model estimate. These tests pin the outcomes that a dashboard needs
 * to be able to distinguish, because the failure mode of instrumentation is
 * silent — a metric that never fires looks identical to a system that never has
 * the problem.
 */
class GeminiMetricsTest {

    private static final byte[] FAKE_IMAGE = {1, 2, 3};

    private HttpServer server;
    private final MeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AppProperties propsFor(int port, List<String> models) {
        AppProperties props = new AppProperties();
        props.setGeminiApiKeys(List.of("test-key"));
        props.setGeminiBaseUrl("http://localhost:" + port);
        props.setGeminiVisionModels(models);
        props.setConnectTimeoutMs(2_000);
        props.setReadTimeoutMs(2_000);
        return props;
    }

    private void respond(String path, int status, String body) throws Exception {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    /** Zero for a series that was never created — asserting "this did not happen" must not NPE. */
    private long callCount(String model, String type, String outcome) {
        io.micrometer.core.instrument.Timer timer = meters.find(AppMetrics.GEMINI_CALL)
                .tag(AppMetrics.TAG_MODEL, model)
                .tag(AppMetrics.TAG_TYPE, type)
                .tag(AppMetrics.TAG_OUTCOME, outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private long chainCount(String type, String outcome) {
        io.micrometer.core.instrument.Timer timer = meters.find(AppMetrics.GEMINI_CHAIN)
                .tag(AppMetrics.TAG_TYPE, type)
                .tag(AppMetrics.TAG_OUTCOME, outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private GeminiClient clientFor(List<String> models) {
        return new GeminiClient(propsFor(server.getAddress().getPort(), models), new ObjectMapper(), meters);
    }

    private static final String EMPTY_LIST =
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"[]\"}]}}]}";

    @Test
    void separatesTheVisionMenuAndFeedbackCallsRatherThanTotallingThem() throws Exception {
        startServer();
        respond("/v1beta/models/vision-model:generateContent", 200, EMPTY_LIST);
        respond("/v1beta/models/feedback-model:generateContent", 200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        server.start();

        AppProperties props = propsFor(server.getAddress().getPort(), List.of("vision-model"));
        props.setGeminiFeedbackModels(List.of("feedback-model"));
        GeminiClient client = new GeminiClient(props, new ObjectMapper(), meters);

        client.identifyFoods(FAKE_IMAGE, "image/jpeg");
        client.identifyMenuDishes(FAKE_IMAGE, "image/jpeg");
        client.generateFeedback("Foods:\n- Nasi lemak", "English");

        // A menu costs roughly double a meal and feedback is a different model
        // on a different budget — one combined "gemini latency" series would
        // average all three into a number describing none of them.
        assertEquals(1, callCount("vision-model", "vision", AppMetrics.OUTCOME_SUCCESS));
        assertEquals(1, callCount("vision-model", "menu", AppMetrics.OUTCOME_SUCCESS));
        assertEquals(1, callCount("feedback-model", "feedback", AppMetrics.OUTCOME_SUCCESS));
    }

    /**
     * The ways a call can fail all reach the user as the same 503, and each calls
     * for a different response: rotate keys, wait it out, raise the token budget,
     * or add capacity. One client per scenario, because a 429 cools the key down
     * for every later model too — chaining them into one run would test key
     * rotation rather than the tagging.
     */
    @Test
    void tagsRateLimitingServerErrorsAndTruncationApart() throws Exception {
        startServer();
        respond("/v1beta/models/limited:generateContent", 429, "{}");
        respond("/v1beta/models/broken:generateContent", 503, "{}");
        respond("/v1beta/models/truncating:generateContent", 200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"[{\\\"name\\\":\\\"cut\\\"\"}]},"
                        + "\"finishReason\":\"MAX_TOKENS\"}]}");
        respond("/v1beta/models/working:generateContent", 200, EMPTY_LIST);
        server.start();

        assertThrows(ProviderBusyException.class,
                () -> clientFor(List.of("limited")).identifyFoods(FAKE_IMAGE, "image/jpeg"));
        clientFor(List.of("broken", "working")).identifyFoods(FAKE_IMAGE, "image/jpeg");
        clientFor(List.of("truncating", "working")).identifyFoods(FAKE_IMAGE, "image/jpeg");

        assertEquals(1, callCount("limited", "vision", AppMetrics.OUTCOME_RATE_LIMITED));
        assertEquals(1, callCount("broken", "vision", AppMetrics.OUTCOME_SERVER_ERROR));
        assertEquals(1, callCount("truncating", "vision", AppMetrics.OUTCOME_TRUNCATED));
        assertEquals(2, callCount("working", "vision", AppMetrics.OUTCOME_SUCCESS));

        // Two failed calls but one chain in each of those runs: the user waited
        // once. Alerting on the call timer would report a healthy p95 straight
        // through an incident where every request took two attempts.
        assertEquals(2, chainCount("vision", AppMetrics.OUTCOME_SUCCESS));
    }

    @Test
    void recordsBudgetExhaustionSeparatelyFromEverythingBeingBusy() throws Exception {
        startServer();
        server.createContext("/v1beta/models/slow:generateContent", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        AppProperties props = propsFor(server.getAddress().getPort(), List.of("slow"));
        props.setReadTimeoutMs(300);
        props.setGeminiBudgetMs(600);
        GeminiClient client = new GeminiClient(props, new ObjectMapper(), meters);

        assertThrows(ProviderBusyException.class, () -> client.identifyFoods(FAKE_IMAGE, "image/jpeg"));

        // "The provider is slow" and "the provider is refusing" need different
        // responses, and both surface to the user as an identical 503.
        assertEquals(1, chainCount("vision", AppMetrics.OUTCOME_BUDGET_EXHAUSTED));
        assertEquals(0, chainCount("vision", AppMetrics.OUTCOME_BUSY));
    }

    @Test
    void publishesFreeBulkheadSlotsSoPressureIsVisibleBeforeAnythingIsShed() {
        AppProperties props = new AppProperties();
        props.setGeminiApiKeys(List.of("test-key"));
        props.setGeminiMaxConcurrentCalls(16);
        new GeminiClient(props, new ObjectMapper(), meters);

        assertEquals(16, meters.get(AppMetrics.GEMINI_SLOTS_AVAILABLE).gauge().value());
    }

    @Test
    void tagValuesStayWithinAClosedSetSoTheSeriesCountCannotGrowWithTraffic() throws Exception {
        startServer();
        respond("/v1beta/models/vision-model:generateContent", 200, EMPTY_LIST);
        server.start();

        AppProperties props = propsFor(server.getAddress().getPort(), List.of("vision-model"));
        GeminiClient client = new GeminiClient(props, new ObjectMapper(), meters);
        for (int i = 0; i < 25; i++) {
            client.identifyFoods(FAKE_IMAGE, "image/jpeg");
        }

        // Every tag value comes from configuration or a fixed word — never a
        // dish name, user id or API key. An unbounded tag is a slow memory leak
        // that also makes the dashboard unreadable.
        assertEquals(1, meters.find(AppMetrics.GEMINI_CALL).timers().size(),
                "twenty-five identical calls produced more than one series");
        assertTrue(meters.find(AppMetrics.GEMINI_CHAIN).timers().size() <= 1);
    }
}
