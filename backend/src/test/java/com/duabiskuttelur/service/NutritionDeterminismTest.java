package com.duabiskuttelur.service;

import com.duabiskuttelur.persistence.LocalFoodRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.MenuDish;
import com.duabiskuttelur.model.MenuRankingResponse;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Validates the determinism guarantee on what the user actually reads — the
 * per-serving totals of a scan (calories and macros for the portion on the
 * plate/menu), not the per-100g basis the cache stores internally. A cache that
 * pinned the basis but let the portion re-roll would still report different
 * calories every scan and would pass a per-100g check.
 *
 * <p>Each config is measured over {@value #SCANS_PER_CONFIG} scans of the same
 * photo with a vision stub that re-rolls its estimate every call — the real
 * lottery, since the model regenerates nutrition, portion and fried/foodGroup
 * per request. Cache off is the control: it must show variance, otherwise the
 * measurement isn't sensitive enough to prove anything about cache on.
 */
class NutritionDeterminismTest {

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

    /** Scans per config. The ask was at least 5; more only re-confirms the same fixed point. */
    private static final int SCANS_PER_CONFIG = 5;

    /** Same three dishes every scan — only their resolved numbers are re-rolled. */
    private static final List<String> MENU = List.of(
            "Char kway teow", "Steamed fish with ginger", "Iced sweetened milk tea");

    /**
     * One scan's worth of vision output. Every field the model controls moves
     * with {@code scan}: nutrition, portion size and the fried/foodGroup flags
     * that ScoringService grades on.
     */
    private static List<IdentifiedFood> roll(int scan) {
        List<IdentifiedFood> foods = new ArrayList<>();
        for (int i = 0; i < MENU.size(); i++) {
            double drift = scan * 13 + i * 7;
            double grams = 180 + scan * 40 + i * 15;
            foods.add(new IdentifiedFood(
                    MENU.get(i), "1 serving / ~" + (int) grams + "g", grams,
                    // The bracket drifts per scan too, exactly like the nutrients:
                    // if it were not pinned, the displayed calorie range would
                    // change between two scans of the same menu.
                    grams - 10 - drift, grams + 10 + drift, MENU.get(i),
                    150 + drift, 8 + scan, 20 + scan * 2, 6 + scan, 2, 3, 400 + drift * 10,
                    scan % 2 == 0 ? "grain" : "protein",
                    scan % 2 == 0 ? "deep-fried" : "steamed", 0.9));
        }
        return foods;
    }

    private record Harness(MenuRankingService menu, AnalysisService analysis, AtomicInteger scans) {
    }

    /**
     * Real ScoringService, AnalysisService and NutritionCacheService; only the
     * vision provider and the cache's repository are stubbed (no USDA key, so
     * resolution takes the model-estimate path without touching the network).
     */
    private static Harness harness(boolean cacheEnabled) {
        AppProperties props = new AppProperties();
        props.setGeminiApiKeys(List.of("test-key")); // else the services return their mock fixtures
        props.setNutritionCacheEnabled(cacheEnabled);

        AtomicInteger scans = new AtomicInteger();
        VisionAnalysisClient vision = Mockito.mock(VisionAnalysisClient.class);
        Mockito.when(vision.identifyMenuDishes(any(), anyString()))
                .thenAnswer(invocation -> roll(scans.getAndIncrement()));
        Mockito.when(vision.identifyFoods(any(), anyString()))
                .thenAnswer(invocation -> roll(scans.getAndIncrement()));

        ScoringService scoring = new ScoringService(new ScoringProperties());
        NutritionCacheService cache = new NutritionCacheService(
                Mockito.mock(NutritionCacheRepository.class), props, new SimpleMeterRegistry());
        AnalysisService analysis = new AnalysisService(
                vision, new UsdaClient(new AppProperties(), new SimpleMeterRegistry()), cache, emptyLocalFoods(), scoring,
                null, null, null, null, props, new ObjectMapper(), new SimpleMeterRegistry());
        MenuRankingService menu = new MenuRankingService(
                vision, scoring, analysis, null, null, props, new ObjectMapper());
        return new Harness(menu, analysis, scans);
    }

    /** Per-serving totals of one menu scan, plus the tier line-up it produced. */
    private record MenuOutcome(Totals totals, List<String> tiers, List<Integer> scores) {
    }

    private static List<MenuOutcome> measureMenu(boolean cacheEnabled) {
        Harness harness = harness(cacheEnabled);
        List<MenuOutcome> outcomes = new ArrayList<>();
        for (int scan = 0; scan < SCANS_PER_CONFIG; scan++) {
            MenuRankingResponse response = harness.menu().rank(new byte[]{1}, "image/jpeg", null, "en");
            List<MenuDish> dishes = response.tiers().stream().flatMap(t -> t.dishes().stream()).toList();
            outcomes.add(new MenuOutcome(
                    Totals.of(dishes.stream().map(MenuDish::nutrition).toList()),
                    dishes.stream().map(MenuDish::tier).toList(),
                    dishes.stream().map(MenuDish::score).toList()));
        }
        return outcomes;
    }

    /**
     * Per-serving totals of one photo scan. Resolution is driven directly rather
     * than through analyze(), which would also need the feedback, dashboard and
     * persistence collaborators — totals() in the response is exactly this.
     */
    private static List<Totals> measurePhoto(boolean cacheEnabled) {
        Harness harness = harness(cacheEnabled);
        List<Totals> totals = new ArrayList<>();
        for (int scan = 0; scan < SCANS_PER_CONFIG; scan++) {
            // Same photo every time, so the portion the model reads off it is the
            // same too — that's what makes repeat totals comparable at all.
            List<IdentifiedFood> identified = fixedPortion(roll(harness.scans().getAndIncrement()));
            List<FoodItem> foods = identified.stream().map(harness.analysis()::resolveNutrition).toList();
            totals.add(Totals.of(foods));
        }
        return totals;
    }

    /** Replays scan 0's portions: a photo shows the same amount of food no matter how often it's scanned. */
    private static List<IdentifiedFood> fixedPortion(List<IdentifiedFood> rolled) {
        List<IdentifiedFood> pinned = new ArrayList<>();
        List<IdentifiedFood> first = roll(0);
        for (int i = 0; i < rolled.size(); i++) {
            IdentifiedFood r = rolled.get(i);
            pinned.add(new IdentifiedFood(r.name(), first.get(i).estimatedPortion(), first.get(i).grams(),
                    r.gramsLow(), r.gramsHigh(),
                    r.usdaSearchTerm(), r.fallbackCaloriesPer100g(), r.fallbackProteinPer100g(),
                    r.fallbackCarbsPer100g(), r.fallbackFatPer100g(), r.fallbackFiberPer100g(),
                    r.fallbackSugarPer100g(), r.fallbackSodiumPer100g(), r.foodGroup(),
                    r.cookingMethod(), r.confidence()));
        }
        return pinned;
    }

    private static <T> Set<T> distinct(List<T> values) {
        return new LinkedHashSet<>(values);
    }

    /** How far apart the same scan's calorie figure landed — the number the user would have seen move. */
    private static String calorieSpread(Set<Totals> totals) {
        double min = totals.stream().mapToDouble(Totals::calories).min().orElse(0);
        double max = totals.stream().mapToDouble(Totals::calories).max().orElse(0);
        return String.format("calories %.1f-%.1f (spread %.0f%%)", min, max, (max - min) / min * 100);
    }

    @Test
    void menuScanReportsIdenticalPerServingTotalsAcrossScans() {
        List<MenuOutcome> outcomes = measureMenu(true);

        Set<Totals> totals = distinct(outcomes.stream().map(MenuOutcome::totals).toList());
        System.out.printf("[menu, cache on ] %d scans -> %d distinct per-serving totals: %s%n",
                SCANS_PER_CONFIG, totals.size(), totals);

        assertEquals(1, totals.size(), "per-serving totals moved between scans: " + totals);
        assertEquals(1, distinct(outcomes.stream().map(MenuOutcome::tiers).toList()).size(),
                "tier line-up moved between scans");
        assertEquals(1, distinct(outcomes.stream().map(MenuOutcome::scores).toList()).size(),
                "dish scores moved between scans");
    }

    @Test
    void photoScanReportsIdenticalPerServingTotalsForTheSamePortion() {
        List<Totals> totals = measurePhoto(true);

        System.out.printf("[photo, cache on ] %d scans -> %d distinct per-serving totals: %s%n",
                SCANS_PER_CONFIG, distinct(totals).size(), distinct(totals));

        assertEquals(1, distinct(totals).size(), "per-serving totals moved between scans: " + distinct(totals));
    }

    /**
     * Control. Without the cache every scan re-rolls, so the measurement above
     * is only meaningful if this one comes out the opposite way — a harness that
     * can't see the variance can't prove it was removed.
     */
    @Test
    void withoutTheCacheTheSameScanKeepsProducingDifferentTotals() {
        Set<Totals> menuTotals = distinct(measureMenu(false).stream().map(MenuOutcome::totals).toList());
        Set<Totals> photoTotals = distinct(measurePhoto(false));

        System.out.printf("[menu, cache off] %d scans -> %d distinct per-serving totals, %s%n",
                SCANS_PER_CONFIG, menuTotals.size(), calorieSpread(menuTotals));
        System.out.printf("[photo, cache off] %d scans -> %d distinct per-serving totals, %s%n",
                SCANS_PER_CONFIG, photoTotals.size(), calorieSpread(photoTotals));

        assertTrue(menuTotals.size() > 1, "control failed: menu totals were stable without the cache");
        assertTrue(photoTotals.size() > 1, "control failed: photo totals were stable without the cache");
    }
}
