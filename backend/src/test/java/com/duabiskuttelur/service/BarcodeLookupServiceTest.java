package com.duabiskuttelur.service;

import com.duabiskuttelur.client.OpenFoodFactsClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.AnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Barcode results skip Gemini entirely (the label numbers are already exact),
 * so this wires a real ScoringService/FeedbackService against a fake Open
 * Food Facts endpoint rather than mocking the pipeline. AnalysisService is
 * only touched when a signed-in user is passed in, so a mostly-null instance
 * is safe for the anonymous-visitor cases exercised here.
 */
class BarcodeLookupServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static final String PRODUCT_JSON = """
            {"status":1,"product":{
              "product_name":"Oreo Original",
              "serving_size":"3 biscuits (33g)",
              "categories_tags":["en:beverages"],
              "nutriments":{
                "energy-kcal_serving":165,"proteins_serving":1.3,"carbohydrates_serving":24,
                "fat_serving":7.3,"fiber_serving":0.9,"sugars_serving":13,"sodium_serving":0.14
              }
            }}""";

    private BarcodeLookupService serviceWith(String path, String json, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext(path, exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AppProperties props = new AppProperties();
        props.setOpenFoodFactsBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setConnectTimeoutMs(5_000);
        props.setReadTimeoutMs(5_000);

        OpenFoodFactsClient client = new OpenFoodFactsClient(props);
        ScoringService scoringService = new ScoringService(new ScoringProperties());
        FeedbackService feedbackService = new FeedbackService(
                context -> { throw new UnsupportedOperationException("Gemini not expected for barcode results"); },
                props, new ScoringProperties());
        AnalysisService analysisService = new AnalysisService(
                null, null, scoringService, feedbackService, null, null, null, props, new ObjectMapper());

        return new BarcodeLookupService(client, scoringService, feedbackService, analysisService);
    }

    @Test
    void scalesNutrientsByServingsAndMarksSourceAsBarcode() throws Exception {
        BarcodeLookupService service = serviceWith("/api/v2/product/111.json", PRODUCT_JSON, 200);

        AnalysisResponse oneServing = service.lookup("111", 1, null, "en");
        assertEquals("barcode", oneServing.source());
        assertEquals(165, oneServing.totals().calories());
        assertEquals("beverage", oneServing.foods().get(0).foodGroup());

        AnalysisResponse twoServings = service.lookup("111", 2, null, "en");
        assertEquals(330, twoServings.totals().calories());
        assertEquals(2.6, twoServings.totals().protein(), 0.01);
    }

    @Test
    void throwsNotFoundWhenProductIsMissing() throws Exception {
        BarcodeLookupService service = serviceWith("/api/v2/product/000.json", "{\"status\":0}", 404);
        assertThrows(BarcodeLookupService.BarcodeNotFoundException.class,
                () -> service.lookup("000", 1, null, "en"));
    }

    @Test
    void lookupProductResolvesNameAndUnitWithoutScoring() throws Exception {
        BarcodeLookupService service = serviceWith("/api/v2/product/111.json", PRODUCT_JSON, 200);

        BarcodeLookupService.ProductInfo info = service.lookupProduct("111");
        assertEquals("Oreo Original", info.name());
        assertEquals("3 biscuits (33g)", info.unitLabel());
        assertEquals(true, info.perServing());
    }

    @Test
    void lookupProductFallsBackToPer100gWhenNoServingDataExists() throws Exception {
        String json = """
                {"status":1,"product":{
                  "product_name":"Generic Snack",
                  "categories_tags":["en:snacks"],
                  "nutriments":{
                    "energy-kcal_100g":450,"proteins_100g":8,"carbohydrates_100g":55,
                    "fat_100g":20,"fiber_100g":2,"sugars_100g":30,"sodium_100g":0.5
                  }
                }}""";
        BarcodeLookupService service = serviceWith("/api/v2/product/222.json", json, 200);

        BarcodeLookupService.ProductInfo info = service.lookupProduct("222");
        assertEquals("100g", info.unitLabel());
        assertEquals(false, info.perServing());
    }

    @Test
    void lookupProductThrowsNotFoundWhenProductIsMissing() throws Exception {
        BarcodeLookupService service = serviceWith("/api/v2/product/000.json", "{\"status\":0}", 404);
        assertThrows(BarcodeLookupService.BarcodeNotFoundException.class,
                () -> service.lookupProduct("000"));
    }
}
