package com.duabiskuttelur.service;

import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.AnalysisResponse.ScoreBreakdown;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.Totals;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic scoring engine. No model calls here — pure arithmetic over the
 * resolved nutrition facts, with all thresholds tunable via ScoringProperties.
 */
@Service
public class ScoringService {

    public record ScoreResult(int score, String grade,
                              double balancePoints, double qualityPoints,
                              double portionPoints, double varietyPoints) {
    }

    private final ScoringProperties cfg;

    public ScoringService(ScoringProperties cfg) {
        this.cfg = cfg;
    }

    /** Uses the tunable default budget (config), for callers with no personalized target — mainly tests. */
    public ScoreResult score(List<FoodItem> foods, Totals totals) {
        return score(foods, totals, (int) Math.round(cfg.getDailyCalorieBudget()));
    }

    /**
     * Grades against the caller's actual daily calorie budget (or the config
     * default when there isn't one) — a "big portion" for a 2600 kcal
     * muscle-gain target isn't the same as for a 1500 kcal weight-loss target.
     */
    public ScoreResult score(List<FoodItem> foods, Totals totals, int dailyCalorieBudget) {
        boolean smallSnack = isSmallSnack(foods, totals);
        double balance = balancePoints(totals);
        double quality = qualityPoints(foods, totals);
        double portion = portionPoints(totals, dailyCalorieBudget, smallSnack);
        double variety = varietyPoints(foods, smallSnack);

        int score = (int) Math.round(balance + quality + portion + variety);
        score = Math.max(1, Math.min(100, score));
        return new ScoreResult(score, gradeFor(score), balance, quality, portion, variety);
    }

    /**
     * Scores one dish read off a menu, which is a different question from
     * grading a plate someone ate — see the menu weights in ScoringProperties
     * for why the meal weights mislead here. Three differences:
     *
     * <ul>
     *   <li>No small-snack exemption. It exists so a single packaged snack
     *       isn't judged as an incomplete meal, but EVERY menu dish is a single
     *       item, so it fired on all of them and handed anything under the
     *       threshold full portion and variety marks for free.
     *   <li>No variety component. One dish is one food group by definition, so
     *       it scored an identical fraction for every dish and only served to
     *       hold real meals below the snacks that got the exemption above.
     *   <li>Quality outweighs balance, and portion slides instead of plateauing.
     * </ul>
     */
    public ScoreResult scoreMenuDish(FoodItem dish, int dailyCalorieBudget) {
        List<FoodItem> single = List.of(dish);
        Totals totals = Totals.of(single);

        double balance = menuMacroPoints(totals);
        double quality = menuQualityPoints(dish, totals);
        double portion = menuPortionPoints(totals, dailyCalorieBudget);

        int score = (int) Math.round(balance + quality + portion);
        score = Math.max(1, Math.min(100, score));
        return new ScoreResult(score, gradeFor(score), balance, quality, portion, 0);
    }

    /** Re-expresses a component computed on the meal scale onto the menu scale. */
    private static double scale(double points, int fromMax, int toMax) {
        return fromMax == 0 ? 0 : points * ((double) toMax / fromMax);
    }

    /**
     * Judges a menu dish's macros on the two ways they can actually be wrong,
     * rather than on distance from an ideal split.
     *
     * <p>Symmetric balance marks a dish down as hard for being lean as for being
     * greasy, which inverts the answer on a menu: a fried-chicken-and-coconut-rice
     * plate lands near 30/40/30 and scores well, while a chicken porridge is
     * penalised for the low fat that makes it the better order. Worse, the only
     * fat signal elsewhere in the score is the deep-fried flag — so coconut milk
     * and ghee, which is most of what makes local dishes heavy, were invisible.
     *
     * <p>So: fat is penalised only when it dominates the dish, and protein only
     * when there's too little of it. Nothing is deducted for a dish being light
     * or lean, and a dish carrying calories with no protein — chips, sweetened
     * fruit, fried pastry — finally pays for it.
     */
    double menuMacroPoints(Totals totals) {
        double max = cfg.getMenuBalanceMaxPoints();
        double proteinCalories = totals.protein() * 4;
        double fatCalories = totals.fat() * 9;
        double macroCalories = proteinCalories + totals.carbs() * 4 + fatCalories;
        if (macroCalories <= 0) {
            // Nothing to judge (plain water, say) rather than a dish at fault.
            return max;
        }

        // Both factors have to agree before fat costs anything: a dish that is
        // proportionally fatty but small (grilled fish, a herbal broth) is not
        // the same fault as one that is proportionally fatty and large.
        double fatExcess = ramp(fatCalories / macroCalories,
                        cfg.getMenuFatExcessFromFraction(), cfg.getMenuFatExcessFullFraction())
                * ramp(totals.fat(), cfg.getMenuFatExcessFromGrams(), cfg.getMenuFatExcessFullGrams());
        double proteinShortfall = 1 - ramp(proteinCalories / macroCalories,
                0, cfg.getMenuAdequateProteinFraction());

        return Math.max(0, max
                - cfg.getMenuFatExcessPenaltyPoints() * fatExcess
                - cfg.getMenuLowProteinPenaltyPoints() * proteinShortfall);
    }

