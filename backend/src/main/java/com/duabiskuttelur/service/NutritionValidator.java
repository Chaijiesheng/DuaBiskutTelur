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
 * <p>Three families of check, in the order they run.
 *
 * <p><b>Per 100g, absolute.</b> Values no food can have, caught without
 * reference to anything else — plus two pieces of pure arithmetic, that macros
 * must reconcile with stated energy at 4/4/9 and that sugar cannot exceed the
 * carbohydrate it is part of.
 *
 * <p><b>Per serving.</b> Density times the portion the model saw. A row can be
 * arguable per 100g and absurd on the plate, and the first family cannot see it
 * — this is why "254 kcal/100g" passes while the bowl it describes does not.
 * Note what this cannot do: a serving total is density times portion and the
 * check cannot say which was wrong. Rejecting helps when the density was at
 * fault; when the portion estimate is the bad number, rejection keeps it.
 *
 * <p><b>Against the model's own estimate.</b> Both are per-100g, so they're
 * directly comparable. Neither number is authoritative on its own, but a large
 * disagreement means they are describing different foods, and between a generic
 * database row and a model that actually read the dish name, the model is the
 * safer of the two.
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

    /**
     * Sodium was not checked at any scale until now, which is how a chicken
     * satay resolved to a row carrying 3345 mg/100g and was used — soy-sauce
     * territory, roughly six times the saltiest dish in the curated table. The
     * bound is set well clear of real food: across 55 curated Malaysian dishes
     * the highest is sambal at 900 mg/100g, and the highest actual dish is 600.
     */
    private static final double MAX_SODIUM_PER_100G = 2000;

    /*
     * The per-serving family, added because the density checks above cannot see
     * a whole class of fault: a match whose density is arguable becomes absurd
     * once multiplied by the portion. Those are separate questions and both have
     * to be asked.
     *
     * Set very loose on purpose. The validator already turns away 10-15 dishes
     * of 30 on a menu scan, and these are meant to catch plates no single dish
     * reaches, not to add a second opinion on large ones — a 700g mixed-rice
     * plate is a real thing and must survive. Each rule is separately tagged in
     * usda.match.rejected, so if one of them does start firing often, it says so
     * itself rather than hiding inside the total.
     *
     * Known limit, worth stating plainly: a serving total is density times
     * portion, and this cannot tell which of the two was wrong. Rejecting the
     * match only helps when the density was at fault. When the portion estimate
     * is the bad number, rejection swaps in the model's density and keeps the
     * same wrong portion — outstanding item 12, not something a nutrition check
     * can fix.
     */

    /** Beyond any single served dish; a whole day's budget is ~2000. */
    private static final double MAX_KCAL_PER_SERVING = 2000;

    /** WHO's whole-day guidance is 2000mg. No one dish delivers two and a half days of it. */
    private static final double MAX_SODIUM_PER_SERVING = 5000;

    /** Dish names that promise a starch base, in the romanisations menus actually use. */
    private static final java.util.regex.Pattern STARCH_DISH = java.util.regex.Pattern.compile(
            "\\b(nasi|rice|mee|mi|mihun|bihun|noodle|kway|kuey|koay|teow|hor fun|"
            + "pasta|bread|roti|canai|naan|bun|porridge|congee|bubur)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Which rule turned a match away.
     *
     * <p>Separate from the message because the message interpolates the actual
     * numbers, and a metric tagged with those would be a new time series per
     * dish — an unbounded tag is a slow memory leak that also makes the
     * dashboard useless. These fifteen are the whole vocabulary.
     *
     * <p>The rejection rate is what sets how often the resolver falls past USDA,
     * so it decides how much work the curated table and the model estimate are
     * asked to do. Production rejected 10-15 of 30 dishes on a menu scan and
     * there was no way to ask which rule was responsible; that is what this is
     * for. Accepted matches are already counted as
     * {@link com.duabiskuttelur.config.AppMetrics#NUTRITION_SOURCE} with
     * {@code source=usda}, so the rejection rate is a ratio of the two and needs
     * no third counter.
     */
    enum Rule {
        ENERGY_DENSITY,
        IMPOSSIBLE_FIBER,
        NEGATIVE_VALUES,
        MACROS_UNRECONCILED,
        PROTEIN_DENSITY,
        CARB_DENSITY,
        SODIUM_DENSITY,
        FIBER_VS_CARBS,
        INCOMPLETE_ROW,
        STARCH_WITHOUT_CARBS,
        SUGAR_EXCEEDS_CARBS,
        CALORIES_PER_SERVING,
        SODIUM_PER_SERVING,
        CALORIE_DISAGREEMENT,
        FIBER_DISAGREEMENT;

        /** The metric tag value: lowercase, stable, safe to build a dashboard on. */
        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Why a match was turned away: the rule for counting, the text for reading. */
    record Rejection(Rule rule, String message) {
    }

    private NutritionValidator() {
    }

    private static Optional<Rejection> reject(Rule rule, String message) {
        return Optional.of(new Rejection(rule, message));
    }

    /**
     * @return why this match shouldn't be trusted, or empty when it's usable.
     *         The message names the actual numbers and is logged; the
     *         {@link Rule} is the closed-vocabulary version for metrics.
     */
    static Optional<Rejection> rejectionReason(NutrientsPer100g match, IdentifiedFood modelEstimate) {
        if (match.calories() < MIN_KCAL_PER_100G || match.calories() > MAX_KCAL_PER_100G) {
            return reject(Rule.ENERGY_DENSITY,
                    "implausible energy density: %.0f kcal/100g".formatted(match.calories()));
        }
        if (match.fiber() > MAX_FIBER_PER_100G) {
            return reject(Rule.IMPOSSIBLE_FIBER, "impossible fibre: %.1fg/100g".formatted(match.fiber()));
        }
        if (match.protein() < 0 || match.carbs() < 0 || match.fat() < 0 || match.fiber() < 0) {
            return reject(Rule.NEGATIVE_VALUES, "negative nutrient values");
        }
        double macroEnergy = match.protein() * 4 + match.carbs() * 4 + match.fat() * 9;
        if (macroEnergy > match.calories() * MAX_MACRO_ENERGY_RATIO) {
            return reject(Rule.MACROS_UNRECONCILED,
                    "macros don't reconcile: %.0f kcal of protein/carbs/fat against %.0f kcal stated"
                            .formatted(macroEnergy, match.calories()));
        }
        if (match.protein() > MAX_PROTEIN_PER_100G) {
            return reject(Rule.PROTEIN_DENSITY, "implausible protein: %.1fg/100g".formatted(match.protein()));
        }
        if (match.carbs() > MAX_CARBS_PER_100G) {
            return reject(Rule.CARB_DENSITY,
                    "implausible carbohydrate: %.1fg/100g — reads as a dry ingredient, not a served dish"
                            .formatted(match.carbs()));
        }
        if (match.sodium() > MAX_SODIUM_PER_100G) {
            return reject(Rule.SODIUM_DENSITY,
                    "implausible sodium: %.0fmg/100g — reads as a sauce or seasoning, not a dish"
                            .formatted(match.sodium()));
        }
        if (match.carbs() > 0 && match.fiber() > match.carbs() * MAX_FIBER_SHARE_OF_CARBS) {
            return reject(Rule.FIBER_VS_CARBS,
                    "%.1fg fibre against %.1fg carbohydrate — too bran-like for this dish"
                            .formatted(match.fiber(), match.carbs()));
        }
        // Both exactly zero alongside real carbohydrate is the signature of a
        // partially-populated USDA row rather than a genuine measurement, and
        // it silently hands the dish a clean sheet on the sugar penalty.
        if (match.carbs() > 0 && match.fiber() == 0 && match.sugar() == 0) {
            return reject(Rule.INCOMPLETE_ROW,
                    "fibre and sugar both exactly zero against %.1fg carbohydrate — incomplete row"
                            .formatted(match.carbs()));
        }
        if (STARCH_DISH.matcher(modelEstimate.name()).find()
                && match.carbs() < MIN_CARBS_FOR_STARCH_DISH_PER_100G) {
            return reject(Rule.STARCH_WITHOUT_CARBS,
                    "only %.1fg carbohydrate/100g for a dish named as a rice, noodle or bread"
                            .formatted(match.carbs()));
        }

        // Arithmetic rather than a threshold: sugars are a component of
        // carbohydrate, so more of the part than the whole means one of the two
        // was copied from a different food. Nothing checked this, and sugar
        // drives its own penalty in the scorer.
        //
        // Deliberately last of the per-100g checks, and only where there is a
        // whole to be a part of. A row with no carbohydrate at all is a row
        // missing carbohydrate data — the starch rule above says something far
        // more useful about it, and firing here instead would report a
        // wrong-food match as a sugar-data problem on the dashboard. The
        // half-gram allowance is for rows that merely round badly.
        if (match.carbs() > 0 && match.sugar() > match.carbs() + 0.5) {
            return reject(Rule.SUGAR_EXCEEDS_CARBS,
                    "%.1fg sugar against %.1fg carbohydrate — sugar is part of carbohydrate"
                            .formatted(match.sugar(), match.carbs()));
        }

        // Everything above judged the match per 100g. These two ask the separate
        // question of what lands on the plate: a density that is merely arguable
        // can still produce a serving no dish reaches. Skipped entirely when the
        // model gave no portion — there is nothing to multiply by, and assuming
        // one would invent the very number being checked.
        double grams = modelEstimate.grams();
        if (grams > 0) {
            double servingCalories = match.calories() * grams / 100.0;
            if (servingCalories > MAX_KCAL_PER_SERVING) {
                return reject(Rule.CALORIES_PER_SERVING,
                        "%.0f kcal for a %.0fg serving — beyond any single dish"
                                .formatted(servingCalories, grams));
            }
            double servingSodium = match.sodium() * grams / 100.0;
            if (servingSodium > MAX_SODIUM_PER_SERVING) {
                return reject(Rule.SODIUM_PER_SERVING,
                        "%.0fmg sodium for a %.0fg serving — over two days' worth in one dish"
                                .formatted(servingSodium, grams));
            }
        }

        // The model didn't venture an estimate, so there's nothing to compare
        // against and the match stands on its own.
        double estimatedCalories = modelEstimate.fallbackCaloriesPer100g();
        if (estimatedCalories <= 0) {
            return Optional.empty();
        }

        double ratio = Math.max(match.calories() / estimatedCalories, estimatedCalories / match.calories());
        if (ratio > MAX_CALORIE_DISAGREEMENT) {
            return reject(Rule.CALORIE_DISAGREEMENT,
                    "%.0f kcal/100g against the model's %.0f — %.1fx apart, likely a different food"
                            .formatted(match.calories(), estimatedCalories, ratio));
        }
        if (match.fiber() > modelEstimate.fallbackFiberPer100g() + MAX_FIBER_EXCESS_PER_100G) {
            return reject(Rule.FIBER_DISAGREEMENT, "%.1fg fibre/100g against the model's %.1fg"
                    .formatted(match.fiber(), modelEstimate.fallbackFiberPer100g()));
        }
        return Optional.empty();
    }
}
