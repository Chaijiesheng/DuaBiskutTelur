package com.duabiskuttelur.service;

import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.service.ScoringService.ScoreResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Five sample meals covering every grade band (A+, A, B, C, D) plus edge cases,
 * exercising the deterministic scoring engine with default thresholds.
 */
class ScoringServiceTest {

    private ScoringService scoring;

    @BeforeEach
    void setUp() {
        scoring = new ScoringService(new ScoringProperties());
    }

    private static FoodItem food(String name, String group, boolean fried,
                                 double cal, double p, double c, double f,
                                 double fiber, double sugar, double sodium) {
        return new FoodItem(name, "1 serving", cal, p, c, f, fiber, sugar, sodium, 0.9, "usda", group, fried);
    }

    private ScoreResult scoreOf(List<FoodItem> foods) {
        return scoring.score(foods, Totals.of(foods));
    }

    @Test
    void perfectlyBalancedMealScoresAPlus() {
        // 600 kcal at exactly the maintenance target of 25% protein / 45% carbs
        // / 30% fat, fiber >= 8g, vegetables present, 3 food groups, no
        // penalties. The target used to be a flat 30/40/30 for everyone, which
        // matched none of the three splits the app displays -- see MacroTargets.
        List<FoodItem> meal = List.of(
                food("Grilled chicken breast", "protein", false, 150, 37.5, 0, 0, 0, 0, 300),
                food("Brown rice", "grain", false, 270, 0, 67.5, 0, 5, 1, 10),
                food("Stir-fried vegetables", "vegetable", false, 180, 0, 0, 20, 4, 2, 200));

        ScoreResult result = scoreOf(meal);

        assertEquals("A+", result.grade());
        assertEquals(100, result.score());
    }

    /**
     * The inconsistency N3 named: MacroDonut showed a maintenance user "protein
     * target 25%" while the engine graded everyone against 30%, both visible on
     * one screen. The same plate should now land differently depending on what
     * the user said they were aiming for.
     */
    @Test
    void theSameMealIsGradedAgainstTheSplitTheUserIsAimingFor() {
        // Protein-heavy: 40% protein / 30% carbs / 30% fat by calories.
        List<FoodItem> proteinHeavy = List.of(
                food("Grilled chicken", "protein", false, 240, 60, 0, 0, 0, 0, 300),
                food("Rice", "grain", false, 180, 0, 45, 0, 5, 1, 10),
                food("Greens", "vegetable", false, 180, 0, 0, 20, 4, 2, 200));
        Totals totals = Totals.of(proteinHeavy);

        double forLoss = scoring.score(proteinHeavy, totals, 2000, "weight_loss").balancePoints();
        double forGain = scoring.score(proteinHeavy, totals, 2000, "muscle_gain").balancePoints();

        assertTrue(forLoss > forGain,
                "a protein-heavy plate should suit a 35%-protein weight-loss target better than a "
                        + "30%/45% muscle-gain one, got " + forLoss + " vs " + forGain);
    }

    @Test
    void anAbsentGoalIsGradedAgainstMaintenanceRatherThanFailing() {
        // Visitors and users who never set a goal are the common case, not an
        // error -- and Map.of rejects a null key outright.
        List<FoodItem> meal = List.of(food("Rice", "grain", false, 300, 6, 60, 3, 2, 1, 100));

        assertEquals(scoring.score(meal, Totals.of(meal), 2000, "maintenance").score(),
                scoring.score(meal, Totals.of(meal), 2000, null).score());
    }

    /**
     * 900mg of sodium is 100mg over the threshold, and used to cost the entire
     * 8-point penalty -- so this meal and an identical one at 799mg differed by
     * 13 points once the fibre cliff was included. The penalty now ramps, so a
     * marginal overshoot costs a marginal amount.
     */
    @Test
    void aMarginallySaltyMealIsNoLongerPunishedAsIfItWereVerySalty() {
        List<FoodItem> meal = List.of(
                food("Chicken rice (roasted)", "protein", false, 232, 40, 0, 8, 0, 0, 500),
                food("Seasoned rice", "grain", false, 238, 5, 50, 2, 3, 1, 200),
                food("Blanched greens", "vegetable", false, 130, 0, 10, 10, 2, 3, 200));

        ScoreResult result = scoreOf(meal);

        assertEquals(900, Totals.of(meal).sodium(), 0.01);
        assertTrue(result.score() >= 90, "a 100mg overshoot should barely move the grade, got " + result.score());
    }