    /**
     * Same signals as the meal scorer — fiber, vegetables, sugar, sodium,
     * frying — but graduated rather than all-or-nothing. The meal version's
     * fixed cliffs make sodium 799mg and 801mg differ by a third of the whole
     * component, which collapses a menu into a handful of identical quality
     * scores and leaves the ordering to be decided by macro ratio. Penalties
     * here start at the threshold and reach full weight at twice it, so
     * "slightly salty" and "brutally salty" are no longer the same answer.
     */
    double menuQualityPoints(FoodItem dish, Totals totals) {
        double max = cfg.getMenuQualityMaxPoints();
        double toMenuScale = (double) cfg.getMenuQualityMaxPoints() / cfg.getQualityMaxPoints();
        double points = cfg.getQualityBasePoints() * toMenuScale;

        points += cfg.getFiberBonusPoints() * toMenuScale
                * ramp(totals.fiber(), 0, cfg.getFiberBonusThresholdGrams());
        if ("vegetable".equalsIgnoreCase(dish.foodGroup()) || "fruit".equalsIgnoreCase(dish.foodGroup())) {
            points += cfg.getVegetableBonusPoints() * toMenuScale;
        }
        // Menu-scale weight, not the meal scorer's: a single dish spending a
        // whole day's free-sugar allowance is a bigger fault than the meal
        // profile's share of it implies.
        points -= cfg.getMenuSugarPenaltyPoints()
                * ramp(totals.sugar(), cfg.getMenuSugarPenaltyThresholdGrams(),
                       cfg.getMenuSugarPenaltyFullGrams());
        points -= cfg.getSodiumPenaltyPoints() * toMenuScale
                * ramp(totals.sodium(), cfg.getSodiumPenaltyThresholdMg(), cfg.getMenuSodiumPenaltyFullMg());
        if (dish.fried()) {
            // Binary on purpose: a dish is deep-fried or it isn't.
            points -= cfg.getFriedPenaltyPoints() * toMenuScale;
        }
        return Math.max(0, Math.min(max, points));
    }

    /** 0 at or below {@code from}, 1 at or above {@code to}, linear in between. */
    private static double ramp(double value, double from, double to) {
        if (to <= from) {
            return value >= to ? 1 : 0;
        }
        return Math.max(0, Math.min(1, (value - from) / (to - from)));
    }

    /**
     * Full marks for a sensibly sized dish, sliding to zero as it grows toward
     * most of a day's calories, and scaled down below a real-dish minimum so a
     * token portion can't score like a meal.
     */
    double menuPortionPoints(Totals totals, double dailyCalorieBudget) {
        double calories = totals.calories();
        double max = cfg.getMenuPortionMaxPoints();
        double full = dailyCalorieBudget * cfg.getMenuPortionFullRatio();
        double zero = dailyCalorieBudget * cfg.getMenuPortionZeroRatio();

        if (calories < cfg.getMinMealCalories()) {
            return max * Math.max(0, calories / cfg.getMinMealCalories());
        }
        if (calories <= full) {
            return max;
        }
        if (calories >= zero) {
            return 0;
        }
        return max * (1 - (calories - full) / (zero - full));
    }

    /**
     * A single packaged item (e.g. a scanned barcode snack) under the
     * min-meal-calories threshold was never meant to be a complete meal, so
     * it shouldn't be judged as one — "under-eating" and "not enough variety"
     * both assume the log represents a whole meal.
     */
    private boolean isSmallSnack(List<FoodItem> foods, Totals totals) {
        return foods.size() == 1 && totals.calories() < cfg.getMinMealCalories();
    }

    /** Resolves the budget to grade against: the user's own target if they have one, else the config default. */
    public double effectiveBudget(Integer userDailyBudget) {
        return userDailyBudget != null ? userDailyBudget : cfg.getDailyCalorieBudget();
    }

    /** Packages a ScoreResult's four components with their max points, for the "how grading works" disclosure. */
    public ScoreBreakdown breakdownFor(ScoreResult result) {
        return new ScoreBreakdown(
                result.balancePoints(), cfg.getBalanceMaxPoints(),
                result.qualityPoints(), cfg.getQualityMaxPoints(),
                result.portionPoints(), cfg.getPortionMaxPoints(),
                result.varietyPoints(), cfg.getVarietyMaxPoints());
    }

