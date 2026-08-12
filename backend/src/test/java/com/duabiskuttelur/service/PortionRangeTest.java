package com.duabiskuttelur.service;

import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.Totals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI3(c): the model now brackets its portion estimate, and that bracket becomes
 * the calorie range shown to the user. Portion estimation is the largest error
 * source in the pipeline, so a single number was the least honest thing on the
 * results screen.
 */
class PortionRangeTest {

    private static IdentifiedFood identified(double grams, double low, double high) {
        return new IdentifiedFood("Nasi lemak", "1 plate", grams, low, high, "coconut rice",
                200, 4, 30, 6, 1, 1, 300, "grain", "steamed", 0.9);
    }

    @Test
    void aBackwardsOrAbsentBracketCollapsesOntoThePointEstimate() {
        // A model that swaps the two, or omits them, must not produce a range
        // that reads as nonsense (low above high, or a 0 kcal floor).
        assertEquals(200, identified(200, 0, 0).lowGrams());
        assertEquals(200, identified(200, 0, 0).highGrams());
        assertEquals(160, identified(200, 240, 160).lowGrams(), "should tolerate the bracket arriving swapped");
        assertEquals(240, identified(200, 240, 160).highGrams());
        assertEquals(200, identified(200, 260, 280).lowGrams(), "a 'low' above the estimate is not a low");
    }

    @Test
    void itemsWithNoBracketStillSumCleanlyIntoTheMealBand() {
        // The 13-arg constructor is what the barcode path and every pre-bracket
        // stored row come through; a collapsed band has to stay summable.
        FoodItem exact = new FoodItem("Cola", "1 can", 139, 0, 35, 0, 0, 35, 15, 1.0, "barcode", "beverage", false);
        FoodItem estimated = new FoodItem("Nasi lemak", "1 plate", 400, 8, 60, 12, 2, 2, 600,
                0.9, "usda", "grain", false, "steamed", 320, 480);

        Totals totals = Totals.of(List.of(exact, estimated));

        assertEquals(539, totals.calories());
        assertEquals(139 + 320, totals.caloriesLow());
        assertEquals(139 + 480, totals.caloriesHigh());
    }

    @Test
    void aMealOfExactItemsHasNoBandToShow() {
        FoodItem exact = new FoodItem("Cola", "1 can", 139, 0, 35, 0, 0, 35, 15, 1.0, "barcode", "beverage", false);

        Totals totals = Totals.of(List.of(exact));

        assertFalse(totals.caloriesHigh() > totals.caloriesLow(),
                "a barcode scan knows its serving exactly — inventing a range there would be a lie");
    }

    @Test
    void theBandIsPresentationOnlyAndNeverMovesTheScore() {
        ScoringService scoring = new ScoringService(new ScoringProperties());
        List<FoodItem> narrow = List.of(new FoodItem("Nasi lemak", "1 plate", 400, 8, 60, 12, 2, 2, 600,
                0.9, "usda", "grain", false, "steamed", 395, 405));
        List<FoodItem> wide = List.of(new FoodItem("Nasi lemak", "1 plate", 400, 8, 60, 12, 2, 2, 600,
                0.9, "usda", "grain", false, "steamed", 100, 900));

        assertEquals(scoring.score(narrow, Totals.of(narrow), 2000).score(),
                scoring.score(wide, Totals.of(wide), 2000).score(),
                "the grade must stay deterministic arithmetic over the point estimate");
    }

    /**
     * AI3(d). A boolean forced char kway teow and deep-fried chicken wings to be
     * the same answer. They are not the same amount of oil, and the score now
     * says so — this is a deliberate change to existing grades.
     */
    @Test
    void stirFriedIsPenalizedLessThanDeepFried() {
        ScoringService scoring = new ScoringService(new ScoringProperties());

        double deepFried = scoring.friedPenalty(List.of(dishCooked("deep-fried")));
        double stirFried = scoring.friedPenalty(List.of(dishCooked("stir-fried")));
        double steamed = scoring.friedPenalty(List.of(dishCooked("steamed")));

        assertTrue(deepFried > stirFried, "deep-fried should cost more than stir-fried");
        assertTrue(stirFried > steamed, "stir-fried should still cost something");
        assertEquals(0, steamed);
    }

    @Test
    void aMealContainingBothIsJudgedOnTheDeepFriedItem() {
        ScoringService scoring = new ScoringService(new ScoringProperties());

        assertEquals(scoring.friedPenalty(List.of(dishCooked("deep-fried"))),
                scoring.friedPenalty(List.of(dishCooked("stir-fried"), dishCooked("deep-fried"))));
    }

    @Test
    void itemsWithNoCookingMethodFallBackToTheOldBooleanAndKeepTheFullPenalty() {
        ScoringService scoring = new ScoringService(new ScoringProperties());
        // What a pre-vocabulary stored row and the barcode path both look like.
        FoodItem legacy = new FoodItem("Ayam goreng", "1 thigh", 290, 21, 8, 19, 0, 0, 480,
                0.9, "estimated", "protein", true);

        assertEquals(scoring.friedPenalty(List.of(dishCooked("deep-fried"))),
                scoring.friedPenalty(List.of(legacy)));
    }

    private static FoodItem dishCooked(String method) {
        return new FoodItem("Test dish", "1 plate", 400, 10, 40, 20, 2, 3, 500, 0.9, "usda", "protein",
                com.duabiskuttelur.model.FoodTaxonomy.isFried(method), method, 400, 400);
    }
}
