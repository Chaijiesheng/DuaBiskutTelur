package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.MenuDish;
import com.duabiskuttelur.model.MenuRankingResponse;
import com.duabiskuttelur.model.MenuRankingResponse.TierGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * MenuRankingService reuses AnalysisService.resolveNutrition (USDA-less here —
 * no key configured, so it deterministically falls back to the model's
 * per-100g estimates) and ScoringService's real grade bands. Only
 * VisionAnalysisClient is faked, so every other step exercises real code.
 */
class MenuRankingServiceTest {

    private MenuRankingService serviceWith(VisionAnalysisClient visionClient) {
        AppProperties props = new AppProperties();
        props.setGeminiApiKeys(List.of("test-key")); // hasGeminiKey() -> true, so the stub above is actually used
        ScoringService scoringService = new ScoringService(new ScoringProperties());
        UsdaClient usdaClient = new UsdaClient(new AppProperties()); // no USDA key -> always falls back, no network
        AnalysisService analysisService = new AnalysisService(
                null, usdaClient, emptyDishTable(), scoringService, null, null, null, null, props, new ObjectMapper());
        return new MenuRankingService(visionClient, scoringService, analysisService, null, null, props, new ObjectMapper());
    }

    /**
     * These fixtures set their own nutrition to exercise scoring and tiering,
     * so the real curated table has to stay out of the way — it would answer
     * for "Char kway teow" and "Nasi lemak" and replace the numbers under test.
     */
    private static LocalDishTable emptyDishTable() {
        return new LocalDishTable(java.util.List.of());
    }

    private static IdentifiedFood dish(String name, String group, boolean fried,
                                        double caloriesPer100g, double proteinPer100g, double carbsPer100g,
                                        double fatPer100g, double sodiumPer100g, double grams) {
        return dish(name, group, fried, caloriesPer100g, proteinPer100g, carbsPer100g,
                fatPer100g, sodiumPer100g, grams, IdentifiedFood.KIND_MAIN);
    }

    private static IdentifiedFood dish(String name, String group, boolean fried,
                                        double caloriesPer100g, double proteinPer100g, double carbsPer100g,
                                        double fatPer100g, double sodiumPer100g, double grams, String kind) {
        return new IdentifiedFood(name, "1 serving / ~" + (int) grams + "g", grams, name,
                caloriesPer100g, proteinPer100g, carbsPer100g, fatPer100g, 1.5, 2, sodiumPer100g,
                group, fried, 0.9, kind);
    }

    @Test
    void groupsFiveTiersEvenWhenSomeAreEmpty() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        // Only two dishes -> at most 2 of the 5 tiers can be non-empty.
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(List.of(
                dish("Steamed fish", "protein", false, 90, 20, 1, 3, 60, 220),
                dish("Fried chicken wings", "protein", true, 260, 22, 8, 20, 550, 220)));

        MenuRankingResponse response = serviceWith(vision).rank(new byte[]{1}, "image/jpeg", null, "en");

