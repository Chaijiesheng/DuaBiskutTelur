package com.duabiskuttelur.model;

/**
 * A single identified food with resolved nutrition, as returned to the frontend.
 * source is "usda" when nutrition came from FoodData Central, "estimated" when
 * the Gemini fallback estimate was used.
 *
 * <p>The strings are scrubbed on construction ({@link UntrustedText}). They are
 * usually already clean, having come through {@link IdentifiedFood}, but two
 * paths do not: barcode scans name the food from Open Food Facts, which is
 * openly editable, and Jackson rebuilds these from {@code result_json} rows
 * written before any of this existed.
 *
 * <p>{@code caloriesLow}/{@code caloriesHigh} carry the model's portion bracket
 * through to the UI. They collapse onto {@code calories} when there is no real
 * range — a barcode scan knows its serving exactly, and rows stored before the
 * bracket existed deserialize to zeros — so callers can sum them unconditionally
 * and "is there a range worth showing" is just {@code high > low}.
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
        boolean fried,
        String cookingMethod,
        double caloriesLow,
        double caloriesHigh,
        // 1.0 until the user says the portion was wrong. Kept alongside the
        // scaled numbers rather than folded into them so a correction is
        // absolute rather than cumulative: setting 0.5 then 2.0 returns exactly
        // the original figures instead of drifting, and estimatedPortion can
        // still show what the model actually estimated.
        double portionMultiplier
) {
    public FoodItem {
        name = UntrustedText.clean(name, UntrustedText.MAX_NAME);
        estimatedPortion = UntrustedText.clean(estimatedPortion, UntrustedText.MAX_PORTION);
        source = UntrustedText.clean(source, UntrustedText.MAX_FOOD_GROUP);
        foodGroup = FoodTaxonomy.normalizeGroup(foodGroup);
        cookingMethod = FoodTaxonomy.normalizeMethod(cookingMethod);
        if (caloriesLow <= 0 && caloriesHigh <= 0) {
            caloriesLow = calories;
            caloriesHigh = calories;
        }
        // Rows written before corrections existed deserialize this as 0.
        if (portionMultiplier <= 0) {
            portionMultiplier = 1.0;
        }
    }

    /**
     * For nutrition that is exact or carries no portion bracket: barcode scans,
     * the keyless demo data, and the tests that predate the bracket. Delegates to
     * the canonical constructor with the range collapsed onto the point value.
     */
    public FoodItem(String name, String estimatedPortion, double calories, double protein, double carbs,
                    double fat, double fiber, double sugar, double sodium, double confidence,
                    String source, String foodGroup, boolean fried) {
        this(name, estimatedPortion, calories, protein, carbs, fat, fiber, sugar, sodium, confidence,
                source, foodGroup, fried, fried ? "deep-fried" : null, calories, calories, 1.0);
    }

    /** For the AI path, which knows the calorie bracket but never starts corrected. */
    public FoodItem(String name, String estimatedPortion, double calories, double protein, double carbs,
                    double fat, double fiber, double sugar, double sodium, double confidence,
                    String source, String foodGroup, boolean fried, String cookingMethod,
                    double caloriesLow, double caloriesHigh) {
        this(name, estimatedPortion, calories, protein, carbs, fat, fiber, sugar, sodium, confidence,
                source, foodGroup, fried, cookingMethod, caloriesLow, caloriesHigh, 1.0);
    }
}
