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

    /** Grades against the maintenance macro split, for callers that have no goal to hand. */
    public ScoreResult score(List<FoodItem> foods, Totals totals, int dailyCalorieBudget) {
        return score(foods, totals, dailyCalorieBudget, MacroTargets.DEFAULT_GOAL);
    }

    /**
     * Grades against the caller's actual daily calorie budget (or the config
     * default when there isn't one) — a "big portion" for a 2600 kcal
     * muscle-gain target isn't the same as for a 1500 kcal weight-loss target.
     */
    public ScoreResult score(List<FoodItem> foods, Totals totals, int dailyCalorieBudget, String goal) {
        boolean smallSnack = isSmallSnack(totals);
        double balance = balancePoints(totals, goal);
        double quality = qualityPoints(foods, totals);
        double portion = portionPoints(totals, dailyCalorieBudget, smallSnack);
        double variety = varietyPoints(foods, smallSnack);

        int score = (int) Math.round(balance + quality + portion + variety);
        score = Math.max(1, Math.min(100, score));
        return new ScoreResult(score, gradeFor(score), balance, quality, portion, variety);
    }

    /**
     * Something under the min-meal-calories threshold was never meant to be a
     * complete meal, so it should not be judged as one — "under-eating" and "not
     * enough variety" both assume the log represents a whole meal.
     *
     * <p>Keyed purely on calories. It used to also require a single item, which
     * meant a coffee <em>and</em> a biscuit — two items, ~180 kcal — was graded
     * as a failed meal: portion points scaled toward zero for under-eating and
     * variety was judged on 2 of 3 food groups. The item count never carried any
     * meaning here; total calories is the whole question.
     */
    private boolean isSmallSnack(Totals totals) {
        return totals.calories() < cfg.getMinMealCalories();
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

    /** Balance against the maintenance split; the goal-aware overload is the real one. */
    double balancePoints(Totals totals) {
        return balancePoints(totals, MacroTargets.DEFAULT_GOAL);
    }

    /**
     * Balance (40): deduct proportionally to deviation from the macro split this
     * user is aiming for.
     *
     * <p>The split comes from {@link MacroTargets}, which is the same table the
     * frontend's MacroDonut already displays. Until now this graded everyone
     * against a flat 30/40/30 that matched none of the three displayed targets —
     * a maintenance user was shown "protein target 25%" and then marked down for
     * hitting it, with both numbers visible on one screen.
     */
    double balancePoints(Totals totals, String goal) {
        MacroTargets.Split ideal = MacroTargets.forGoal(goal);
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
        double deviation = (Math.abs(proteinCal / macroCal - ideal.protein())
                + Math.abs(carbsCal / macroCal - ideal.carbs())
                + Math.abs(fatCal / macroCal - ideal.fat())) / 2.0;
        double fraction = Math.max(0, 1 - deviation / cfg.getBalanceZeroDeviation());
        return cfg.getBalanceMaxPoints() * fraction;
    }

    /** Nutrient quality (30): fiber/vegetable bonuses, sugar/sodium/fried penalties. */
    double qualityPoints(List<FoodItem> foods, Totals totals) {
        double points = cfg.getQualityBasePoints();
        // Proportional rather than a cliff: 7.9g of fibre used to score nothing
        // and 8.0g the full five points, so two near-identical meals could differ
        // by a whole bonus on a number nobody measures that precisely.
        points += cfg.getFiberBonusPoints()
                * ramp(totals.fiber(), 0, cfg.getFiberBonusThresholdGrams());
        boolean hasVeg = foods.stream().anyMatch(f ->
                "vegetable".equalsIgnoreCase(f.foodGroup()) || "fruit".equalsIgnoreCase(f.foodGroup()));
        if (hasVeg) {
            points += cfg.getVegetableBonusPoints();
        }
        // Same reasoning in the other direction: the penalty starts at the
        // threshold and reaches its full value at the "full at" mark, instead of
        // 799mg costing nothing and 801mg costing eight points.
        points -= cfg.getSugarPenaltyPoints()
                * ramp(totals.sugar(), cfg.getSugarPenaltyThresholdGrams(), cfg.getSugarPenaltyFullAtGrams());
        points -= cfg.getSodiumPenaltyPoints()
                * ramp(totals.sodium(), cfg.getSodiumPenaltyThresholdMg(), cfg.getSodiumPenaltyFullAtMg());
        points -= friedPenalty(foods);
        return Math.max(0, Math.min(cfg.getQualityMaxPoints(), points));
    }

    /**
     * 0 at or below {@code from}, 1 at or above {@code to}, linear between.
     *
     * <p>A degenerate range (to <= from) falls back to a step at {@code from},
     * so setting "full at" equal to the threshold restores the old cliff exactly
     * — which is what makes this change reversible from configuration alone.
     */
    static double ramp(double value, double from, double to) {
        if (to <= from) {
            return value > from ? 1 : 0;
        }
        return Math.max(0, Math.min(1, (value - from) / (to - from)));
    }

    /**
     * The oiliest cooking method present, scored once for the meal rather than
     * once per item — the penalty has always been "does this meal contain
     * something fried", not a tally.
     *
     * <p>Deep-fried outranks stir-fried, so a meal with both is judged on the
     * deep-fried item. Items with no cooking method (barcode scans, rows written
     * before the vocabulary existed) fall back to the old boolean and take the
     * full penalty, which is what they were already scored with.
     */
    double friedPenalty(List<FoodItem> foods) {
        boolean deepFried = false;
        boolean stirFried = false;
        for (FoodItem f : foods) {
            if ("stir-fried".equals(f.cookingMethod())) {
                stirFried = true;
            } else if (f.fried()) {
                deepFried = true;
            }
        }
        if (deepFried) return cfg.getFriedPenaltyPoints();
        return stirFried ? cfg.getStirFriedPenaltyPoints() : 0;
    }

    /** Portion sanity (20): penalize meals over ~50% of the daily budget or under a real-meal minimum. */
    double portionPoints(Totals totals, double dailyCalorieBudget) {
        return portionPoints(totals, dailyCalorieBudget, false);
    }

    /** Package-visible for the tests that exercise the snack exemption directly. */
    boolean smallSnack(Totals totals) {
        return isSmallSnack(totals);
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
        // Under-eating: scale points down toward zero calories.
        //
        // Unreachable from score() as configured, and deliberately so. The snack
        // exemption above now triggers on the same minMealCalories threshold this
        // branch is measured against, so anything that would land here is already
        // exempt — "under-eating" and "this was a snack" were always the same
        // test, and the item count was the only thing pretending otherwise. It
        // stays for the two-argument overload (which scores without the
        // exemption) and would come back into play if the two thresholds were
        // ever separated into "too small to be a meal" and "too small, full
        // stop".
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

    public String gradeFor(int score) {
        if (score >= cfg.getGradeAPlusMin()) return "A+";
        if (score >= cfg.getGradeAMin()) return "A";
        if (score >= cfg.getGradeBMin()) return "B";
        if (score >= cfg.getGradeCMin()) return "C";
        return "D";
    }
}
