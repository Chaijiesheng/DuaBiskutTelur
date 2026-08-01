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
        double confidence,
        /**
         * Menu scans only: "main", "addon" or "drink", read off the menu's own
         * sections. Null for plate photos, which have no such notion — every
         * caller treats null as a main. Lets menu ranking keep condiments and
         * teh tarik out of a tier list that's meant to answer "what should I
         * order", where a spoon of sambal isn't a competing answer.
         */
        String kind
) {
    public static final String KIND_MAIN = "main";
    public static final String KIND_ADDON = "addon";
    public static final String KIND_DRINK = "drink";

    /** True for anything that isn't a dish you'd order as your meal. */
    public boolean isSideOrDrink() {
        // Belt and braces: the model classifies from the menu layout, but a
        // beverage is a drink whatever section it was printed in.
        return KIND_ADDON.equalsIgnoreCase(kind)
                || KIND_DRINK.equalsIgnoreCase(kind)
                || "beverage".equalsIgnoreCase(foodGroup);
    }
}
