package com.duabiskuttelur.service;

import com.duabiskuttelur.persistence.LocalFoodRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.MenuDish;
import com.duabiskuttelur.model.MenuRankingResponse;
import com.duabiskuttelur.model.MenuRankingResponse.TierGroup;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * A local database with nothing in it — the shipped state. Exercises the
     * local-first branch and proves it falls through to USDA rather than
     * short-circuiting resolution.
     */
    private static LocalFoodService emptyLocalFoods() {
        LocalFoodRepository repository = Mockito.mock(LocalFoodRepository.class);
        Mockito.when(repository.findByCanonicalName(Mockito.anyString())).thenReturn(java.util.Optional.empty());
        Mockito.when(repository.findByAlias(Mockito.anyString())).thenReturn(java.util.Optional.empty());
        return new LocalFoodService(repository, new SimpleMeterRegistry());
    }

    private MenuRankingService serviceWith(VisionAnalysisClient visionClient) {
        return serviceWith(visionClient, new AppProperties());
    }

    private MenuRankingService serviceWith(VisionAnalysisClient visionClient, UsdaClient usdaClient,
                                            AppProperties props) {
        props.setGeminiApiKeys(List.of("test-key"));
        ScoringService scoringService = new ScoringService(new ScoringProperties());
        AnalysisService analysisService = new AnalysisService(
                null, usdaClient, nutritionCache(props), emptyLocalFoods(), scoringService, null, null, null, null, props, new ObjectMapper(), new SimpleMeterRegistry());
        return new MenuRankingService(visionClient, scoringService, analysisService, null, null, props, new ObjectMapper());
    }

    private MenuRankingService serviceWith(VisionAnalysisClient visionClient, AppProperties propsIn) {
        AppProperties props = propsIn;
        // no USDA key -> always falls back, no network
        return serviceWith(visionClient, new UsdaClient(new AppProperties(), new SimpleMeterRegistry()), props);
    }

    /**
     * Real cache over a mocked repository: findByCanonicalName returns
     * Optional.empty() (Mockito's default for Optional) and save is a no-op, so
     * the DB-backed layer is inert and only the in-memory memo is exercised — no
     * Spring context or database needed.
     */
    private static NutritionCacheService nutritionCache(AppProperties props) {
        return new NutritionCacheService(Mockito.mock(NutritionCacheRepository.class), props, new SimpleMeterRegistry());
    }

    private static IdentifiedFood dish(String name, String group, boolean fried,
                                        double caloriesPer100g, double proteinPer100g, double carbsPer100g,
                                        double fatPer100g, double sodiumPer100g, double grams) {
        return new IdentifiedFood(name, "1 serving / ~" + (int) grams + "g", grams, grams * 0.8, grams * 1.2, name,
                caloriesPer100g, proteinPer100g, carbsPer100g, fatPer100g, 1.5, 2, sodiumPer100g, group,
                fried ? "deep-fried" : "steamed", 0.9);
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

    /**
     * The bug this guards: scanning the same menu twice re-ran the resolution
     * lottery, so a dish's calories — and with them its grade and tier — could
     * move between scans. The vision stub below deliberately returns wildly
     * different nutrition for the same dish name on the second scan; the cache
     * must replay the first resolution and ignore it.
     */
    @Test
    void sameDishResolvesIdenticallyOnEveryScan() {
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString()))
                .thenReturn(List.of(dish("Char kway teow", "grain", true, 176, 6, 22, 7, 620, 350)))
                // Same dish, re-rolled: leaner, unfried, differently classified, smaller portion.
                .thenReturn(List.of(dish("char-kway-teow", "protein", false, 90, 20, 5, 2, 100, 120)));

        MenuRankingService service = serviceWith(vision);
        MenuDish first = onlyDish(service.rank(new byte[]{1}, "image/jpeg", null, "en"));
        MenuDish second = onlyDish(service.rank(new byte[]{1}, "image/jpeg", null, "en"));

        assertEquals(first.nutrition().calories(), second.nutrition().calories());
        assertEquals(first.nutrition().protein(), second.nutrition().protein());
        assertEquals(first.nutrition().sodium(), second.nutrition().sodium());
        assertEquals(first.nutrition().foodGroup(), second.nutrition().foodGroup());
        assertEquals(first.nutrition().fried(), second.nutrition().fried());
        assertEquals(first.estimatedPortion(), second.estimatedPortion());
        assertEquals(first.score(), second.score());
        assertEquals(first.tier(), second.tier());
        // Sanity: the first scan's numbers are the ones that stuck, not the second's.
        assertEquals(616, first.nutrition().calories());
    }

    private static MenuDish onlyDish(MenuRankingResponse response) {
        List<MenuDish> dishes = response.tiers().stream().flatMap(t -> t.dishes().stream()).toList();
        assertEquals(1, dishes.size());
        return dishes.get(0);
    }

    @Test
    void mapsEachGradeBandToItsTierKey() {
        assertEquals("HANG", TierMapping.tierFor("A+"));
        assertEquals("TOP", TierMapping.tierFor("A"));
        assertEquals("RENSHANGREN", TierMapping.tierFor("B"));
        assertEquals("NPC", TierMapping.tierFor("C"));
        assertEquals("LAWANLE", TierMapping.tierFor("D"));
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
        UsdaClient usdaClient = new UsdaClient(new AppProperties(), new SimpleMeterRegistry());
        AppProperties props = new AppProperties();
        AnalysisService analysisService = new AnalysisService(
                null, usdaClient, nutritionCache(props), emptyLocalFoods(), scoringService, null, null, null, null, props, new ObjectMapper(), new SimpleMeterRegistry());
        var combinedFoods = List.of(analysisService.resolveNutrition(proteinHeavy), analysisService.resolveNutrition(carbHeavy));
        int combinedScore = scoringService.score(combinedFoods, com.duabiskuttelur.model.Totals.of(combinedFoods), 2000).score();

        assertFalse(proteinScore == combinedScore && carbScore == combinedScore,
                "each dish should be scored on its own, not as if combined into one meal");
    }

    /**
     * Dishes used to be resolved strictly one after another, so a menu's latency
     * was the sum of every lookup rather than the longest — a cold 60-dish menu
     * could outlast the gateway timeout on nothing but serial round trips. The
     * assertion is on wall clock rather than on thread counts because that is
     * the thing that was actually broken.
     */
    @Test
    void dishesOnOneMenuResolveConcurrentlyRatherThanOneAtATime() {
        int dishes = 16;
        Duration perLookup = Duration.ofMillis(200);

        List<IdentifiedFood> menu = new ArrayList<>();
        for (int i = 0; i < dishes; i++) {
            // Distinct names: the nutrition cache memoises per dish, so repeats
            // would be served from memory and measure nothing.
            menu.add(dish("Dish number " + i, "protein", false, 150, 10, 12, 5, 300, 200));
        }
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(menu);

        AtomicInteger lookups = new AtomicInteger();
        UsdaClient slowUsda = Mockito.mock(UsdaClient.class);
        Mockito.when(slowUsda.lookup(anyString())).thenAnswer(invocation -> {
            lookups.incrementAndGet();
            Thread.sleep(perLookup.toMillis());
            return Optional.empty(); // falls back to the model estimate, as with no USDA key
        });

        AppProperties props = new AppProperties();
        props.setMenuResolveParallelism(8);
        MenuRankingService service = serviceWith(vision, slowUsda, props);

        long startedAt = System.nanoTime();
        MenuRankingResponse response = service.rank(new byte[]{1}, "image/jpeg", null, "en");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertEquals(dishes, lookups.get(), "every dish should still be resolved exactly once");
        assertEquals(dishes, response.dishCount());

        // Serial would be 16 x 200ms = 3.2s. At a parallelism of 8 this is two
        // waves, so ~400ms; the ceiling is loose enough for a slow CI box while
        // staying far below the serial figure.
        assertTrue(elapsedMs < 1_500,
                "resolution looks serial — 16 dishes x 200ms took " + elapsedMs + "ms");
    }

    /** Order is what the menu was read in, not whichever lookup happened to finish first. */
    @Test
    void concurrentResolutionKeepsTheMenusOwnDishOrder() {
        List<IdentifiedFood> menu = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            menu.add(dish("Dish " + i, "protein", false, 150, 10, 12, 5, 300, 200));
        }
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString())).thenReturn(menu);

        UsdaClient jitteryUsda = Mockito.mock(UsdaClient.class);
        Mockito.when(jitteryUsda.lookup(anyString())).thenAnswer(invocation -> {
            // Later dishes finish first, so anything relying on completion order breaks.
            String term = invocation.getArgument(0);
            int index = Integer.parseInt(term.substring("Dish ".length()));
            Thread.sleep((12 - index) * 15L);
            return Optional.empty();
        });

        MenuRankingService service = serviceWith(vision, jitteryUsda, new AppProperties());
        MenuRankingResponse response = service.rank(new byte[]{1}, "image/jpeg", null, "en");

        List<String> names = response.tiers().stream().flatMap(t -> t.dishes().stream()).map(MenuDish::name).toList();
        assertEquals(12, names.size());
        // All dishes score identically here, so they land in one tier and the
        // tier's own list is the submission order.
        assertEquals("Dish 0", names.get(0));
        assertEquals("Dish 11", names.get(11));
    }
}
