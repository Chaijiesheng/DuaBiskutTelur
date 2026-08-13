package com.duabiskuttelur.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import com.duabiskuttelur.persistence.LocalFoodRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the order nutrition sources are tried in, because the order is not the
 * obvious one and was settled by measurement rather than argument.
 *
 * <p>Consulting the curated table first — answering "nasi lemak" from it and
 * never calling USDA — scored rho 0.665 against an expert ranking of 30
 * Malaysian dishes. Using it only after a USDA match has failed validation
 * scored 0.790. One curated row has to stand for a dish every stall cooks
 * differently, so it loses to a specific match that looks sound, and wins only
 * where that path has already produced something impossible.
 */
class NutritionResolutionTest {

    /** No USDA key configured, so every lookup misses and resolution falls through. */
    private AnalysisService serviceWithoutUsda() {
        AppProperties props = new AppProperties();
        // Off in production until the gate rejects on per-serving totals; these
        // tests describe the path itself, which is what stays true either way.
        props.setLocalDishTableEnabled(true);
        LocalDishTable table = new LocalDishTable();
        table.load();
        return new AnalysisService(null, new UsdaClient(props, new SimpleMeterRegistry()),
                nutritionCache(props), emptyLocalFoods(), table,
                new ScoringService(new ScoringProperties()), null, null, null, null, props,
                new ObjectMapper(), new SimpleMeterRegistry());
    }

    private static IdentifiedFood identified(String name, double grams, double kcalPer100g) {
        return new IdentifiedFood(name, "1 serving", grams, grams * 0.8, grams * 1.2, name,
                kcalPer100g, 5, 20, 4, 1, 2, 300, "grain", "steamed", 0.9, IdentifiedFood.KIND_MAIN);
    }

    @Test
    void aKnownLocalDishFallsBackToTheTableRatherThanTheModelGuess() {
        FoodItem resolved = serviceWithoutUsda().resolveNutrition(identified("Bak Kut Teh (RM18.90)", 400, 999));

        assertEquals("local", resolved.source(), "the curated row should beat the model's estimate");
        // 81 kcal/100g in the table, scaled to the 400g the model measured.
        assertEquals(324, resolved.calories(), 1.0);
    }

    @Test
    void anUnknownDishStillFallsBackToTheModelEstimate() {
        FoodItem resolved = serviceWithoutUsda().resolveNutrition(identified("Beef Wellington", 250, 280));

        assertEquals("estimated", resolved.source());
        assertEquals(700, resolved.calories(), 1.0);
    }

    /** The table carries the dish's own group and cooking method, not the photo's guess. */
    @Test
    void theTableSuppliesFoodGroupAndFriedFlag() {
        FoodItem resolved = serviceWithoutUsda().resolveNutrition(identified("Popiah Basah", 200, 100));

        assertEquals("vegetable", resolved.foodGroup());
        assertTrue(!resolved.fried(), "popiah is a fresh roll");
    }
    /** No curated rows: this test is about the USDA/validator/model ordering. */
    private static LocalFoodService emptyLocalFoods() {
        LocalFoodRepository repository = org.mockito.Mockito.mock(LocalFoodRepository.class);
        org.mockito.Mockito.when(repository.findByCanonicalName(org.mockito.Mockito.anyString()))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(repository.findByAlias(org.mockito.Mockito.anyString()))
                .thenReturn(java.util.Optional.empty());
        return new LocalFoodService(repository, new SimpleMeterRegistry());
    }

    /** A cache over a mocked repository — nothing is pinned across tests. */
    private static NutritionCacheService nutritionCache(AppProperties props) {
        return new NutritionCacheService(
                org.mockito.Mockito.mock(NutritionCacheRepository.class), props, new SimpleMeterRegistry());
    }

}
