package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient.NutrientsPer100g;
import com.duabiskuttelur.model.IdentifiedFood;

import java.util.Optional;

/**
 * Decides whether a USDA match is believable enough to use in place of the
 * model's own estimate.
 *
 * <p>USDA's search is a fuzzy text match over a database of American foods and
 * generic ingredients. Ask it about a composite restaurant dish and it answers
 * confidently with whatever was closest, which in practice has produced a
 * plain broth for a soup-and-rice set and a bran-like 25g of fibre per 100g for
 * a chicken chop rice. Nothing downstream can tell those apart from real data —
 * they're simply wrong numbers with a "usda" label on them, which then drive
 * the score and the ranking.
 *
 * <p>Two families of check. The first is absolute: values no food can have,
 * caught without reference to anything else. The second compares the match
 * against what the vision model independently estimated for the same dish —
 * both are per-100g, so they're directly comparable. Neither number is
 * authoritative on its own, but a large disagreement means they are describing
 * different foods, and between a generic database row and a model that actually
 * read the dish name, the model is the safer of the two.
 *
 * <p>Thresholds are deliberately loose. This is a guard against nonsense, not a
 * second opinion on plausible data — a match only has to be arguable to pass.
 */
final class NutritionValidator {

    /** Pure fat is ~900 kcal/100g; nothing edible sits above it, and near-zero means water, not food. */
    private static final double MIN_KCAL_PER_100G = 5;
    private static final double MAX_KCAL_PER_100G = 920;

    /** Wheat bran, about the most fibrous thing there is, is ~43g/100g. */
    private static final double MAX_FIBER_PER_100G = 50;

    /** Protein+carbs+fat converted at 4/4/9 shouldn't wildly overshoot the stated energy. */
    private static final double MAX_MACRO_ENERGY_RATIO = 1.6;

    /** Calorie densities differing by more than this factor aren't the same dish. */
    private static final double MAX_CALORIE_DISAGREEMENT = 2.5;

    /**
     * Fibre is small in absolute terms, so ratios explode on noise; requiring a
     * large absolute gap keeps this pointed at genuine mismatches.
     */
    private static final double MAX_FIBER_EXCESS_PER_100G = 15;

    /*
     * The five rules below were derived by auditing a 30-dish Malaysian
     * benchmark: every USDA row that produced an impossible dish is caught by
     * one of them, and no plausible row is. Each is a structural fault — a
     * number a real dish cannot have, or a category error the checks above
     * can't see because the match is internally consistent and merely about a
     * different food.
     */

    /** No single restaurant dish is 60g of protein per 100g; that's near-pure meat powder. */
    private static final double MAX_PROTEIN_PER_100G = 60;

    /** Likewise carbohydrate: over this is dry flour or uncooked noodles, not a served dish. */
    private static final double MAX_CARBS_PER_100G = 90;

    /** Fibre above this share of carbohydrate means bran, not a rice or flour dish. */
    private static final double MAX_FIBER_SHARE_OF_CARBS = 0.25;

    /** A dish named after its starch has to contain some. */
    private static final double MIN_CARBS_FOR_STARCH_DISH_PER_100G = 8;

    /** Dish names that promise a starch base, in the romanisations menus actually use. */
    private static final java.util.regex.Pattern STARCH_DISH = java.util.regex.Pattern.compile(
            "\\b(nasi|rice|mee|mi|mihun|bihun|noodle|kway|kuey|koay|teow|hor fun|"
            + "pasta|bread|roti|canai|naan|bun|porridge|congee|bubur)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private NutritionValidator() {
    }

    /**
     * @return why this match shouldn't be trusted, or empty when it's usable.
     *         The text is logged, so it names the actual numbers.
     */
    static Optional<String> rejectionReason(NutrientsPer100g match, IdentifiedFood modelEstimate) {
        if (match.calories() < MIN_KCAL_PER_100G || match.calories() > MAX_KCAL_PER_100G) {
            return Optional.of("implausible energy density: %.0f kcal/100g".formatted(match.calories()));
        }
        if (match.fiber() > MAX_FIBER_PER_100G) {
            return Optional.of("impossible fibre: %.1fg/100g".formatted(match.fiber()));
        }
        if (match.protein() < 0 || match.carbs() < 0 || match.fat() < 0 || match.fiber() < 0) {
            return Optional.of("negative nutrient values");
        }
        double macroEnergy = match.protein() * 4 + match.carbs() * 4 + match.fat() * 9;
        if (macroEnergy > match.calories() * MAX_MACRO_ENERGY_RATIO) {
            return Optional.of("macros don't reconcile: %.0f kcal of protein/carbs/fat against %.0f kcal stated"
                    .formatted(macroEnergy, match.calories()));
        }
        if (match.protein() > MAX_PROTEIN_PER_100G) {
            return Optional.of("implausible protein: %.1fg/100g".formatted(match.protein()));
        }
        if (match.carbs() > MAX_CARBS_PER_100G) {
            return Optional.of("implausible carbohydrate: %.1fg/100g — reads as a dry ingredient, not a served dish"
                    .formatted(match.carbs()));
        }
        if (match.carbs() > 0 && match.fiber() > match.carbs() * MAX_FIBER_SHARE_OF_CARBS) {
            return Optional.of("%.1fg fibre against %.1fg carbohydrate — too bran-like for this dish"
                    .formatted(match.fiber(), match.carbs()));
        }
        // Both exactly zero alongside real carbohydrate is the signature of a
        // partially-populated USDA row rather than a genuine measurement, and
        // it silently hands the dish a clean sheet on the sugar penalty.
        if (match.carbs() > 0 && match.fiber() == 0 && match.sugar() == 0) {
            return Optional.of("fibre and sugar both exactly zero against %.1fg carbohydrate — incomplete row"
                    .formatted(match.carbs()));
        }
        if (STARCH_DISH.matcher(modelEstimate.name()).find()
                && match.carbs() < MIN_CARBS_FOR_STARCH_DISH_PER_100G) {
            return Optional.of("only %.1fg carbohydrate/100g for a dish named as a rice, noodle or bread"
                    .formatted(match.carbs()));
        }

        // The model didn't venture an estimate, so there's nothing to compare
        // against and the match stands on its own.
        double estimatedCalories = modelEstimate.fallbackCaloriesPer100g();
        if (estimatedCalories <= 0) {
            return Optional.empty();
        }

        double ratio = Math.max(match.calories() / estimatedCalories, estimatedCalories / match.calories());
        if (ratio > MAX_CALORIE_DISAGREEMENT) {
            return Optional.of("%.0f kcal/100g against the model's %.0f — %.1fx apart, likely a different food"
                    .formatted(match.calories(), estimatedCalories, ratio));
        }
        if (match.fiber() > modelEstimate.fallbackFiberPer100g() + MAX_FIBER_EXCESS_PER_100G) {
            return Optional.of("%.1fg fibre/100g against the model's %.1fg"
                    .formatted(match.fiber(), modelEstimate.fallbackFiberPer100g()));
        }
        return Optional.empty();
    }
}