        assertEquals(5, response.tiers().size());
        List<String> tierOrder = response.tiers().stream().map(TierGroup::tier).toList();
        assertEquals(List.of("HANG", "TOP", "RENSHANGREN", "NPC", "LAWANLE"), tierOrder);
        assertEquals(2, response.tiers().stream().mapToInt(t -> t.dishes().size()).sum());
        assertTrue(response.tiers().stream().anyMatch(t -> t.dishes().isEmpty()), "expected at least one empty tier");
    }

    @Test
    void mapsEachGradeBandToItsTierKey() {
        assertEquals("HANG", TierMapping.tierFor("A+"));
        assertEquals("TOP", TierMapping.tierFor("A"));
        assertEquals("RENSHANGREN", TierMapping.tierFor("B"));
        assertEquals("NPC", TierMapping.tierFor("C"));
        assertEquals("LAWANLE", TierMapping.tierFor("D"));
    }

    /** Nothing on this menu is a genuinely healthy option — every dish is fried, salty and calorie-dense. */
    private static List<IdentifiedFood> allUnhealthyMenu() {
        return List.of(
                dish("Fried chicken wings", "protein", true, 290, 22, 8, 21, 620, 200),
                dish("Deep-fried spring rolls", "fat", true, 280, 5, 26, 17, 520, 180),
                dish("Char kway teow", "grain", true, 176, 6, 22, 7, 620, 350),
                dish("Cheese fries", "fat", true, 310, 6, 33, 18, 700, 250),
                dish("Fried banana fritters", "sweet", true, 330, 3, 42, 17, 210, 200),
                // Deliberately no drinks here: those are filtered into addOns,
                // and these tests are about how the mains get tiered.
                dish("Fried spring onion pancake", "grain", true, 300, 6, 34, 16, 480, 220),
                dish("Fried pork lard rice", "grain", true, 240, 8, 30, 11, 580, 300));
    }

    @Test
    void spreadsDishesAcrossAllTiersWhenNothingOnTheMenuIsHealthy() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(allUnhealthyMenu());

        MenuRankingResponse response = serviceWith(vision).rank(new byte[]{1}, "image/jpeg", null, "en");

        assertTrue(response.relative(), "an all-unhealthy menu should be ranked relatively");
        // Absolute grading would have stacked every one of these in the bottom
        // tiers; the point of relative mode is that all 5 rows carry dishes.
        List<Integer> sizes = response.tiers().stream().map(t -> t.dishes().size()).toList();
        assertTrue(sizes.stream().allMatch(s -> s > 0), "every tier should hold at least one dish, got " + sizes);
        // 7 dishes over 5 tiers -> 2,2,1,1,1: no tier may differ by more than one.
        assertEquals(1, Math.max(0, sizes.stream().mapToInt(Integer::intValue).max().orElseThrow()
                - sizes.stream().mapToInt(Integer::intValue).min().orElseThrow()),
                "tier sizes should be balanced, got " + sizes);
        assertEquals(7, sizes.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void keepsAbsoluteTiersWhenTheMenuHasAHealthyOption() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        List<IdentifiedFood> mixed = new ArrayList<>(allUnhealthyMenu());
        // Macros near the ideal 30/40/30 split, a vegetable, unfried and a sane
        // portion — enough to clear the grade-B "genuinely healthy" floor.
        mixed.add(dish("Garden vegetable stir-fry", "vegetable", false, 120, 7, 12, 4, 100, 300));
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(mixed);

        MenuRankingResponse response = serviceWith(vision).rank(new byte[]{1}, "image/jpeg", null, "en");

        assertFalse(response.relative(), "a menu with a genuinely healthy dish keeps its absolute grades");
        // Every dish must still sit in the tier its own grade maps to.
        response.tiers().forEach(group -> group.dishes().forEach(d ->
                assertEquals(TierMapping.tierFor(d.grade()), d.tier(),
                        d.name() + " should be in the tier its grade maps to")));
    }

    @Test
    void ranksDishesHealthiestFirstAcrossTheWholeMenu() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(allUnhealthyMenu());

        MenuRankingResponse response = serviceWith(vision).rank(new byte[]{1}, "image/jpeg", null, "en");

        // Tiers are best-to-worst and each tier lists its dishes in rank order,
        // so flattening them must produce ranks 1..n with scores never rising.
        var flattened = response.tiers().stream().flatMap(t -> t.dishes().stream()).toList();
        assertEquals(7, flattened.size());
        for (int i = 0; i < flattened.size(); i++) {
            assertEquals(i + 1, flattened.get(i).rank(), "ranks should run 1..n healthiest first");
            if (i > 0) {
                assertTrue(flattened.get(i - 1).score() >= flattened.get(i).score(),
                        "scores should never increase as rank gets worse");
            }
        }
    }

    @Test
    void spreadsTiersEvenlyForAnExactMultipleOfFive() {
        assertEquals(List.of("HANG", "HANG", "TOP", "TOP", "RENSHANGREN", "RENSHANGREN",
                        "NPC", "NPC", "LAWANLE", "LAWANLE"),
                TierMapping.evenlySpreadTiers(10));
        // Leftovers go to the better tiers first.
        assertEquals(List.of("HANG", "HANG", "TOP", "RENSHANGREN", "NPC", "LAWANLE"),
                TierMapping.evenlySpreadTiers(6));
        assertEquals(List.of(), TierMapping.evenlySpreadTiers(0));
    }

    /**
     * historyDetail() replays whatever JSON was in menu_scan.result_json, which
     * for older rows predates the rank/relative fields — those scans still have
     * to reopen rather than blowing up on a missing property.
     */
    @Test
    void reopensMenuScansSavedBeforeRankingWasAdded() throws Exception {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(allUnhealthyMenu());
        MenuRankingResponse fresh = serviceWith(vision).rank(new byte[]{1}, "image/jpeg", null, "en");

        // Strip exactly the fields a pre-ranking scan wouldn't have written,
        // rather than hand-rolling the old JSON, so this keeps matching the
        // real persisted shape as FoodItem/MenuDish evolve.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.valueToTree(fresh);
        root.remove("relative");
        for (JsonNode tier : (ArrayNode) root.get("tiers")) {
            for (JsonNode dish : tier.get("dishes")) {
                ((ObjectNode) dish).remove("rank");
            }
        }

        MenuRankingResponse restored = mapper.treeToValue(root, MenuRankingResponse.class);

        assertEquals(5, restored.tiers().size());
        assertFalse(restored.relative(), "a scan with no relative flag reads back as absolute");
        var dishes = restored.tiers().stream().flatMap(t -> t.dishes().stream()).toList();
        assertEquals(7, dishes.size());
        assertTrue(dishes.stream().allMatch(d -> d.rank() == 0),
                "legacy dishes come back unranked, which the tier list renders without a number");
    }

    /**
     * Nutrition lookups are one independent round trip per dish, so a menu's
     * worth of them should overlap rather than queue up. Against a USDA that
     * takes 300ms per call, 8 dishes cost ~2.4s serially but roughly one call's
     * latency in parallel — the assertion sits far enough below the serial time
     * that it can't flip on a slow machine.
     */
    @Test
    void nutritionLookupsRunConcurrentlyNotOneAtATime() throws Exception {
        int dishCount = 8;
        int perLookupMs = 300;

        HttpServer usda = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        usda.setExecutor(Executors.newCachedThreadPool());
        usda.createContext("/fdc/v1/foods/search", exchange -> {
            try {
                Thread.sleep(perLookupMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"foods\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        usda.start();

        try {
            AppProperties props = new AppProperties();
            props.setGeminiApiKeys(List.of("test-key"));
            // A distinct search term per dish so the cache can't do the work for us.
            props.setUsdaApiKey("test-key");
            props.setUsdaBaseUrl("http://localhost:" + usda.getAddress().getPort());
            props.setConnectTimeoutMs(5_000);
            props.setReadTimeoutMs(5_000);

            List<IdentifiedFood> dishes = new ArrayList<>();
            for (int i = 0; i < dishCount; i++) {
                dishes.add(dish("Unique dish " + i, "grain", false, 150, 5, 20, 5, 300, 200));
            }
            VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
            Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(dishes);

            ScoringService scoringService = new ScoringService(new ScoringProperties());
            AnalysisService analysisService = new AnalysisService(
                    null, new UsdaClient(props), emptyDishTable(), scoringService, null, null, null, null, props, new ObjectMapper());
            MenuRankingService service = new MenuRankingService(
                    vision, scoringService, analysisService, null, null, props, new ObjectMapper());

            long startedAt = System.currentTimeMillis();
            MenuRankingResponse response = service.rank(new byte[]{1}, "image/jpeg", null, "en");
            long elapsedMs = System.currentTimeMillis() - startedAt;

            assertEquals(dishCount, response.dishCount(), "every dish should still be resolved");
            long serialMs = (long) dishCount * perLookupMs;
            assertTrue(elapsedMs < serialMs / 2,
                    "lookups should overlap: took " + elapsedMs + "ms, serial would be ~" + serialMs + "ms");
        } finally {
            usda.stop(0);
        }
    }

    /**
     * A condiment or a drink isn't an answer to "what should I order", so it
     * belongs in its own list rather than competing for a tier. Before the
     * split, a spoonful of sambal outscored every real dish on the menu.
     */
    @Test
    void addOnsAndDrinksAreListedSeparatelyInsteadOfTiered() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(List.of(
                dish("Steamed chicken rice", "grain", false, 100, 5, 14, 2, 150, 600),
                dish("Fried chicken wings", "protein", true, 260, 22, 8, 20, 550, 220),
                dish("Nasi lemak", "grain", false, 180, 4, 24, 8, 130, 400),
                dish("Sambal", "vegetable", false, 250, 4, 32, 12, 1200, 30, IdentifiedFood.KIND_ADDON),
                dish("Plain rice", "grain", false, 130, 2.4, 28, 0.3, 1, 200, IdentifiedFood.KIND_ADDON),
                // Marked a main by the model, but a beverage is still a drink.
                dish("Teh tarik", "beverage", false, 65, 1, 13, 1.2, 40, 350, IdentifiedFood.KIND_MAIN)));

        MenuRankingResponse response = serviceWith(vision).rank(new byte[]{1}, "image/jpeg", null, "en");

        List<String> tiered = response.tiers().stream()
                .flatMap(t -> t.dishes().stream()).map(MenuDish::name).toList();
        List<String> addOns = response.addOns().stream().map(MenuDish::name).toList();

        assertEquals(3, response.dishCount(), "only the mains are ranked");
        assertTrue(tiered.containsAll(List.of("Steamed chicken rice", "Fried chicken wings", "Nasi lemak")));
        assertTrue(addOns.contains("Sambal") && addOns.contains("Plain rice"));
        assertTrue(addOns.contains("Teh tarik"), "a beverage is a drink whatever the model called it");
        assertTrue(response.addOns().stream().allMatch(d -> d.tier() == null), "add-ons carry no tier");
    }

    @Test
    void aMenuOfNothingButDrinksHasNoDishesToRank() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(List.of(
                dish("Kopi O", "beverage", false, 20, 0.2, 4, 0.1, 10, 250),
                dish("Teh tarik", "beverage", false, 65, 1, 13, 1.2, 40, 350)));

        MenuRankingService service = serviceWith(vision);
        assertThrows(MenuRankingService.NoDishesDetectedException.class,
                () -> service.rank(new byte[]{1}, "image/jpeg", null, "en"));
    }

    @Test
    void throwsNoDishesDetectedWhenVisionReturnsEmptyList() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(List.of());

        MenuRankingService service = serviceWith(vision);
        assertThrows(MenuRankingService.NoDishesDetectedException.class,
                () -> service.rank(new byte[]{1}, "image/jpeg", null, "en"));
    }

    @Test
    void truncatesAt60DishesAndFlagsTruncated() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        List<IdentifiedFood> seventy = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            seventy.add(dish("Dish " + i, "grain", false, 150, 5, 20, 5, 300, 200));
        }
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(seventy);

        MenuRankingResponse response = serviceWith(vision).rank(new byte[]{1}, "image/jpeg", null, "en");

        assertEquals(60, response.dishCount());
        assertTrue(response.truncated());
    }

    @Test
    void eachDishIsScoredIndependentlyNotCombined() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        // A near-pure-protein dish and a near-pure-carb dish: combined, their
        // macros roughly balance out; scored alone, each is heavily skewed.
        IdentifiedFood proteinHeavy = dish("Grilled chicken breast", "protein", false, 165, 31, 0, 3.6, 74, 300);
        IdentifiedFood carbHeavy = dish("Plain white rice", "grain", false, 130, 2.4, 28, 0.3, 1, 300);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(List.of(proteinHeavy, carbHeavy));

        MenuRankingResponse response = serviceWith(vision).rank(new byte[]{1}, "image/jpeg", null, "en");

        var dishes = response.tiers().stream().flatMap(t -> t.dishes().stream()).toList();
        assertEquals(2, dishes.size());
        int proteinScore = dishes.stream().filter(d -> d.name().equals("Grilled chicken breast"))
                .findFirst().orElseThrow().score();
        int carbScore = dishes.stream().filter(d -> d.name().equals("Plain white rice"))
                .findFirst().orElseThrow().score();

        // What the two dishes would score if combined into one meal instead.
        ScoringService scoringService = new ScoringService(new ScoringProperties());
        UsdaClient usdaClient = new UsdaClient(new AppProperties());
        AnalysisService analysisService = new AnalysisService(
                null, usdaClient, emptyDishTable(), scoringService, null, null, null, null, new AppProperties(), new ObjectMapper());
        var combinedFoods = List.of(analysisService.resolveNutrition(proteinHeavy), analysisService.resolveNutrition(carbHeavy));
        int combinedScore = scoringService.score(combinedFoods, com.duabiskuttelur.model.Totals.of(combinedFoods), 2000).score();

        assertFalse(proteinScore == combinedScore && carbScore == combinedScore,
                "each dish should be scored on its own, not as if combined into one meal");
    }
}
