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
        // 600 kcal at exactly 30% protein / 40% carbs / 30% fat, fiber >= 8g,
        // vegetables present, 3 food groups, no penalties.
        List<FoodItem> meal = List.of(
                food("Grilled chicken breast", "protein", false, 232, 40, 0, 8, 0, 0, 300),
                food("Brown rice", "grain", false, 238, 5, 50, 2, 4, 1, 10),
                food("Stir-fried vegetables", "vegetable", false, 130, 0, 10, 10, 5, 3, 200));

        ScoreResult result = scoreOf(meal);

        assertEquals("A+", result.grade());
        assertTrue(result.score() >= 90, "expected A+ band, got " + result.score());
        assertEquals(100, result.score());
    }

    @Test
    void balancedButSaltyLowFiberMealScoresA() {
        // Same perfect macro split, but sodium over 800mg and fiber under 8g:
        // loses the fiber bonus and takes the sodium penalty -> 87.
        List<FoodItem> meal = List.of(
                food("Chicken rice (roasted)", "protein", false, 232, 40, 0, 8, 0, 0, 500),
                food("Seasoned rice", "grain", false, 238, 5, 50, 2, 3, 1, 200),
                food("Blanched greens", "vegetable", false, 130, 0, 10, 10, 2, 3, 200));

        ScoreResult result = scoreOf(meal);

        assertEquals("A", result.grade());
        assertTrue(result.score() >= 80 && result.score() < 90, "expected A band, got " + result.score());
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

    @Test
    void multiItemSparseMealIsStillTreatedAsUnderEating() {
        // The snack exemption is scoped to a single logged item — a
        // genuinely sparse multi-item plate is still under-eating, not a
        // snack, so it should keep losing portion points as before.
        List<FoodItem> meal = List.of(
                food("Half a cracker", "grain", false, 40, 1, 8, 0.5, 0.5, 0, 30),
                food("Sliver of cheese", "protein", false, 40, 3, 0, 3, 0, 0, 100));

        ScoreResult result = scoring.score(meal, Totals.of(meal), 2000);

        assertTrue(result.portionPoints() < 20.0, "sparse multi-item meal should still lose portion points");
    }
}
