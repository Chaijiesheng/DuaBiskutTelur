package com.duabiskuttelur.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One food item as identified by the vision provider. Nutrition here is the
 * model's fallback estimate (per 100g) used only when the USDA lookup fails.
 *
 * <p>Every string here originates in text the model read off a user-supplied
 * photo, so all of them are scrubbed on construction — see {@link UntrustedText}
 * for what that means and why it happens at the record rather than at the call
 * sites. {@code name} in particular is later interpolated into the feedback
 * prompt, and {@code usdaSearchTerm} into an outbound query string.
 *
 * <p>{@code foodGroup} and {@code cookingMethod} are additionally checked
 * against {@link FoodTaxonomy}: the schema sent to the model constrains them to
 * a closed vocabulary, and this makes sure nothing outside it reaches the
 * scorer regardless.
 *
 * <p>{@code gramsLow}/{@code gramsHigh} bracket {@code grams}. Portion
 * estimation is the largest error source in the pipeline, and a single number
 * hides that entirely — asking for the bracket costs nothing and is what lets
 * the app show an honest calorie range instead of a false precision.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentifiedFood(
        String name,
        String estimatedPortion,
        double grams,
        double gramsLow,
        double gramsHigh,
        String usdaSearchTerm,
        double fallbackCaloriesPer100g,
        double fallbackProteinPer100g,
        double fallbackCarbsPer100g,
        double fallbackFatPer100g,
        double fallbackFiberPer100g,
        double fallbackSugarPer100g,
        double fallbackSodiumPer100g,
        String foodGroup,
        String cookingMethod,
        double confidence
) {
    public IdentifiedFood {
        name = UntrustedText.clean(name, UntrustedText.MAX_NAME);
        estimatedPortion = UntrustedText.clean(estimatedPortion, UntrustedText.MAX_PORTION);
        usdaSearchTerm = UntrustedText.clean(usdaSearchTerm, UntrustedText.MAX_SEARCH_TERM);
        foodGroup = FoodTaxonomy.normalizeGroup(foodGroup);
        cookingMethod = FoodTaxonomy.normalizeMethod(cookingMethod);
    }

    /**
     * The portion bracket, or the point estimate repeated when the model gave no
     * usable one. Ordering is enforced rather than trusted — a model that swaps
     * the two, or returns a "low" above the point estimate, would otherwise
     * produce a calorie range that reads as nonsense on screen.
     */
    public double lowGrams() {
        double low = Math.min(gramsLow, gramsHigh);
        return low > 0 && low <= grams ? low : grams;
    }

    /** See {@link #lowGrams()}. */
    public double highGrams() {
        double high = Math.max(gramsLow, gramsHigh);
        return high >= grams ? high : grams;
    }

    /**
     * Whether this item should take the fried penalty. Derived from
     * {@code cookingMethod} rather than stored, so there is one definition of
     * "fried" in the codebase instead of a boolean that can disagree with it.
     */
    public boolean fried() {
        return FoodTaxonomy.isFried(cookingMethod);
    }
}
