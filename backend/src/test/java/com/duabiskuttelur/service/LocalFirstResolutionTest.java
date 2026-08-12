package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.persistence.LocalFoodEntity;
import com.duabiskuttelur.persistence.LocalFoodRepository;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Resolution order is local → USDA → model. The order is the whole point: a
 * USDA search for "coconut rice" returns something, so without the local table
 * being consulted first, nasi lemak resolves to the nearest generic and looks
 * like a successful match rather than an approximation.
 */
class LocalFirstResolutionTest {

    private final LocalFoodRepository localFoods = Mockito.mock(LocalFoodRepository.class);
    private final UsdaClient usdaClient = Mockito.mock(UsdaClient.class);

    private static IdentifiedFood nasiLemak() {
        // usdaSearchTerm is the model translating a local dish into the nearest
        // generic USDA has — exactly the approximation the local table replaces.
        return new IdentifiedFood("Nasi lemak", "1 plate / ~300g", 300, 240, 360, "coconut rice",
                150, 3, 25, 5, 1, 1, 250, "grain", "steamed", 0.9);
    }

    private static LocalFoodEntity curatedNasiLemak() {
        LocalFoodEntity food = new LocalFoodEntity();
        food.setCanonicalName("nasi lemak");
        food.setDisplayName("Nasi lemak");
        food.setTypicalGrams(230);
        food.setCaloriesPer100g(180);
        food.setProteinPer100g(4);
        food.setCarbsPer100g(28);
        food.setFatPer100g(6);
        food.setFiberPer100g(1);
        food.setSugarPer100g(1);
        food.setSodiumPer100g(300);
        food.setFoodGroup("grain");
        food.setCookingMethod("steamed");
        food.setSource("curated");
        return food;
    }

    private AnalysisService analysisService() {
        AppProperties props = new AppProperties();
        props.setNutritionCacheEnabled(false);
        NutritionCacheService cache = new NutritionCacheService(
                Mockito.mock(NutritionCacheRepository.class), props, new SimpleMeterRegistry());
        return new AnalysisService(null, usdaClient, cache,
                new LocalFoodService(localFoods, new SimpleMeterRegistry()),
                null, null, null, null, null, props, new ObjectMapper(), new SimpleMeterRegistry());
    }

    private void haveLocalRow() {
        Mockito.when(localFoods.findByCanonicalName("nasi lemak")).thenReturn(Optional.of(curatedNasiLemak()));
        Mockito.when(localFoods.findByAlias(anyString())).thenReturn(Optional.empty());
    }

    private void haveNoLocalRow() {
        Mockito.when(localFoods.findByCanonicalName(anyString())).thenReturn(Optional.empty());
        Mockito.when(localFoods.findByAlias(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void aCuratedRowWinsAndUsdaIsNotEvenAsked() {
        haveLocalRow();

        FoodItem food = analysisService().resolveNutrition(nasiLemak());

        assertEquals("local", food.source());
        // 180 kcal/100g at the photo's 300g portion.
        assertEquals(540, food.calories(), 0.1);
        Mockito.verify(usdaClient, Mockito.never()).lookup(anyString());
    }

    @Test
    void withoutACuratedRowItStillFallsThroughToUsda() {
        haveNoLocalRow();
        Mockito.when(usdaClient.lookup("coconut rice")).thenReturn(Optional.of(
                new UsdaClient.NutrientsPer100g("Rice, coconut", 160, 3, 26, 5, 1, 1, 200)));

        FoodItem food = analysisService().resolveNutrition(nasiLemak());

        assertEquals("usda", food.source());
        assertEquals(480, food.calories(), 0.1);
    }

    @Test
    void andWithoutEitherItStillFallsThroughToTheModelEstimate() {
        haveNoLocalRow();
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());

        assertEquals("estimated", analysisService().resolveNutrition(nasiLemak()).source());
    }

    /**
     * The photo saw a plate; the table only knows a typical serving. A photo's
     * own portion has to win, or every nasi lemak in the app becomes 230g.
     */
    @Test
    void aPhotoKeepsThePortionItActuallySaw() {
        haveLocalRow();

        FoodItem food = analysisService().resolveNutrition(nasiLemak());

        assertEquals("1 plate / ~300g", food.estimatedPortion());
        assertTrue(food.caloriesHigh() > food.caloriesLow(),
                "the photo's portion uncertainty should survive a certain composition");
    }

    /**
     * A menu has no plate to measure, so it replays the pinned portion — and that
     * is exactly where a published typical serving beats the model's guess. An
     * earlier version of this wiring overwrote the curated grams with the scan's,
     * which silently threw the table's serving away for the one flow that wanted it.
     */
    @Test
    void aMenuReplaysTheCuratedTypicalServing() {
        haveLocalRow();

        FoodItem dish = analysisService().resolveNutrition(nasiLemak(), true);

        assertEquals(230 * 1.8, dish.calories(), 0.5);
        assertTrue(dish.estimatedPortion().contains("230"),
                "expected the curated serving, got: " + dish.estimatedPortion());
    }

    @Test
    void aCuratedRowWithGapsStillBorrowsTheModelsClassification() {
        LocalFoodEntity sparse = curatedNasiLemak();
        sparse.setFoodGroup(null);
        sparse.setCookingMethod(null);
        Mockito.when(localFoods.findByCanonicalName("nasi lemak")).thenReturn(Optional.of(sparse));
        Mockito.when(localFoods.findByAlias(anyString())).thenReturn(Optional.empty());

        FoodItem food = analysisService().resolveNutrition(nasiLemak());

        // A partially-filled row should improve on nothing, not erase what the
        // model could tell us.
        assertEquals("grain", food.foodGroup());
        assertEquals("steamed", food.cookingMethod());
        assertEquals("local", food.source());
    }
}
