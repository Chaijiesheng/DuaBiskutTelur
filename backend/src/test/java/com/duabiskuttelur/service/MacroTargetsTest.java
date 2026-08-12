package com.duabiskuttelur.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The macro splits exist twice — here and in `MACRO_TARGET_RATIO`
 * (calorieCalculator.js) — because the frontend displays them and the backend
 * grades against them. Nothing enforces that at build time, so this table and
 * the identical one in `calorieCalculator.test.js` are what does.
 *
 * <p>A drift would be silent: nothing crashes, the grade simply stops matching
 * the target shown on the same screen. That is exactly the bug N3 reported, and
 * it existed because the two numbers were never written down together.
 */
class MacroTargetsTest {

    /** Keep verbatim in step with PARITY_CASES in calorieCalculator.test.js. */
    private static final String[][] PARITY_CASES = {
            //  goal            protein  carbs   fat
            {"weight_loss",     "0.35",  "0.35", "0.30"},
            {"muscle_gain",     "0.30",  "0.45", "0.25"},
            {"maintenance",     "0.25",  "0.45", "0.30"},
    };

    @Test
    void matchesTheTargetsTheFrontendDisplays() {
        for (String[] c : PARITY_CASES) {
            MacroTargets.Split split = MacroTargets.forGoal(c[0]);
            assertEquals(Double.parseDouble(c[1]), split.protein(), 1e-9, c[0] + " protein");
            assertEquals(Double.parseDouble(c[2]), split.carbs(), 1e-9, c[0] + " carbs");
            assertEquals(Double.parseDouble(c[3]), split.fat(), 1e-9, c[0] + " fat");
        }
    }

    @Test
    void everySplitAddsUpToAWholeMeal() {
        // A split that does not sum to 1 makes the deviation arithmetic in
        // balancePoints meaningless — every meal would be "off target".
        for (String[] c : PARITY_CASES) {
            MacroTargets.Split split = MacroTargets.forGoal(c[0]);
            assertEquals(1.0, split.protein() + split.carbs() + split.fat(), 1e-9, c[0]);
        }
    }

    @Test
    void anUnknownOrAbsentGoalFallsBackToMaintenance() {
        assertEquals(MacroTargets.forGoal("maintenance"), MacroTargets.forGoal(null));
        assertEquals(MacroTargets.forGoal("maintenance"), MacroTargets.forGoal("bulking"));
    }
}
