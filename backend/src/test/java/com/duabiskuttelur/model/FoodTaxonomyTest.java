package com.duabiskuttelur.model;

import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.service.ScoringService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vocabulary check behind AI3(a). The prompt always listed the eight food
 * groups; nothing verified the answer, and the variety score counts distinct
 * group strings — so an off-vocabulary label was not just untidy, it was points.
 */
class FoodTaxonomyTest {

    private static FoodItem food(String group, String cookingMethod) {
        return new FoodItem("Test dish", "1 plate", 300, 10, 40, 10, 2, 3, 400,
                0.9, "usda", group, FoodTaxonomy.isFried(cookingMethod), cookingMethod, 300, 300);
    }

    @Test
    void keepsTheEightGroupsAndRejectsEverythingElse() {
        assertEquals("grain", FoodTaxonomy.normalizeGroup("Grain"));
        assertEquals("vegetable", FoodTaxonomy.normalizeGroup("  VEGETABLE "));

        // Plausible-sounding answers a model actually reaches for.
        assertNull(FoodTaxonomy.normalizeGroup("noodles"));
        assertNull(FoodTaxonomy.normalizeGroup("carbohydrate"));
        assertNull(FoodTaxonomy.normalizeGroup("grains"));
    }

    @Test
    void treatsHyphenAndSpaceAsTheSameInCookingMethods() {
        assertEquals("deep-fried", FoodTaxonomy.normalizeMethod("Deep Fried"));
        assertEquals("stir-fried", FoodTaxonomy.normalizeMethod("stir-fried"));
        assertNull(FoodTaxonomy.normalizeMethod("air-fried"));
    }

    /**
     * The reason the whitelist is worth having. Three starches labelled three
     * different ways used to look like three food groups.
     */
    @Test
    void offVocabularyGroupsCanNoLongerInflateVariety() {
        ScoringService scoring = new ScoringService(new ScoringProperties());
        // Three starches under three invented labels, versus the same three
        // honestly called one group. Nothing else differs, so any score gap is
        // variety points — which is what the invented labels used to buy.
        List<FoodItem> mislabelled = List.of(
                food("noodles", "steamed"), food("rice", "steamed"), food("carbohydrate", "steamed"));
        List<FoodItem> honest = List.of(
                food("grain", "steamed"), food("grain", "steamed"), food("grain", "steamed"));
        List<FoodItem> genuinelyVaried = List.of(
                food("grain", "steamed"), food("protein", "grilled"), food("dairy", "raw"));

        int mislabelledScore = scoring.score(mislabelled, Totals.of(mislabelled), 2000).score();
        int honestScore = scoring.score(honest, Totals.of(honest), 2000).score();
        int variedScore = scoring.score(genuinelyVaried, Totals.of(genuinelyVaried), 2000).score();

        assertTrue(mislabelledScore <= honestScore,
                "invented group labels scored " + mislabelledScore + " against " + honestScore
                        + " for the same food honestly labelled — variety was being bought with words");
        assertTrue(variedScore > honestScore, "three real groups should still out-score one");
    }

    @Test
    void anUnknownGroupBecomesNullRatherThanADefault() {
        // Guessing a group would be worse than admitting there isn't one: null is
        // skipped by both the variety count and the vegetable bonus.
        assertNull(food("noodles", "steamed").foodGroup());
        assertNull(food(null, null).foodGroup());
    }
}
