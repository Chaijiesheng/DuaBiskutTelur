package com.duabiskuttelur.service;

import com.duabiskuttelur.service.NutrientPlausibility.Row;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks that make hand-transcribed composition data safe to badge as the
 * app's most trustworthy source. A slipped decimal in one nutrient column is
 * invisible on inspection — the row still reads like a plausible dish — so the
 * cross-checks have to be arithmetic rather than eyeballed.
 */
class NutrientPlausibilityTest {

    private static String report(Row row) {
        return String.join("; ", NutrientPlausibility.problems(row));
    }

    /** Cooked white rice, roughly: 130 kcal, 2.7g protein, 28g carbs, 0.3g fat. */
    private static final Row PLAIN_RICE = new Row("rice", 130, 2.7, 28, 0.3, 0.4, 0.1, 1);

    @Test
    void acceptsAnOrdinaryRow() {
        assertEquals("", report(PLAIN_RICE));
    }

    /**
     * The load-bearing check. Calories are not an independent measurement — they
     * are protein and carbohydrate at 4 kcal/g plus fat at 9 — so a stated energy
     * that disagrees with the stated macros means one of the four was copied
     * wrong, and nothing else in the row reveals which.
     */
    @Test
    void catchesADecimalPointSlipInTheEnergyColumn() {
        Row slipped = new Row("rice", 13, 2.7, 28, 0.3, 0.4, 0.1, 1);
        assertTrue(report(slipped).contains("transcribed wrong"), report(slipped));
    }

    @Test
    void catchesADecimalPointSlipInAMacroColumn() {
        // Fat 0.3 -> 30 g. Energy stays plausible-looking at a glance.
        Row slipped = new Row("rice", 130, 2.7, 28, 30, 0.4, 0.1, 1);
        assertTrue(report(slipped).contains("transcribed wrong"), report(slipped));
    }

    /**
     * Composition tables round, count fibre's energy differently between
     * editions, and sometimes carry alcohol or organic acids these four macros
     * miss. The tolerance exists to catch transcription errors, not to
     * second-guess a laboratory.
     */
    @Test
    void toleratesTheRoundingRealTablesActuallyPublish() {
        assertEquals("", report(new Row("rice", 130, 2.7, 28.4, 0.3, 0.4, 0.1, 1)));
        assertEquals("", report(new Row("rice", 128, 3, 28, 0.5, 0.4, 0.1, 1)));
    }

    @Test
    void doesNotDemandArithmeticFromNearZeroEnergyFoods() {
        // Plain water and black coffee would fail any percentage-based check.
        assertEquals("", report(new Row("plain water", 0, 0, 0, 0, 0, 0, 2)));
        assertEquals("", report(new Row("teh o kosong", 2, 0, 0.4, 0, 0, 0.3, 5)));
    }

    @Test
    void rejectsImpossibleTotals() {
        assertTrue(report(new Row("x", 950, 0, 0, 105, 0, 0, 0)).contains("exceeds pure fat"));
        assertTrue(report(new Row("x", 400, 40, 40, 40, 0, 0, 0)).contains("100g sample"));
        assertTrue(report(new Row("x", 130, 2.7, 28, 0.3, 0.4, 0.1, 50_000)).contains("sodium"));
    }

    @Test
    void rejectsComponentsThatExceedWhatTheyAreComponentsOf() {
        // Both fibre and sugar are parts of carbohydrate, not additions to it.
        assertTrue(report(new Row("x", 130, 2.7, 28, 0.3, 30, 0.1, 1)).contains("fiber"));
        assertTrue(report(new Row("x", 130, 2.7, 28, 0.3, 0.4, 30, 1)).contains("sugar"));
    }

    @Test
    void rejectsNegatives() {
        assertTrue(report(new Row("x", 130, -1, 28, 0.3, 0.4, 0.1, 1)).contains("protein is negative"));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        // A curator fixing a batch should see the whole list, not one per build.
        assertTrue(NutrientPlausibility.problems(new Row("x", 130, -1, 28, 0.3, 40, 40, -5)).size() >= 4);
    }
}