    /** Softened, not removed: a genuinely salty meal still loses most of the penalty. */
    @Test
    void aGenuinelySaltyMealStillLosesTheFullPenalty() {
        List<FoodItem> salty = List.of(
                food("Chicken rice (roasted)", "protein", false, 232, 40, 0, 8, 0, 0, 1100),
                food("Seasoned rice", "grain", false, 238, 5, 50, 2, 3, 1, 400),
                food("Blanched greens", "vegetable", false, 130, 0, 10, 10, 2, 3, 300));

        double saltyQuality = scoreOf(salty).qualityPoints();
        List<FoodItem> mild = List.of(
                food("Chicken rice (roasted)", "protein", false, 232, 40, 0, 8, 0, 0, 200),
                food("Seasoned rice", "grain", false, 238, 5, 50, 2, 3, 1, 100),
                food("Blanched greens", "vegetable", false, 130, 0, 10, 10, 2, 3, 100));

        // 1800mg is past the "full at" mark, so the whole 8 points are gone.
        assertEquals(8, scoreOf(mild).qualityPoints() - saltyQuality, 0.01);
    }

    /**
     * The step function N3 named. Two meals a hair apart on either side of a
     * threshold must not differ by a whole bonus or penalty.
     */
    @Test
    void thereIsNoCliffAtAnyQualityThreshold() {
        for (double[] pair : new double[][]{
                // fibre either side of 8g
                {7.9, 8.1, 0, 0},
                // sugar either side of 25g
                {4, 4, 24.9, 25.1},
        }) {
            List<FoodItem> below = List.of(
                    food("A", "grain", false, 400, 20, 50, 12, pair[0], pair[2], 700));
            List<FoodItem> above = List.of(
                    food("A", "grain", false, 400, 20, 50, 12, pair[1], pair[3], 700));

            double gap = Math.abs(scoreOf(above).qualityPoints() - scoreOf(below).qualityPoints());
            assertTrue(gap < 0.5, "a 0.2 difference produced a " + gap + "-point swing");
        }
    }

    @Test
    void slightlyCarbHeavyFriedMealScoresB() {
        // 22/48/30 macro split, one fried item, only two food groups, no vegetables.
        List<FoodItem> meal = List.of(
                food("Fried chicken chop", "protein", true, 350, 30, 14, 18.3, 1, 2, 400),
                food("White rice, large", "grain", false, 350, 8.5, 70, 5, 1.5, 3, 300));

        ScoreResult result = scoreOf(meal);

        assertEquals("B", result.grade());
        assertTrue(result.score() >= 70 && result.score() < 80, "expected B band, got " + result.score());
    }

    @Test
    void nasiLemakWithFriedChickenScoresC() {
        // Fat-heavy (49% of calories), sodium 1442mg, fried item -> mid-band C
        // despite good portion size and variety.
        List<FoodItem> meal = List.of(
                food("Nasi lemak (coconut rice)", "grain", false, 398, 7.2, 52.1, 18.3, 1.9, 2.1, 520),
                food("Ayam goreng (fried chicken)", "protein", true, 290, 21.5, 8.4, 19.2, 0.4, 0.5, 480),
                food("Sambal + cucumber", "vegetable", false, 75, 1.4, 9.8, 3.6, 1.8, 6.2, 380),
                food("Telur rebus (boiled egg)", "protein", false, 68, 5.6, 0.6, 4.7, 0, 0.3, 62));

        ScoreResult result = scoreOf(meal);

        assertEquals("C", result.grade());
        assertTrue(result.score() >= 55 && result.score() < 70, "expected C band, got " + result.score());
    }

