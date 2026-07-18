package com.duabiskuttelur.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One food item as identified by the vision provider. Nutrition here is the
 * model's fallback estimate (per 100g) used only when the USDA lookup fails.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentifiedFood(
        String name,
        String estimatedPortion,
        double grams,
        String usdaSearchTerm,
        double fallbackCaloriesPer100g,
        double fallbackProteinPer100g,
        double fallbackCarbsPer100g,
        double fallbackFatPer100g,
        double fallbackFiberPer100g,
        double fallbackSugarPer100g,
        double fallbackSodiumPer100g,
        String foodGroup,
        boolean fried,
        double confidence
) {
}
