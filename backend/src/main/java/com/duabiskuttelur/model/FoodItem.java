package com.duabiskuttelur.model;

/**
 * A single identified food with resolved nutrition, as returned to the frontend.
 * source is "usda" when nutrition came from FoodData Central, "estimated" when
 * the Claude fallback estimate was used.
 */
public record FoodItem(
        String name,
        String estimatedPortion,
        double calories,
        double protein,
        double carbs,
        double fat,
        double fiber,
        double sugar,
        double sodium,
        double confidence,
        String source,
        String foodGroup,
        boolean fried
) {
}
