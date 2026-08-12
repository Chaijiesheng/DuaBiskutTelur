package com.duabiskuttelur.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable thresholds for the deterministic scoring engine.
 * Override any value in application.yml under the "scoring" prefix.
 */
@ConfigurationProperties(prefix = "scoring")
public class ScoringProperties {

    // Component maximums (sum to 100)
    private int balanceMaxPoints = 40;
    private int qualityMaxPoints = 30;
    private int portionMaxPoints = 20;
    private int varietyMaxPoints = 10;

    // Balance: ideal macro split by calories
    private double idealProteinRatio = 0.30;
    private double idealCarbsRatio = 0.40;
    private double idealFatRatio = 0.30;
    // Total absolute deviation (halved) at which balance points reach zero
    private double balanceZeroDeviation = 0.50;

    // Nutrient quality
    private int qualityBasePoints = 20;
    private double fiberBonusThresholdGrams = 8.0;
    private int fiberBonusPoints = 5;
    private int vegetableBonusPoints = 5;
    private double sugarPenaltyThresholdGrams = 25.0;
    private int sugarPenaltyPoints = 8;
    /**
     * Where the sugar penalty reaches its full value. Between the threshold and
     * here it ramps linearly instead of snapping on, so 24g and 26g no longer
     * differ by the whole penalty.
     */
    private double sugarPenaltyFullAtGrams = 50.0;
    private double sodiumPenaltyThresholdMg = 800.0;
    private int sodiumPenaltyPoints = 8;
    /**
     * Full sodium penalty at 1600mg - twice a meal's fair share of the 2300mg
     * daily guideline, i.e. one meal delivering roughly two thirds of the day.
     *
     * <p>This is a deliberate softening, and a bounded one. 800mg in a single
     * meal is a reasonable point to *start* being concerned; it was the point at
     * which the entire 8-point penalty landed. That is harsh for Malaysian food
     * and it rested on the least reliable number in the whole pipeline - added
     * salt in local cooking is invisible to both a photo and a generic USDA
     * match. Ramping to full at 1600 keeps a real signal (a genuinely salty meal
     * still loses most of the penalty) while stopping the least trustworthy
     * input from dominating the grade. 2300 was considered and rejected as too
     * generous: it would leave a 1200mg meal losing only 2 of 8 points. Revisit
     * once the local food database carries real sodium figures.
     */
    private double sodiumPenaltyFullAtMg = 1600.0;
    private int friedPenaltyPoints = 8;

    /**
     * Penalty for a stir-fried dish, applied instead of the full
     * {@link #friedPenaltyPoints} when the model reports "stir-fried".
     *
     * <p>The vision model used to answer a single "fried" boolean, which forced
     * it to call char kway teow and deep-fried chicken wings the same thing.
     * With a cooking-method vocabulary it no longer has to, and grading them
     * identically is a real inaccuracy: a wok dish carries meaningfully less oil
     * than something submerged in it. Set this equal to friedPenaltyPoints to
     * restore the old flat behaviour.
     */
    private int stirFriedPenaltyPoints = 4;

    // Portion sanity
    private double dailyCalorieBudget = 2000.0;
    private double maxMealBudgetRatio = 0.50;   // meal should stay under 50% of daily budget
    private double minMealCalories = 250.0;      // under this is "not a real meal"

    // Variety
    private int varietyFullBonusGroups = 3;

    // Grade bands
    private int gradeAPlusMin = 90;
    private int gradeAMin = 80;
    private int gradeBMin = 70;
    private int gradeCMin = 55;