    @Test
    void oversizedFriedSugaryMealScoresD() {
        // 1600 kcal (80% of the daily budget), 62% of calories from fat,
        // sugar and sodium both over threshold, fried, little variety.
        List<FoodItem> meal = List.of(
                food("Fried chicken bucket", "protein", true, 1300, 60, 40, 100, 2, 5, 2400),
                food("Large sweet iced drink", "beverage", false, 300, 0, 40, 0, 0, 40, 80));

        ScoreResult result = scoreOf(meal);

        assertEquals("D", result.grade());
        assertTrue(result.score() < 55, "expected D band, got " + result.score());
    }

    @Test
    void gradeBandEdgesMapCorrectly() {
        assertEquals("A+", scoring.gradeFor(90));
        assertEquals("A", scoring.gradeFor(89));
        assertEquals("A", scoring.gradeFor(80));
        assertEquals("B", scoring.gradeFor(79));
        assertEquals("B", scoring.gradeFor(70));
        assertEquals("C", scoring.gradeFor(69));
        assertEquals("C", scoring.gradeFor(55));
        assertEquals("D", scoring.gradeFor(54));
    }

    @Test
    void tinySnackLosesPortionPointsButNeverGoesBelowOne() {
        // 68 kcal boiled egg alone: "not a real meal"
        List<FoodItem> snack = List.of(
                food("Telur rebus (boiled egg)", "protein", false, 68, 5.6, 0.6, 4.7, 0, 0.3, 62));

        ScoreResult result = scoreOf(snack);

        assertTrue(result.score() >= 1 && result.score() <= 100);
        Totals totals = Totals.of(snack);
        assertTrue(scoring.portionPoints(totals, 2000) < 10,
                "tiny meal should lose most portion points, got " + scoring.portionPoints(totals, 2000));
    }

    @Test
    void zeroMacroItemGetsFullBalancePointsInsteadOfZero() {
        // Plain water: no protein/carbs/fat at all. There's no macro ratio to
        // be imbalanced about, so this shouldn't be scored as if it violated
        // the ideal split (previously returned 0/40 here, dragging a barcode
        // scan of water down to a D grade).
        List<FoodItem> water = List.of(
                food("Mineral water", "beverage", false, 0, 0, 0, 0, 0, 0, 0));
        Totals totals = Totals.of(water);

        assertEquals(40.0, scoring.balancePoints(totals), 0.001);
    }

    @Test
    void portionScoringUsesTheCallersBudgetNotTheConfigDefault() {
        // A 1200 kcal meal is half of a 2400 kcal (muscle-gain) budget -> full
        // portion points, but the same meal is the entire budget for someone
        // at 1200 kcal (weight-loss) -> should lose portion points instead.
        List<FoodItem> meal = List.of(
                food("Big rice bowl", "grain", false, 1200, 40, 150, 30, 5, 10, 500));
        Totals totals = Totals.of(meal);

        ScoreResult generousBudget = scoring.score(meal, totals, 2400);
        ScoreResult tightBudget = scoring.score(meal, totals, 1200);

        assertTrue(generousBudget.portionPoints() > tightBudget.portionPoints(),
                "the same meal should score fewer portion points against a tighter personal budget");
        assertEquals(20.0, generousBudget.portionPoints(), 0.001, "50% of a 2400 kcal budget is within the sane max");
    }

    @Test
    void varietyScalesWithDistinctFoodGroups() {
        List<FoodItem> oneGroup = List.of(
                food("Chicken", "protein", false, 200, 30, 0, 8, 0, 0, 100),
                food("More chicken", "protein", false, 200, 30, 0, 8, 0, 0, 100));
        List<FoodItem> threeGroups = List.of(
                food("Chicken", "protein", false, 200, 30, 0, 8, 0, 0, 100),
                food("Rice", "grain", false, 200, 4, 44, 1, 1, 0, 10),
                food("Kangkung", "vegetable", false, 80, 2, 8, 4, 3, 2, 150));

        assertTrue(scoring.varietyPoints(oneGroup) < scoring.varietyPoints(threeGroups));
        assertEquals(10.0, scoring.varietyPoints(threeGroups), 0.001);
    }

