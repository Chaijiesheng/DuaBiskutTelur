package com.duabiskuttelur.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The activity multiplier already bakes in a level of daily movement for
 * "normal_workout"/"daily_workout", so 12,000 tracked steps shouldn't add the
 * same extra calories on top of a very-active multiplier as it would on top
 * of a sedentary one — that would credit the same activity twice.
 */
class CalorieBudgetTest {

    private static Integer budgetWithSteps(String exerciseFrequency, Integer steps) {
        return CalorieBudget.compute(30, "male", 70.0, 175.0, steps, exerciseFrequency, "maintenance");
    }

    @Test
    void highStepCountAddsLessOnTopOfAMoreActiveMultiplier() {
        int sedentaryBoost = budgetWithSteps("not_workout", 12000) - budgetWithSteps("not_workout", 0);
        int normalBoost = budgetWithSteps("normal_workout", 12000) - budgetWithSteps("normal_workout", 0);
        int dailyBoost = budgetWithSteps("daily_workout", 12000) - budgetWithSteps("daily_workout", 0);

        assertTrue(sedentaryBoost > normalBoost,
                "sedentary should get more step credit than normal_workout, got " + sedentaryBoost + " vs " + normalBoost);
        assertTrue(normalBoost > dailyBoost,
                "normal_workout should get more step credit than daily_workout, got " + normalBoost + " vs " + dailyBoost);
        assertTrue(dailyBoost > 0, "daily_workout should still get some step credit, not zero");
    }

    @Test
    void stepsBelowTheBaselineAddNothingRegardlessOfExerciseFrequency() {
        assertEquals(budgetWithSteps("daily_workout", 3000), budgetWithSteps("daily_workout", 0));
        assertEquals(budgetWithSteps("not_workout", 2000), budgetWithSteps("not_workout", 0));
    }

    /**
     * This class and frontend/src/calorieCalculator.js implement the same
     * formula twice — the frontend previews a budget as the user types, this one
     * stores the authoritative value so it isn't client-tamperable. Both carry a
     * "keep in sync" comment and nothing enforced it, so they could drift and the
     * only symptom would be the number changing the moment the user pressed Save.
     *
     * <p>These five cases are duplicated verbatim in calorieCalculator.test.js.
     * Either side drifting breaks its own test, and the fix is to return to the
     * shared numbers rather than to edit them. Change a case here only alongside
     * the JavaScript one.
     */
    @Test
    void matchesTheFrontendPreviewOnTheSharedParityCases() {
        // baseline maintenance, moderate steps
        assertEquals(2630, CalorieBudget.compute(
                30, "male", 70.0, 175.0, 6000, "normal_workout", "maintenance"));
        // weight loss applies the percentage, not the cap
        assertEquals(1440, CalorieBudget.compute(
                25, "female", 55.0, 160.0, 12000, "not_workout", "weight_loss"));
        // muscle gain hits the absolute cap before the percentage
        assertEquals(3740, CalorieBudget.compute(
                40, "male", 90.0, 180.0, 15000, "daily_workout", "muscle_gain"));
        // clamps up to the floor
        assertEquals(1200, CalorieBudget.compute(
                70, "female", 35.0, 140.0, 0, "not_workout", "weight_loss"));
        // clamps down to the ceiling
        assertEquals(4500, CalorieBudget.compute(
                20, "male", 150.0, 200.0, 30000, "daily_workout", "muscle_gain"));
    }
}
