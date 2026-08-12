package com.duabiskuttelur.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Sanity checks for a hand-transcribed nutrition row.
 *
 * <p>Curated data is only worth its highest-trust badge if the transcription is
 * right, and a slipped decimal point in one nutrient column is invisible on
 * inspection — the row still looks like a plausible dish. These are the checks
 * that catch it without needing the source table to hand.
 *
 * <p>The load-bearing one is the Atwater cross-check: calories are not an
 * independent measurement, they are protein and carbohydrate at 4 kcal/g plus
 * fat at 9. If the stated energy does not agree with the stated macros, at least
 * one of the four numbers was copied wrong. Everything else here is a bound that
 * no real food crosses.
 *
 * <p>Deliberately lenient rather than strict. Published composition tables round
 * their figures, count fibre's energy differently between editions, and
 * sometimes report alcohol or organic acids that these four macros do not
 * capture — so the tolerance is set to flag transcription errors, not to
 * second-guess a laboratory.
 */
public final class NutrientPlausibility {

    /** kcal per gram — the Atwater general factors. */
    private static final double KCAL_PER_G_PROTEIN = 4;
    private static final double KCAL_PER_G_CARB = 4;
    private static final double KCAL_PER_G_FAT = 9;

    /**
     * How far the macro-derived energy may sit from the stated energy. Wide on
     * purpose: 25% absorbs rounding, fibre-energy convention differences, and
     * the ~7 kcal/g of any alcohol, while a slipped decimal is off by 900%.
     */
    private static final double ENERGY_TOLERANCE = 0.25;

    /** Below this, a percentage tolerance is meaninglessly tight. Plain water is 0 kcal. */
    private static final double ENERGY_FLOOR_KCAL = 20;

    /** Nothing edible exceeds this per 100g: pure fat is 900. */
    private static final double MAX_KCAL_PER_100G = 900;

    /** Pure salt is ~39 g sodium per 100g; a composed dish nowhere near it. */
    private static final double MAX_SODIUM_MG_PER_100G = 12_000;

    private NutrientPlausibility() {
    }

    /** One row's nutrients, per 100g, sodium in mg. */
    public record Row(String name, double calories, double protein, double carbs,
                      double fat, double fiber, double sugar, double sodium) {
    }

    /**
     * @return every problem found, empty when the row is plausible. All of them
     *         rather than the first, so a curator fixing a batch sees the whole
     *         list instead of one per build.
     */
    public static List<String> problems(Row row) {
        List<String> problems = new ArrayList<>();

        checkNonNegative(problems, "calories", row.calories());
        checkNonNegative(problems, "protein", row.protein());
        checkNonNegative(problems, "carbs", row.carbs());
        checkNonNegative(problems, "fat", row.fat());
        checkNonNegative(problems, "fiber", row.fiber());
        checkNonNegative(problems, "sugar", row.sugar());
        checkNonNegative(problems, "sodium", row.sodium());

        if (row.calories() > MAX_KCAL_PER_100G) {
            problems.add("calories %.0f/100g exceeds pure fat (%.0f)".formatted(row.calories(), MAX_KCAL_PER_100G));
        }
        if (row.sodium() > MAX_SODIUM_MG_PER_100G) {
            problems.add("sodium %.0fmg/100g is beyond any composed dish".formatted(row.sodium()));
        }
        // Macros are grams of a 100g sample, so they cannot sum past it. Checked
        // without fibre, which is already counted inside carbohydrate.
        double macroGrams = row.protein() + row.carbs() + row.fat();
        if (macroGrams > 100) {
            problems.add("protein + carbs + fat = %.1fg in a 100g sample".formatted(macroGrams));
        }
        // Both are components of carbohydrate, not additions to it.
        if (row.fiber() > row.carbs()) {
            problems.add("fiber %.1fg exceeds carbs %.1fg".formatted(row.fiber(), row.carbs()));
        }
        if (row.sugar() > row.carbs()) {
            problems.add("sugar %.1fg exceeds carbs %.1fg".formatted(row.sugar(), row.carbs()));
        }

        double fromMacros = row.protein() * KCAL_PER_G_PROTEIN
                + row.carbs() * KCAL_PER_G_CARB
                + row.fat() * KCAL_PER_G_FAT;
        if (Math.max(row.calories(), fromMacros) >= ENERGY_FLOOR_KCAL) {
            double drift = Math.abs(fromMacros - row.calories()) / Math.max(row.calories(), 1);
            if (drift > ENERGY_TOLERANCE) {
                problems.add(("stated %.0f kcal but the macros give %.0f (%.0f%% off) — "
                        + "one of calories/protein/carbs/fat was transcribed wrong")
                        .formatted(row.calories(), fromMacros, drift * 100));
            }
        }
        return problems;
    }

    private static void checkNonNegative(List<String> problems, String field, double value) {
        if (value < 0) {
            problems.add(field + " is negative (" + value + ")");
        }
    }
}