    @Test
    void singleItemSnackUnderMinMealCaloriesIsExemptFromPortionAndVarietyPenalties() {
        // A single 95 kcal apple isn't a failed meal — it's a snack. Barcode
        // scans of packaged snacks hit this constantly: without the
        // exemption, a lone low-calorie item lost most of its portion points
        // ("under-eating") and most of its variety points (only one food
        // group), dragging an otherwise-fine snack down toward a D grade.
        List<FoodItem> apple = List.of(
                food("Apple", "fruit", false, 95, 0.5, 25, 0.3, 4.4, 19, 2));

        ScoreResult result = scoring.score(apple, Totals.of(apple), 2000);

        assertEquals(20.0, result.portionPoints(), 0.001);
        assertEquals(10.0, result.varietyPoints(), 0.001);
    }

    /**
     * The snack exemption used to require a single logged item, so a coffee
     * <em>and</em> a biscuit — two items, ~180 kcal — was graded as a failed
     * meal: portion points scaled toward zero for under-eating, and variety was
     * judged on 2 of 3 food groups. The item count never meant anything here;
     * total calories is the whole question.
     */
    @Test
    void aTwoItemSnackIsNoLongerGradedAsAFailedMeal() {
        List<FoodItem> snack = List.of(
                food("Kopi O", "beverage", false, 40, 0, 10, 0, 0, 9, 15),
                food("Biscuit", "grain", false, 140, 2, 20, 6, 0.5, 5, 90));

        ScoreResult result = scoring.score(snack, Totals.of(snack), 2000);

        assertEquals(20.0, result.portionPoints(), 0.001,
                "180 kcal is a snack whether it arrives as one item or two");
        assertEquals(10.0, result.varietyPoints(), 0.001,
                "a snack is not expected to cover three food groups");
    }

    /**
     * The exemption is about size, so a real meal is still judged as one however
     * many items it arrives in — and an oversized one still loses portion points,
     * which is the branch that remains live.
     */
    @Test
    void aFullSizedMealIsStillJudgedAsAMealHoweverManyItemsItHas() {
        List<FoodItem> meal = List.of(
                food("Rice", "grain", false, 300, 6, 65, 1, 2, 1, 100),
                food("Chicken", "protein", false, 250, 30, 0, 14, 0, 0, 400));
        assertEquals(20.0, scoring.score(meal, Totals.of(meal), 2000).portionPoints(), 0.001);

        List<FoodItem> huge = List.of(
                food("Rice, large", "grain", false, 900, 18, 190, 6, 4, 3, 300),
                food("Fried chicken", "protein", true, 800, 50, 20, 55, 1, 2, 900));
        assertTrue(scoring.score(huge, Totals.of(huge), 2000).portionPoints() < 20.0,
                "1700 kcal against a 2000 kcal day should still cost portion points");
    }

    /**
     * A consequence of keying the exemption on calories alone that is worth
     * pinning: the under-eating ramp is now unreachable through score(), because
     * anything below the threshold is a snack. Logging 200 kcal is a snack, and
     * nothing in the log distinguishes that from an intended small meal — so
     * exempting it is the honest reading, not a gap.
     */
    @Test
    void nothingBelowTheSnackThresholdIsPenalizedForUnderEating() {
        for (double calories : new double[]{40, 120, 240}) {
            List<FoodItem> small = List.of(
                    food("A", "grain", false, calories / 2, 1, 8, 0.5, 0.5, 0, 30),
                    food("B", "protein", false, calories / 2, 3, 0, 3, 0, 0, 100));
            assertEquals(20.0, scoring.score(small, Totals.of(small), 2000).portionPoints(), 0.001,
                    calories + " kcal should be exempt, not under-eating");
        }
    }

    // ---- menu-dish profile ----

    /**
     * The meal scorer exempts a lone sub-250kcal item from portion and variety
     * judgement so a packaged snack isn't graded as an incomplete meal. Every
     * menu dish is a lone item, so that exemption applied to all of them and
     * handed 30 free points to whatever happened to be small — which is how a
     * spoon of sambal came to outrank every real dish on a menu.
     */
    @Test
    void menuProfileDoesNotHandFreePointsToSmallItems() {
        FoodItem condiment = food("Sambal", "vegetable", false, 75, 1.4, 9.8, 3.6, 1.8, 6.2, 380);

        ScoreResult asMeal = scoring.score(List.of(condiment), Totals.of(List.of(condiment)), 2000);
        ScoreResult asMenuDish = scoring.scoreMenuDish(condiment, 2000);

        assertEquals(20.0, asMeal.portionPoints(), 0.01, "meal scoring exempts it entirely");
        assertTrue(asMenuDish.portionPoints() < 10.0,
                "a 75kcal item is a token portion for a dish, got " + asMenuDish.portionPoints());
        assertTrue(asMenuDish.score() < asMeal.score(),
                "the condiment should stop outscoring itself once the exemption is gone");
    }

