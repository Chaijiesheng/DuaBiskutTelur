package com.duabiskuttelur.service;

import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.FeedbackResult;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.service.FeedbackService.RemainingBudget;
import com.duabiskuttelur.service.ScoringService.ScoreResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the goal-aware trigger logic for the rule-based feedback path —
 * specifically the two boundary cases caught in review: a small weight-loss
 * meal shouldn't be flagged just because little budget is left (needs an
 * absolute floor), and a muscle-gain meal's protein bar shouldn't scale with
 * time of day (needs a flat threshold, not a percentage of what's remaining).
 */
class FeedbackServiceTest {

    private FeedbackService feedbackService;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackService(
                context -> { throw new UnsupportedOperationException("Gemini not expected in this test"); },
                new AppProperties(), // no Gemini key configured -> always routes through the rule-based path
                new ScoringProperties());
    }

    private static FoodItem food(double calories, double protein) {
        return new FoodItem("Test meal", "1 serving", calories, protein, 0, 0, 0, 0, 0, 0.9, "usda", "protein", false);
    }

    private FeedbackResult feedbackFor(double calories, double protein, String goal, RemainingBudget remaining) {
        List<FoodItem> foods = List.of(food(calories, protein));
        Totals totals = Totals.of(foods);
        ScoreResult score = new ScoringService(new ScoringProperties()).score(foods, totals);
        return feedbackService.feedbackFor(foods, totals, score, "en", goal, remaining, 2000);
    }

    @Test
    void weightLossFlagsPortionWhenOverHalfRemainingAndAboveFloor() {
        // remaining 800 kcal, meal 500 kcal: 500 > 400 (50%) and 500 > 300 floor
        FeedbackResult result = feedbackFor(500, 10, "weight_loss", new RemainingBudget(800, 100));
        assertTrue(result.concerns().stream().anyMatch(c -> c.contains("left today")),
                "expected the remaining-aware portion concern, got: " + result.concerns());
    }

    @Test
    void weightLossDoesNotFlagSmallMealEvenWhenBudgetIsAlmostGone() {
        // remaining 400 kcal, meal 250 kcal: 250 > 200 (50%) but NOT > 300 floor —
        // the exact case that a pure percentage rule gets wrong.
        FeedbackResult result = feedbackFor(250, 10, "weight_loss", new RemainingBudget(400, 100));
        assertFalse(result.concerns().stream().anyMatch(c -> c.contains("left today")),
                "a 250 kcal meal shouldn't trip the portion warning just because little budget remains, got: "
                        + result.concerns());
    }

    @Test
    void weightLossFallsBackToFixedThresholdForVisitors() {
        FeedbackResult flagged = feedbackFor(950, 10, "weight_loss", null);
        assertTrue(flagged.concerns().stream().anyMatch(c -> c.contains("big chunk")));

        FeedbackResult notFlagged = feedbackFor(850, 10, "weight_loss", null);
        assertFalse(notFlagged.concerns().stream().anyMatch(c -> c.contains("big chunk")));
    }

    @Test
    void muscleGainFlagsLowProteinWhenPlentyOfTargetRemains() {
        // remaining protein 100g (>50), meal protein 15g (<25)
        FeedbackResult result = feedbackFor(400, 15, "muscle_gain", new RemainingBudget(1000, 100));
        assertTrue(result.concerns().stream().anyMatch(c -> c.contains("still needed today")),
                "expected the remaining-aware protein concern, got: " + result.concerns());
    }

    @Test
    void muscleGainDoesNotFlagLowProteinNearEndOfDay() {
        // remaining protein 30g (<=50), meal protein 15g (<25) — little protein
        // target left regardless of this meal, so no point nagging.
        FeedbackResult result = feedbackFor(400, 15, "muscle_gain", new RemainingBudget(1000, 30));
        assertFalse(result.concerns().stream().anyMatch(c -> c.contains("still needed today")),
                "shouldn't flag low protein once the day's target is nearly used up, got: " + result.concerns());
    }

    @Test
    void muscleGainFallsBackToFixedThresholdForVisitors() {
        FeedbackResult flagged = feedbackFor(400, 15, "muscle_gain", null);
        assertTrue(flagged.concerns().stream().anyMatch(c -> c.contains("bit light")));

        FeedbackResult notFlagged = feedbackFor(400, 22, "muscle_gain", null);
        assertFalse(notFlagged.concerns().stream().anyMatch(c -> c.contains("bit light")));
    }

    @Test
    void beverageOnlyEntryDoesNotSuggestAddingVegetables() {
        // A scanned drink has no "side" to add vegetables to — recommending
        // ulam/kangkung/cucumber alongside a can of soda doesn't make sense.
        FoodItem drink = new FoodItem("Cola", "1 can (330ml)", 139, 0, 35, 0, 0, 35, 15,
                1.0, "barcode", "beverage", false);
        List<FoodItem> foods = List.of(drink);
        Totals totals = Totals.of(foods);
        ScoreResult score = new ScoringService(new ScoringProperties()).score(foods, totals);

        FeedbackResult result = feedbackService.feedbackFor(foods, totals, score, "en", "maintenance", null, 2000);

        assertFalse(result.concerns().stream().anyMatch(c -> c.contains("Low fiber")),
                "a beverage-only entry shouldn't get the low-fiber/add-vegetables concern, got: " + result.concerns());
    }

    @Test
    void maintenanceGoalNeverTriggersGoalSpecificConcerns() {
        FeedbackResult result = feedbackFor(2000, 5, "maintenance", new RemainingBudget(100, 5));
        assertFalse(result.concerns().stream().anyMatch(c -> c.contains("left today") || c.contains("big chunk")),
                "maintenance goal shouldn't trigger the weight-loss portion concern, got: " + result.concerns());
    }
}
