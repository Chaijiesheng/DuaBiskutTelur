package com.duabiskuttelur.model;

import java.util.List;

public record Totals(
        double calories,
        double protein,
        double carbs,
        double fat,
        double fiber,
        double sugar,
        double sodium
) {
    public static Totals of(List<FoodItem> foods) {
        double cal = 0, p = 0, c = 0, f = 0, fib = 0, sug = 0, sod = 0;
        for (FoodItem item : foods) {
            cal += item.calories();
            p += item.protein();
            c += item.carbs();
            f += item.fat();
            fib += item.fiber();
            sug += item.sugar();
            sod += item.sodium();
        }
        return new Totals(round1(cal), round1(p), round1(c), round1(f), round1(fib), round1(sug), round1(sod));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
