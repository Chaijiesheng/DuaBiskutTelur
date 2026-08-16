package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient.NutrientsPer100g;
import com.duabiskuttelur.model.IdentifiedFood;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import com.duabiskuttelur.service.NutritionValidator.Rejection;
import com.duabiskuttelur.service.NutritionValidator.Rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        return new IdentifiedFood(name, "1 serving", 400, 320, 480, name,
                kcal, protein, carbs, fat, fiber, 3, 400, "protein", "steamed", 0.9,
                IdentifiedFood.KIND_MAIN);
    }

    private static NutrientsPer100g match(String description, double kcal, double protein,
                                          double carbs, double fat, double fiber) {
        return new NutrientsPer100g(description, kcal, protein, carbs, fat, fiber, 2, 500);
    }

    /**
     * As {@link #estimate}, but with the portion the model saw made explicit —
     * the per-serving rules multiply by it, so it is the variable under test
     * rather than a fixed 400g detail.
     */
    private static IdentifiedFood servingOf(String name, double grams, double kcal, double protein,
                                            double carbs, double fat, double fiber) {
        return new IdentifiedFood(name, "1 serving", grams, grams * 0.8, grams * 1.2, name,
                kcal, protein, carbs, fat, fiber, 3, 400, "protein", "steamed", 0.9,
                IdentifiedFood.KIND_MAIN);
    }

    @Test
    void acceptsAMatchThatAgreesWithTheModel() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Rice, white, cooked", 130, 2.7, 28, 0.3, 0.4),
                estimate("Steamed rice", 140, 3, 30, 0.5, 0.5));

        assertTrue(reason.isEmpty(), "a sensible match should be used: " + reason.map(Rejection::message).orElse(""));
    }

    /**
     * "Herbal chicken soup + rice" matched a plain broth, returning 160 kcal for
     * a whole rice set — which then also read as an under-sized portion and sank
     * the dish to the bottom tier.
     *
     * <p>Two independent rules catch this: the dish is named as a rice dish and
     * the match has almost no carbohydrate, and separately the energies are 3.5x
     * apart. The starch rule is checked first, so that is the one reported —
     * pinned exactly now that the reason is an enum rather than a sentence,
     * because which rule fires is a real property. It decides what the metric
     * says, and the metric is how the rejection rate gets diagnosed.
     */
    @Test
    void rejectsAMatchThatIsAFractionOfTheModelsEnergy() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Soup, chicken broth", 40, 2.6, 6.6, 0.3, 0.3),
                estimate("Herbal chicken soup + rice", 140, 9, 20, 3, 1));

        assertTrue(reason.isPresent(), "a broth is not a rice set");
        assertEquals(Rule.STARCH_WITHOUT_CARBS, reason.get().rule(), reason.get().message());
    }

    /** The energy gap alone still rejects, for a dish with no starch in its name. */
    @Test
    void rejectsAMatchThatIsAFractionOfTheModelsEnergyWithoutTheStarchRule() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Soup, chicken broth", 40, 2.6, 6.6, 0.3, 0.3),
                estimate("Herbal chicken pot", 140, 9, 20, 3, 1));

        assertEquals(Rule.CALORIE_DISAGREEMENT, reason.get().rule(), reason.get().message());
    }

    /**
     * "Nasi lemak" resolved to plain chicken: 120g protein per serving, no
     * carbohydrate at all.
     *
     * <p>Also pins rule <em>order</em>, which became load-bearing once the
     * sugar-versus-carbohydrate check arrived. This row has no carbohydrate and
     * a little sugar, so that check would also fire — and reporting it would
     * describe a wrong-food match as a sugar-data problem, sending anyone
     * reading the metric after the wrong thing entirely. The sugar rule
     * therefore requires carbohydrate to be present and runs after this one.
     */
    @Test
    void rejectsAStarchDishThatCameBackWithNoStarch() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Chicken, broilers, meat only, cooked", 195, 29.8, 0, 7.8, 0),
                estimate("Nasi Lemak with Fried Chicken", 210, 9, 24, 9, 1.2));

        assertEquals(Rule.STARCH_WITHOUT_CARBS, reason.get().rule(), reason.get().message());
    }

    /**
     * "Wantan mee" matched dry noodles by weight, giving 240g of carbohydrate a
     * serving.
     *
     * <p>Worth knowing which rule actually saves this one: <em>not</em> the
     * carbohydrate-density rule its name suggests. Dry egg noodle is 71.3g/100g,
     * comfortably under that rule's 90g threshold — what catches it is the row
     * being 2.7x the model's energy estimate. Naming the rule in the assertion
     * is what made that visible; while it only asserted "rejected", this read as
     * coverage of a rule it never exercised.
     */
    @Test
    void rejectsCarbohydrateThatOnlyMakesSenseForADryIngredient() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Noodles, egg, dry", 384, 14.2, 71.3, 4.4, 3.3),
                estimate("Wantan Mee (Dry)", 140, 7, 20, 3, 1));

        assertEquals(Rule.CALORIE_DISAGREEMENT, reason.get().rule(), reason.get().message());
    }

    /**
     * And the carbohydrate-density rule itself, which nothing covered until the
     * assertion above stopped being a guess. Flour is ~95g/100g; no served dish
     * is, so this is the shape of a match against a raw ingredient whose energy
     * happens to agree with the model's.
     */
    @Test
    void rejectsCarbohydrateDensityOnlyADryIngredientReaches() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Flour, wheat, all-purpose", 364, 10.3, 95.4, 1.0, 2.7),
                estimate("Roti Canai", 300, 6, 38, 13, 1.5));

        assertEquals(Rule.CARB_DENSITY, reason.get().rule(), reason.get().message());
    }

    @Test
    void rejectsImplausibleProteinDensity() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Protein isolate", 380, 78, 5, 4, 0),
                estimate("Bak Kut Teh", 90, 8, 2, 5, 0.5));

        assertEquals(Rule.PROTEIN_DENSITY, reason.get().rule(), reason.get().message());
    }

    /** Real roti canai has ~2g of fibre; the match claimed a fifth of its carbohydrate was fibre. */
    @Test
    void rejectsFibreOutOfProportionToCarbohydrate() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Bread, wholemeal, high fibre", 250, 9, 40, 4, 12),
                estimate("Roti Canai", 300, 6, 38, 13, 1.5));

        assertEquals(Rule.FIBER_VS_CARBS, reason.get().rule(), reason.get().message());
    }

    /**
     * A row carrying carbohydrate but exactly zero for both fibre and sugar is
     * a partly-populated database entry rather than a measurement, and it hands
     * the dish an undeserved clean sheet on the sugar penalty.
     */
    @Test
    void rejectsAPartiallyPopulatedRow() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                new NutrientsPer100g("Half-filled row", 200, 8, 30, 5, 0, 0, 400),
                estimate("Nasi Kerabu", 190, 8, 24, 7, 1.6));

        assertEquals(Rule.INCOMPLETE_ROW, reason.get().rule(), reason.get().message());
    }

    /** "Black pepper chicken chop rice" came back with bran-like fibre. */
    @Test
    void rejectsAMatchWithFibreTheDishCouldNotHave() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Cereal, bran, high fibre", 251, 10.4, 64, 3.3, 25.3),
                estimate("Black pepper chicken chop rice", 200, 9, 25, 7, 1.2));

        assertEquals(Rule.FIBER_VS_CARBS, reason.get().rule(), reason.get().message());
    }

    /**
     * The real case this rule was added for. "Chicken satay" resolved to a row
     * carrying 3345mg of sodium per 100g and it was used, because nothing looked
     * at sodium at any scale. That is soy sauce, not satay — six times the
     * saltiest row in the curated table.
     */
    @Test
    void rejectsSodiumOnlyASauceReaches() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                new NutrientsPer100g("Sauce, soy, shoyu", 60, 8, 5, 0.1, 0.8, 1.5, 3345),
                estimate("Chicken Satay", 200, 15, 8, 12, 1));

        assertEquals(Rule.SODIUM_DENSITY, reason.get().rule(), reason.get().message());
    }

    /**
     * Sugar is a component of carbohydrate, so more of the part than the whole
     * means one of the two came from a different food. Arithmetic, not a
     * threshold — and sugar drives its own penalty in the scorer, so a bogus
     * figure moves the grade.
     */
    @Test
    void rejectsMoreSugarThanCarbohydrate() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                new NutrientsPer100g("Confection, mixed row", 300, 2, 20, 5, 1, 45, 100),
                estimate("Cendol", 130, 1.5, 22, 4, 0.8));

        assertEquals(Rule.SUGAR_EXCEEDS_CARBS, reason.get().rule(), reason.get().message());
    }

    /**
     * The gap the per-serving family exists to close: every per-100g figure here
     * is arguable, and the plate it describes is not. Nothing in the density
     * checks can see this, because the fault only appears once the portion is
     * applied.
     */
    @Test
    void rejectsAServingTotalNoSingleDishReaches() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Pastry, mixed, rich", 450, 8, 45, 26, 2),
                servingOf("Karipap platter", 800, 400, 8, 40, 20, 2));

        assertEquals(Rule.CALORIES_PER_SERVING, reason.get().rule(), reason.get().message());
    }

    @Test
    void rejectsSodiumPerServingBeyondAnyDish() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                new NutrientsPer100g("Broth, concentrated", 120, 6, 10, 5, 1, 2, 1400),
                servingOf("Bak Kut Teh", 600, 100, 7, 2, 5, 0.4));

        assertEquals(Rule.SODIUM_PER_SERVING, reason.get().rule(), reason.get().message());
    }

    /**
     * A large plate is a real thing. 700g of mixed rice at a sane density is
     * roughly 1100 kcal, and rejecting it would be the validator second-guessing
     * plausible data rather than catching nonsense — the line the whole file is
     * written to hold.
     */
    @Test
    void keepsAGenuinelyLargePlate() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Rice, mixed dish, cooked", 156, 7.5, 20, 5, 1.3),
                servingOf("Nasi Campur", 700, 160, 8, 21, 5, 1.4));

        assertTrue(reason.isEmpty(), "a big plate is not a broken row: "
                + reason.map(Rejection::message).orElse(""));
    }

    /**
     * With no portion there is nothing to multiply by, so the per-serving family
     * sits out rather than assuming a number and then judging the row against
     * its own assumption.
     */
    @Test
    void skipsThePerServingChecksWhenNoPortionWasEstimated() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                new NutrientsPer100g("Broth, concentrated", 120, 6, 10, 5, 1, 2, 1400),
                servingOf("Mystery soup", 0, 100, 7, 2, 5, 0.4));

        assertTrue(reason.isEmpty(), "nothing to scale by: "
                + reason.map(Rejection::message).orElse(""));
    }

    /**
     * Fibre above anything that exists — wheat bran, the most fibrous thing
     * there is, is ~43g/100g. Caught before the proportional check, so this is
     * the only input that reaches it.
     */
    @Test
    void rejectsFibreNoFoodContains() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Fibre supplement, powdered", 200, 5, 60, 2, 55),
                estimate("Mee Goreng", 190, 7, 25, 6, 1.4));

        assertEquals(Rule.IMPOSSIBLE_FIBER, reason.get().rule(), reason.get().message());
    }

    /**
     * Fibre in proportion to its own carbohydrate, so the bran rule passes it,
     * but far above what the model saw in this dish. The last rule in the chain,
     * and the only one that catches a row like this.
     */
    @Test
    void rejectsFibreTheModelSaysTheDishCannotHave() {
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Cereal, wholegrain, high fibre", 300, 8, 80, 2, 18),
                estimate("Chicken chop rice", 200, 9, 25, 7, 1));

        assertEquals(Rule.FIBER_DISAGREEMENT, reason.get().rule(), reason.get().message());
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
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Mismatched row", 90, 20, 60, 15, 2),
                estimate("Chicken rice", 120, 10, 18, 3, 1));

        assertEquals(Rule.MACROS_UNRECONCILED, reason.get().rule(), reason.get().message());
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
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Rice, white, cooked", 130, 2.7, 28, 0.3, 0.4),
                estimate("Mystery dish", 0, 0, 0, 0, 0));

        assertTrue(reason.isEmpty(), "nothing to disagree with: " + reason.map(Rejection::message).orElse(""));
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
        Optional<Rejection> reason = NutritionValidator.rejectionReason(
                match("Chicken curry", 180, 14, 8, 11, 1.5),
                estimate("Curry chicken rice", 150, 10, 18, 5, 2));

        assertFalse(reason.isPresent(), "a 1.2x gap is normal estimation spread: " + reason.map(Rejection::message).orElse(""));
    }
}
