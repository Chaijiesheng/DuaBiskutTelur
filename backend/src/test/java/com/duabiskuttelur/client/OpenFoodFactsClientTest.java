package com.duabiskuttelur.client;

import com.duabiskuttelur.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenFoodFactsClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenFoodFactsClient clientFor(String path, String jsonBody, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext(path, exchange -> {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
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
        return new OpenFoodFactsClient(props);
    }

    @Test
    void usesPerServingNutrientsWhenAvailable() throws Exception {
        String json = """
                {"status":1,"product":{
                  "product_name":"Oreo Original",
                  "serving_size":"3 biscuits (33g)",
                  "categories_tags":["en:snacks","en:biscuits-and-cakes"],
                  "nutriments":{
                    "energy-kcal_serving":165,"proteins_serving":1.3,"carbohydrates_serving":24,
                    "fat_serving":7.3,"fiber_serving":0.9,"sugars_serving":13,"sodium_serving":0.14,
                    "energy-kcal_100g":500,"proteins_100g":4,"carbohydrates_100g":73,
                    "fat_100g":22,"fiber_100g":2.7,"sugars_100g":39,"sodium_100g":0.42
                  }
                }}""";
        OpenFoodFactsClient client = clientFor("/api/v2/product/123.json", json, 200);

        Optional<OpenFoodFactsClient.Product> result = client.lookup("123");
        assertTrue(result.isPresent());
        OpenFoodFactsClient.Product p = result.get();
        assertEquals("Oreo Original", p.name());
        assertTrue(p.perServing());
        assertEquals("3 biscuits (33g)", p.unitLabel());
        assertEquals(165, p.calories());
        assertEquals(140, p.sodium(), 0.01); // 0.14g -> 140mg
    }

    @Test
    void fallsBackToPer100gWhenNoServingDataExists() throws Exception {
        String json = """
                {"status":1,"product":{
                  "product_name":"Plain Rice Crackers",
                  "categories_tags":["en:snacks"],
                  "nutriments":{
                    "energy-kcal_100g":380,"proteins_100g":7,"carbohydrates_100g":80,
                    "fat_100g":2,"fiber_100g":1,"sugars_100g":0.5,"sodium_100g":0.6
                  }
                }}""";
        OpenFoodFactsClient client = clientFor("/api/v2/product/456.json", json, 200);

        OpenFoodFactsClient.Product p = client.lookup("456").orElseThrow();
        assertFalse(p.perServing());
        assertEquals("100g", p.unitLabel());
        assertEquals(380, p.calories());
        assertEquals(600, p.sodium(), 0.01); // 0.6g -> 600mg
    }

    @Test
    void returnsEmptyWhenProductNotFound() throws Exception {
        OpenFoodFactsClient client = clientFor("/api/v2/product/999.json", "{\"status\":0}", 404);
        assertTrue(client.lookup("999").isEmpty());
    }
}
