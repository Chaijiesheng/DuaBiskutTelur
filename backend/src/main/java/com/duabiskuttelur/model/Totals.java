package com.duabiskuttelur.model;

import java.util.List;

/**
 * Summed nutrition for one logged meal.
 *
 * <p>{@code caloriesLow}/{@code caloriesHigh} are the meal's calorie band,
 * summed from each item's portion bracket. They collapse onto {@code calories}
 * when no item carried a bracket, so {@code caloriesHigh > caloriesLow} is the
 * test for "there is real uncertainty here worth showing". Everything that
 * grades or budgets still uses the point estimate — the band is presentation,
 * not arithmetic, so a wider band never changes a score.
 */
public record Totals(
        double calories,
        double protein,
        double carbs,
        double fat,
        double fiber,
        double sugar,
        double sodium,
        double caloriesLow,
        double caloriesHigh
) {
    /** Totals with no portion band, for callers that have only point values (tests, fixtures). */
    public Totals(double calories, double protein, double carbs, double fat,
                  double fiber, double sugar, double sodium) {
        this(calories, protein, carbs, fat, fiber, sugar, sodium, calories, calories);
    }

    public static Totals of(List<FoodItem> foods) {
        double cal = 0, p = 0, c = 0, f = 0, fib = 0, sug = 0, sod = 0, low = 0, high = 0;
        for (FoodItem item : foods) {
            cal += item.calories();
            p += item.protein();
            c += item.carbs();
            f += item.fat();
            fib += item.fiber();
            sug += item.sugar();
            sod += item.sodium();
            low += item.caloriesLow();
            high += item.caloriesHigh();
        }
        return new Totals(round1(cal), round1(p), round1(c), round1(f), round1(fib), round1(sug), round1(sod),
                round1(low), round1(high));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
