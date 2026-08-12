package com.duabiskuttelur.service;

import java.util.Map;

/**
 * The macro split a meal is graded against, per goal.
 *
 * <p>These are the same three sets the frontend already displays in
 * `MACRO_TARGET_RATIO` (calorieCalculator.js). They had to be brought here
 * because the two disagreed: `MacroDonut` showed a maintenance user "target 25%"
 * for protein while `ScoringService` graded everyone against a flat 30/40/30 —
 * so the app told you what to aim for and then marked you down for hitting it.
 * A user could see both numbers on the same screen.
 *
 * <p><b>These duplicate `MACRO_TARGET_RATIO` and must not drift.</b> The same
 * problem `CalorieBudget`/`calorieCalculator.js` has, and it is handled the same
 * way: a parity table asserted verbatim on both sides
 * ({@code MacroTargetsTest} and {@code calorieCalculator.test.js}). A drift
 * would be invisible — nothing crashes, the grade just quietly stops matching
 * the target on screen.
 */
public final class MacroTargets {

    /** Fractions of total macro calories, summing to 1.0. */
    public record Split(double protein, double carbs, double fat) {
    }

    private static final Map<String, Split> BY_GOAL = Map.of(
            "weight_loss", new Split(0.35, 0.35, 0.30),
            "muscle_gain", new Split(0.30, 0.45, 0.25),
            "maintenance", new Split(0.25, 0.45, 0.30));

    /** Used for an unset or unrecognized goal — the same fallback the frontend makes. */
    public static final String DEFAULT_GOAL = "maintenance";

    private MacroTargets() {
    }

    /**
     * @param goal may be null — a user who has not set one, or a visitor, which
     *             is the common case and not an error. Map.of rejects a null key
     *             outright rather than missing, so this cannot be a getOrDefault.
     */
    public static Split forGoal(String goal) {
        Split split = goal == null ? null : BY_GOAL.get(goal);
        return split != null ? split : BY_GOAL.get(DEFAULT_GOAL);
    }
}
