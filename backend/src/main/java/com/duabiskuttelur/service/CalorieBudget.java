package com.duabiskuttelur.service;

/**
 * Server-side daily calorie budget estimate. Mirrors the frontend
 * calorieCalculator.js (Mifflin-St Jeor BMR + activity multiplier + steps,
 * then a goal-based deficit/surplus), so the stored budget is authoritative
 * and not client-tamperable.
 */
public final class CalorieBudget {

    private CalorieBudget() {
    }

    private static final int MIN_BUDGET = 1200;
    private static final int MAX_BUDGET = 4500;
    private static final int BASELINE_STEPS = 3000;
    private static final double KCAL_PER_STEP_AT_70KG = 0.04;

    // The activity multiplier already assumes a level of daily movement —
    // "daily_workout" (1.725x) bakes in very-active-lifestyle steps, so
    // crediting tracked steps at full weight on top of it double-counts the
    // same activity. Discount step credit as the multiplier already accounts
    // for more of it; a sedentary profile gets full credit since 12k tracked
    // steps there really is activity the 1.2x multiplier doesn't capture.
    private static final double STEP_CREDIT_NOT_WORKOUT = 1.0;
    private static final double STEP_CREDIT_NORMAL_WORKOUT = 0.6;
    private static final double STEP_CREDIT_DAILY_WORKOUT = 0.3;

    // Percentage adjustment applied to TDEE, capped in absolute kcal so it
    // doesn't get disproportionate at very low or very high TDEE — see
    // goalAdjustment() below. Unknown/null goal (e.g. a profile saved before
    // this field existed) is treated as maintenance: no adjustment.
    private static final double WEIGHT_LOSS_PCT = -0.20;
    private static final double MUSCLE_GAIN_PCT = 0.12;
    private static final double WEIGHT_LOSS_CAP = -750;
    private static final double MUSCLE_GAIN_CAP = 400;

    /** Returns null if the profile is incomplete or the exercise level is unknown. */
    public static Integer compute(Integer age, String sex, Double weightKg, Double heightCm,
                                  Integer steps, String exerciseFrequency, String goal) {
        if (age == null || sex == null || weightKg == null || heightCm == null || exerciseFrequency == null) {
            return null;
        }
        Double multiplier = activityMultiplier(exerciseFrequency);
        if (multiplier == null) {
            return null;
        }
        double bmr = 10 * weightKg + 6.25 * heightCm - 5 * age + ("female".equals(sex) ? -161 : 5);
        int stepCount = steps != null ? steps : 0;
        double extraSteps = Math.max(0, stepCount - BASELINE_STEPS);
        double stepsCalories = extraSteps * KCAL_PER_STEP_AT_70KG * (weightKg / 70.0) * stepCreditFor(exerciseFrequency);
        double tdee = bmr * multiplier + stepsCalories;
        double total = tdee + goalAdjustment(goal, tdee);
        int rounded = (int) (Math.round(total / 10.0) * 10);
        return Math.min(MAX_BUDGET, Math.max(MIN_BUDGET, rounded));
    }

    private static double goalAdjustment(String goal, double tdee) {
        if ("weight_loss".equals(goal)) {
            return Math.max(tdee * WEIGHT_LOSS_PCT, WEIGHT_LOSS_CAP);
        }
        if ("muscle_gain".equals(goal)) {
            return Math.min(tdee * MUSCLE_GAIN_PCT, MUSCLE_GAIN_CAP);
        }
        return 0;
    }

    private static double stepCreditFor(String frequency) {
        return switch (frequency) {
            case "normal_workout" -> STEP_CREDIT_NORMAL_WORKOUT;
            case "daily_workout" -> STEP_CREDIT_DAILY_WORKOUT;
            default -> STEP_CREDIT_NOT_WORKOUT;
        };
    }

    private static Double activityMultiplier(String frequency) {
        return switch (frequency) {
            case "not_workout" -> 1.2;
            case "normal_workout" -> 1.55;
            case "daily_workout" -> 1.725;
            default -> null;
        };
    }
}
