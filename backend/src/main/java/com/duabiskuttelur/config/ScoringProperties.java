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
    private double sodiumPenaltyThresholdMg = 800.0;
    private int sodiumPenaltyPoints = 8;
    private int friedPenaltyPoints = 8;

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
    public double getSodiumPenaltyThresholdMg() { return sodiumPenaltyThresholdMg; }
    public void setSodiumPenaltyThresholdMg(double v) { this.sodiumPenaltyThresholdMg = v; }
    public int getSodiumPenaltyPoints() { return sodiumPenaltyPoints; }
    public void setSodiumPenaltyPoints(int v) { this.sodiumPenaltyPoints = v; }
    public int getFriedPenaltyPoints() { return friedPenaltyPoints; }
    public void setFriedPenaltyPoints(int v) { this.friedPenaltyPoints = v; }
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
