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

    public String gradeFor(int score) {
        if (score >= cfg.getGradeAPlusMin()) return "A+";
        if (score >= cfg.getGradeAMin()) return "A";
        if (score >= cfg.getGradeBMin()) return "B";
        if (score >= cfg.getGradeCMin()) return "C";
        return "D";
    }
}