    /** Balance (40): deduct proportionally to deviation from the ideal 30/40/30 macro split. */
    double balancePoints(Totals totals) {
        double proteinCal = totals.protein() * 4;
        double carbsCal = totals.carbs() * 4;
        double fatCal = totals.fat() * 9;
        double macroCal = proteinCal + carbsCal + fatCal;
        if (macroCal <= 0) {
            // No macros to be imbalanced about (e.g. plain water) — this isn't
            // a violation of the ideal split, there's just no split to judge.
            return cfg.getBalanceMaxPoints();
        }
        // Sum of absolute deviations halved: 0 = perfect, 1 = completely off
        double deviation = (Math.abs(proteinCal / macroCal - cfg.getIdealProteinRatio())
                + Math.abs(carbsCal / macroCal - cfg.getIdealCarbsRatio())
                + Math.abs(fatCal / macroCal - cfg.getIdealFatRatio())) / 2.0;
        double fraction = Math.max(0, 1 - deviation / cfg.getBalanceZeroDeviation());
        return cfg.getBalanceMaxPoints() * fraction;
    }

    /** Nutrient quality (30): fiber/vegetable bonuses, sugar/sodium/fried penalties. */
    double qualityPoints(List<FoodItem> foods, Totals totals) {
        double points = cfg.getQualityBasePoints();
        if (totals.fiber() >= cfg.getFiberBonusThresholdGrams()) {
            points += cfg.getFiberBonusPoints();
        }
        boolean hasVeg = foods.stream().anyMatch(f ->
                "vegetable".equalsIgnoreCase(f.foodGroup()) || "fruit".equalsIgnoreCase(f.foodGroup()));
        if (hasVeg) {
            points += cfg.getVegetableBonusPoints();
        }
        if (totals.sugar() > cfg.getSugarPenaltyThresholdGrams()) {
            points -= cfg.getSugarPenaltyPoints();
        }
        if (totals.sodium() > cfg.getSodiumPenaltyThresholdMg()) {
            points -= cfg.getSodiumPenaltyPoints();
        }
        if (foods.stream().anyMatch(FoodItem::fried)) {
            points -= cfg.getFriedPenaltyPoints();
        }
        return Math.max(0, Math.min(cfg.getQualityMaxPoints(), points));
    }

    /** Portion sanity (20): penalize meals over ~50% of the daily budget or under a real-meal minimum. */
    double portionPoints(Totals totals, double dailyCalorieBudget) {
        return portionPoints(totals, dailyCalorieBudget, false);
    }

    double portionPoints(Totals totals, double dailyCalorieBudget, boolean smallSnack) {
        double calories = totals.calories();
        double max = dailyCalorieBudget * cfg.getMaxMealBudgetRatio();
        double min = cfg.getMinMealCalories();
        if (calories >= min && calories <= max) {
            return cfg.getPortionMaxPoints();
        }
        if (calories > max) {
            // Lose all portion points by the time the meal reaches double the sane maximum
            double overshoot = Math.min(1, (calories - max) / max);
            return cfg.getPortionMaxPoints() * (1 - overshoot);
        }
        if (smallSnack) {
            return cfg.getPortionMaxPoints();
        }
        // Under-eating: scale points down toward zero calories
        return cfg.getPortionMaxPoints() * Math.max(0, calories / min);
    }

    /** Variety (10): full bonus for 3+ distinct food groups, scaled below that. */
    double varietyPoints(List<FoodItem> foods) {
        return varietyPoints(foods, false);
    }

    double varietyPoints(List<FoodItem> foods, boolean smallSnack) {
        if (smallSnack) {
            return cfg.getVarietyMaxPoints();
        }
        Set<String> groups = new HashSet<>();
        for (FoodItem f : foods) {
            if (f.foodGroup() != null && !f.foodGroup().isBlank()) {
                groups.add(f.foodGroup().toLowerCase());
            }
        }
        double fraction = Math.min(1.0, groups.size() / (double) cfg.getVarietyFullBonusGroups());
        return cfg.getVarietyMaxPoints() * fraction;
    }

    /**
     * Lowest score still worth calling a genuinely good choice (grade B).
     * Menu ranking uses it to spot a menu where nothing qualifies, so the tier
     * list can rank dishes against each other instead of stacking them all in
     * the bottom tiers.
     */
    public int healthyScoreFloor() {
        return cfg.getGradeBMin();
    }

    public String gradeFor(int score) {
        if (score >= cfg.getGradeAPlusMin()) return "A+";
        if (score >= cfg.getGradeAMin()) return "A";
        if (score >= cfg.getGradeBMin()) return "B";
        if (score >= cfg.getGradeCMin()) return "C";
        return "D";
    }
}
