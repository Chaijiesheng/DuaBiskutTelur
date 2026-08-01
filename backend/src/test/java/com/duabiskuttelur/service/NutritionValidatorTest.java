package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient.NutrientsPer100g;
import com.duabiskuttelur.model.IdentifiedFood;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two named cases here are real matches taken from a production scan of a
 * kopitiam menu, where USDA's fuzzy search answered about a different food and
 * the wrong numbers went on to drive the dish's score and its tier.
 */
class NutritionValidatorTest {

    /** Per-100g estimate from the vision model, which is what a rejected match falls back to. */
    private static IdentifiedFood estimate(String name, double kcal, double protein, double carbs,
                                           double fat, double fiber) {
        return new IdentifiedFood(name, "1 serving", 400, name,
                kcal, protein, carbs, fat, fiber, 3, 400, "protein", false, 0.9,
                IdentifiedFood.KIND_MAIN);
    }

    private static NutrientsPer100g match(String description, double kcal, double protein,
                                          double carbs, double fat, double fiber) {
        return new NutrientsPer100g(description, kcal, protein, carbs, fat, fiber, 2, 500);
    }

    @Test
    void acceptsAMatchThatAgreesWithTheModel() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Rice, white, cooked", 130, 2.7, 28, 0.3, 0.4),
                estimate("Steamed rice", 140, 3, 30, 0.5, 0.5));

        assertTrue(reason.isEmpty(), "a sensible match should be used: " + reason.orElse(""));
    }

    /**
     * "Herbal chicken soup + rice" matched a plain broth, returning 160 kcal for
     * a whole rice set — which then also read as an under-sized portion and sank
     * the dish to the bottom tier.
     *
     * <p>Two independent rules now catch this: the dish is named as a rice dish
     * and the match has almost no carbohydrate, and separately the energies are
     * 3.5x apart. The starch rule is checked first, so that's the reason
     * reported — either is a correct rejection, so the assertion accepts both
     * rather than pinning the message.
     */
    @Test
    void rejectsAMatchThatIsAFractionOfTheModelsEnergy() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Soup, chicken broth", 40, 2.6, 6.6, 0.3, 0.3),
                estimate("Herbal chicken soup + rice", 140, 9, 20, 3, 1));

        assertTrue(reason.isPresent(), "a broth is not a rice set");
        assertTrue(reason.get().contains("apart") || reason.get().contains("carbohydrate"), reason.get());
    }

    /** The energy gap alone still rejects, for a dish with no starch in its name. */
    @Test
    void rejectsAMatchThatIsAFractionOfTheModelsEnergyWithoutTheStarchRule() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Soup, chicken broth", 40, 2.6, 6.6, 0.3, 0.3),
                estimate("Herbal chicken pot", 140, 9, 20, 3, 1));

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("apart"), reason.get());
    }

    /** "Nasi lemak" resolved to plain chicken: 120g protein per serving, no carbohydrate at all. */
    @Test
    void rejectsAStarchDishThatCameBackWithNoStarch() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Chicken, broilers, meat only, cooked", 195, 29.8, 0, 7.8, 0),
                estimate("Nasi Lemak with Fried Chicken", 210, 9, 24, 9, 1.2));

        assertTrue(reason.isPresent(), "a rice dish has to contain rice");
        assertTrue(reason.get().contains("carbohydrate"), reason.get());
    }

    /** "Wantan mee" matched dry noodles by weight, giving 240g of carbohydrate a serving. */
    @Test
    void rejectsCarbohydrateThatOnlyMakesSenseForADryIngredient() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Noodles, egg, dry", 384, 14.2, 71.3, 4.4, 3.3),
                estimate("Wantan Mee (Dry)", 140, 7, 20, 3, 1));

        assertTrue(reason.isPresent(), "71g carbs/100g is uncooked noodle, not a served plate");
    }

    @Test
    void rejectsImplausibleProteinDensity() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Protein isolate", 380, 78, 5, 4, 0),
                estimate("Bak Kut Teh", 90, 8, 2, 5, 0.5));

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("protein"), reason.get());
    }

    /** Real roti canai has ~2g of fibre; the match claimed a fifth of its carbohydrate was fibre. */
    @Test
    void rejectsFibreOutOfProportionToCarbohydrate() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Bread, wholemeal, high fibre", 250, 9, 40, 4, 12),
                estimate("Roti Canai", 300, 6, 38, 13, 1.5));

        assertTrue(reason.isPresent(), "12g fibre against 40g carbs is bran, not flatbread");
        assertTrue(reason.get().contains("fibre"), reason.get());
    }

    /**
     * A row carrying carbohydrate but exactly zero for both fibre and sugar is
     * a partly-populated database entry rather than a measurement, and it hands
     * the dish an undeserved clean sheet on the sugar penalty.
     */
    @Test
    void rejectsAPartiallyPopulatedRow() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                new NutrientsPer100g("Half-filled row", 200, 8, 30, 5, 0, 0, 400),
                estimate("Nasi Kerabu", 190, 8, 24, 7, 1.6));

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("incomplete"), reason.get());
    }

    /** "Black pepper chicken chop rice" came back with bran-like fibre. */
    @Test
    void rejectsAMatchWithFibreTheDishCouldNotHave() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Cereal, bran, high fibre", 251, 10.4, 64, 3.3, 25.3),
                estimate("Black pepper chicken chop rice", 200, 9, 25, 7, 1.2));

        assertTrue(reason.isPresent(), "25g of fibre per 100g isn't a chicken chop");
        assertTrue(reason.get().contains("fibre"), reason.get());
    }

    @Test
    void rejectsImpossibleEnergyDensity() {
        assertTrue(NutritionValidator.rejectionReason(
                match("Something broken", 1500, 10, 10, 10, 1),
                estimate("Fried rice", 200, 8, 30, 6, 1)).isPresent(), "nothing is 1500 kcal/100g");
        assertTrue(NutritionValidator.rejectionReason(
                match("Water, tap", 1, 0, 0, 0, 0),
                estimate("Fried rice", 200, 8, 30, 6, 1)).isPresent(), "a dish isn't 1 kcal/100g");
    }

    @Test
    void rejectsMacrosThatCannotAddUpToTheStatedEnergy() {
        // 60g carbs alone is 240 kcal, so 90 kcal stated is arithmetically impossible.
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Mismatched row", 90, 20, 60, 15, 2),
                estimate("Chicken rice", 120, 10, 18, 3, 1));

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("reconcile"), reason.get());
    }

    @Test
    void rejectsNegativeNutrients() {
        assertTrue(NutritionValidator.rejectionReason(
                match("Corrupt row", 200, -5, 30, 6, 1),
                estimate("Fried rice", 200, 8, 30, 6, 1)).isPresent());
    }

    /**
     * With no estimate to compare against, a match can only be judged on whether
     * it's plausible in isolation — rejecting it would leave nothing to use.
     */
    @Test
    void keepsAPlausibleMatchWhenTheModelOfferedNoEstimate() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Rice, white, cooked", 130, 2.7, 28, 0.3, 0.4),
                estimate("Mystery dish", 0, 0, 0, 0, 0));

        assertTrue(reason.isEmpty(), "nothing to disagree with: " + reason.orElse(""));
    }

    @Test
    void stillRejectsNonsenseEvenWithNoEstimateToCompareAgainst() {
        assertTrue(NutritionValidator.rejectionReason(
                match("Broken row", 5000, 10, 10, 10, 1),
                estimate("Mystery dish", 0, 0, 0, 0, 0)).isPresent());
    }

    /** Loose on purpose: a merely-different match is still allowed through. */
    @Test
    void toleratesOrdinaryDisagreement() {
        Optional<String> reason = NutritionValidator.rejectionReason(
                match("Chicken curry", 180, 14, 8, 11, 1.5),
                estimate("Curry chicken rice", 150, 10, 18, 5, 2));

        assertFalse(reason.isPresent(), "a 1.2x gap is normal estimation spread: " + reason.orElse(""));
    }
}
