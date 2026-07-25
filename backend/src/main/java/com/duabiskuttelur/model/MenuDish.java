package com.duabiskuttelur.model;

/** One dish read off a menu, scored on its own (never combined with the other dishes on the same menu). */
public record MenuDish(
        String name,
        String estimatedPortion,
        int score,
        String grade,
        String tier,
        FoodItem nutrition
) {
}