    public int getBalanceMaxPoints() { return balanceMaxPoints; }
    public void setBalanceMaxPoints(int v) { this.balanceMaxPoints = v; }
    public int getQualityMaxPoints() { return qualityMaxPoints; }
    public void setQualityMaxPoints(int v) { this.qualityMaxPoints = v; }
    public int getPortionMaxPoints() { return portionMaxPoints; }
    public void setPortionMaxPoints(int v) { this.portionMaxPoints = v; }
    public int getVarietyMaxPoints() { return varietyMaxPoints; }
    public void setVarietyMaxPoints(int v) { this.varietyMaxPoints = v; }
    public double getIdealProteinRatio() { return idealProteinRatio; }
    public void setIdealProteinRatio(double v) { this.idealProteinRatio = v; }
    public double getIdealCarbsRatio() { return idealCarbsRatio; }
    public void setIdealCarbsRatio(double v) { this.idealCarbsRatio = v; }
    public double getIdealFatRatio() { return idealFatRatio; }
    public void setIdealFatRatio(double v) { this.idealFatRatio = v; }
    public double getBalanceZeroDeviation() { return balanceZeroDeviation; }
    public void setBalanceZeroDeviation(double v) { this.balanceZeroDeviation = v; }
    public int getQualityBasePoints() { return qualityBasePoints; }
    public void setQualityBasePoints(int v) { this.qualityBasePoints = v; }
    public double getFiberBonusThresholdGrams() { return fiberBonusThresholdGrams; }
    public void setFiberBonusThresholdGrams(double v) { this.fiberBonusThresholdGrams = v; }
    public int getFiberBonusPoints() { return fiberBonusPoints; }
    public void setFiberBonusPoints(int v) { this.fiberBonusPoints = v; }
    public int getVegetableBonusPoints() { return vegetableBonusPoints; }
    public void setVegetableBonusPoints(int v) { this.vegetableBonusPoints = v; }
    public double getSugarPenaltyThresholdGrams() { return sugarPenaltyThresholdGrams; }
    public void setSugarPenaltyThresholdGrams(double v) { this.sugarPenaltyThresholdGrams = v; }
    public int getSugarPenaltyPoints() { return sugarPenaltyPoints; }
    public void setSugarPenaltyPoints(int v) { this.sugarPenaltyPoints = v; }
    public double getSugarPenaltyFullAtGrams() { return sugarPenaltyFullAtGrams; }
    public void setSugarPenaltyFullAtGrams(double v) { this.sugarPenaltyFullAtGrams = v; }
    public double getSodiumPenaltyFullAtMg() { return sodiumPenaltyFullAtMg; }
    public void setSodiumPenaltyFullAtMg(double v) { this.sodiumPenaltyFullAtMg = v; }
    public double getSodiumPenaltyThresholdMg() { return sodiumPenaltyThresholdMg; }
    public void setSodiumPenaltyThresholdMg(double v) { this.sodiumPenaltyThresholdMg = v; }
    public int getSodiumPenaltyPoints() { return sodiumPenaltyPoints; }
    public void setSodiumPenaltyPoints(int v) { this.sodiumPenaltyPoints = v; }
    public int getFriedPenaltyPoints() { return friedPenaltyPoints; }
    public void setFriedPenaltyPoints(int v) { this.friedPenaltyPoints = v; }
    public int getStirFriedPenaltyPoints() { return stirFriedPenaltyPoints; }
    public void setStirFriedPenaltyPoints(int v) { this.stirFriedPenaltyPoints = v; }
    public double getDailyCalorieBudget() { return dailyCalorieBudget; }
    public void setDailyCalorieBudget(double v) { this.dailyCalorieBudget = v; }
    public double getMaxMealBudgetRatio() { return maxMealBudgetRatio; }
    public void setMaxMealBudgetRatio(double v) { this.maxMealBudgetRatio = v; }
    public double getMinMealCalories() { return minMealCalories; }
    public void setMinMealCalories(double v) { this.minMealCalories = v; }
    public int getVarietyFullBonusGroups() { return varietyFullBonusGroups; }
    public void setVarietyFullBonusGroups(int v) { this.varietyFullBonusGroups = v; }
    public int getGradeAPlusMin() { return gradeAPlusMin; }
    public void setGradeAPlusMin(int v) { this.gradeAPlusMin = v; }
    public int getGradeAMin() { return gradeAMin; }
    public void setGradeAMin(int v) { this.gradeAMin = v; }
    public int getGradeBMin() { return gradeBMin; }
    public void setGradeBMin(int v) { this.gradeBMin = v; }
    public int getGradeCMin() { return gradeCMin; }
    public void setGradeCMin(int v) { this.gradeCMin = v; }
}