    @Test
    void menuProfileDropsVarietyEntirely() {
        FoodItem dish = food("Chicken rice", "grain", false, 600, 30, 85, 12, 2, 2, 900);

        assertEquals(0.0, scoring.scoreMenuDish(dish, 2000).varietyPoints(), 0.01,
                "one dish is always one food group, so variety can only add a constant");
    }

    /**
     * Portion used to be flat across the whole 250-1000kcal band, so a light
     * porridge and a heavy fried rice scored identically on it.
     */
    @Test
    void menuPortionSlidesInsteadOfPlateauing() {
        FoodItem light = food("Porridge", "grain", false, 350, 18, 50, 7, 1.5, 1, 400);
        FoodItem heavy = food("Big fried rice", "grain", false, 950, 28, 120, 34, 3, 5, 600);
        FoodItem huge = food("Family platter", "grain", false, 1600, 50, 180, 70, 4, 8, 700);

        double lightPortion = scoring.scoreMenuDish(light, 2000).portionPoints();
        double heavyPortion = scoring.scoreMenuDish(heavy, 2000).portionPoints();
        double hugePortion = scoring.scoreMenuDish(huge, 2000).portionPoints();

        assertEquals(25.0, lightPortion, 0.01, "a sensible dish keeps full marks");
        assertTrue(heavyPortion > 0 && heavyPortion < lightPortion,
                "a much bigger dish should lose some, not all, portion marks: " + heavyPortion);
        assertEquals(0.0, hugePortion, 0.01, "most of a day's calories in one dish scores nothing");
    }

    /**
     * Fixed penalty cliffs collapsed a whole menu onto three quality values,
     * leaving macro ratio to decide the ordering.
     */
    @Test
    void menuQualityPenaltiesAreGraduatedNotCliffs() {
        FoodItem clean = food("Steamed fish", "protein", false, 400, 40, 5, 12, 2, 1, 700);
        FoodItem slightlySalty = food("Steamed fish", "protein", false, 400, 40, 5, 12, 2, 1, 900);
        FoodItem verySalty = food("Steamed fish", "protein", false, 400, 40, 5, 12, 2, 1, 1600);

        double cleanQ = scoring.scoreMenuDish(clean, 2000).qualityPoints();
        double slightQ = scoring.scoreMenuDish(slightlySalty, 2000).qualityPoints();
        double veryQ = scoring.scoreMenuDish(verySalty, 2000).qualityPoints();

        assertTrue(cleanQ > slightQ && slightQ > veryQ,
                "sodium should bite progressively, got " + cleanQ + " / " + slightQ + " / " + veryQ);
        assertTrue(cleanQ - slightQ < veryQ + 0.01 - veryQ + (cleanQ - veryQ) / 2,
                "just over the threshold should cost far less than double it");
    }

    /** Meal grading must be untouched by any of the above. */
    @Test
    void mealScoringIsUnchangedByTheMenuProfile() {
        List<FoodItem> meal = List.of(
                food("Rice", "grain", false, 300, 6, 65, 1, 1, 0, 5),
                food("Grilled chicken", "protein", false, 250, 35, 0, 11, 0, 0, 400),
                food("Vegetables", "vegetable", false, 90, 4, 12, 3, 5, 4, 200));

        ScoreResult result = scoring.score(meal, Totals.of(meal), 2000);

        assertEquals(20.0, result.portionPoints(), 0.01);
        assertEquals(10.0, result.varietyPoints(), 0.01, "three food groups still earn full variety");
        assertTrue(result.qualityPoints() <= 30.0, "meal quality still uses the 30-point scale");
        assertTrue(result.balancePoints() <= 40.0, "meal balance still uses the 40-point scale");
    }
}
