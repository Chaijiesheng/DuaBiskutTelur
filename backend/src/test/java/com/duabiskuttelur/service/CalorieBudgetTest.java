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
}
