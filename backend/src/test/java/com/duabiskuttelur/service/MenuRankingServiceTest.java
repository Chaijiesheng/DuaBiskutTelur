package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.MenuRankingResponse;
import com.duabiskuttelur.model.MenuRankingResponse.TierGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

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
                null, usdaClient, scoringService, null, null, null, null, props, new ObjectMapper());
        return new MenuRankingService(visionClient, scoringService, analysisService, null, null, props, new ObjectMapper());
    }

    private static IdentifiedFood dish(String name, String group, boolean fried,
                                        double caloriesPer100g, double proteinPer100g, double carbsPer100g,
                                        double fatPer100g, double sodiumPer100g, double grams) {
        return new IdentifiedFood(name, "1 serving / ~" + (int) grams + "g", grams, name,
                caloriesPer100g, proteinPer100g, carbsPer100g, fatPer100g, 1.5, 2, sodiumPer100g, group, fried, 0.9);
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
                null, usdaClient, scoringService, null, null, null, null, new AppProperties(), new ObjectMapper());
        var combinedFoods = List.of(analysisService.resolveNutrition(proteinHeavy), analysisService.resolveNutrition(carbHeavy));
        int combinedScore = scoringService.score(combinedFoods, com.duabiskuttelur.model.Totals.of(combinedFoods), 2000).score();

        assertFalse(proteinScore == combinedScore && carbScore == combinedScore,
                "each dish should be scored on its own, not as if combined into one meal");
    }
}
