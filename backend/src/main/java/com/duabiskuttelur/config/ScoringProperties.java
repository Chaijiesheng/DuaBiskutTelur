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

    /*
     * Menu-dish weights. Grading a plate you ate and ranking one dish on a menu
     * are different questions, and the meal weights above answer the wrong one
     * here: a single dish always has exactly one food group (so variety is a
     * constant, not a signal), and macro ratio rewards a big greasy dish for
     * happening to land near 30/40/30. Quality — fried, sodium, sugar, fiber,
     * vegetables — is the component that actually separates a steamed chicken
     * rice from a fried chicken nasi lemak, so it carries the most weight.
     * These sum to 100 with variety deliberately absent.
     */
    private int menuQualityMaxPoints = 50;
    private int menuBalanceMaxPoints = 25;
    private int menuPortionMaxPoints = 25;
    /**
     * Portion gradient for a menu dish, as a fraction of the daily budget: full
     * marks up to the first, sliding to zero at the second. The meal scorer's
     * flat pass/fail plateau gave a 300 kcal porridge and a 950 kcal fried rice
     * identical marks, which is most of why portion did no work.
     */
    private double menuPortionFullRatio = 0.30;
    private double menuPortionZeroRatio = 0.70;
    /*
     * The meal scorer marks a dish down for any distance from a 30/40/30 split,
     * in either direction — which treats "too little fat" as the same fault as
     * "too much". For a single dish that's backwards: a low-fat porridge is a
     * good order, while a coconut-rice plate reads as nicely balanced. So a menu
     * dish is judged on two one-directional faults instead.
     *
     * Excess fat: nothing until fat supplies this share of the dish's calories,
     * full penalty by the second figure. Catches coconut milk and ghee, which
     * the deep-fried flag never saw.
     */
    private double menuFatExcessFromFraction = 0.35;
    private double menuFatExcessFullFraction = 0.60;
    private int menuFatExcessPenaltyPoints = 13;
    /*
     * The fraction on its own is scale-blind, and on a real menu that reads
     * backwards. A 360 kcal fish-in-tamarind dish carrying 19g of fat is 48%
     * fat only because there's no rice to dilute it, and was penalised harder
     * than a 548 kcal rendang plate carrying 20.5g at 34%. Same fat, opposite
     * verdict. So the share is now multiplied by how much fat there actually
     * is: nothing below the first figure, full weight at the second. A dish
     * has to be both proportionally and absolutely fatty to take the hit.
     */
    private double menuFatExcessFromGrams = 15.0;
    private double menuFatExcessFullGrams = 45.0;
    /*
     * Thin protein: full marks once protein supplies this share of calories,
     * scaling to the full penalty at none. This is the "empty calories" signal
     * the score never had — chips, sweetened fruit and fried pastry all look
     * fine on every other axis.
     */
    private double menuAdequateProteinFraction = 0.20;
    private int menuLowProteinPenaltyPoints = 12;
    /**
     * Sugar starts costing a menu dish earlier than it does a whole meal: the
     * meal figure is roughly a day's worth of free sugars, so applying it per
     * dish let a single item spend the entire daily allowance unpenalised.
     */
    private double menuSugarPenaltyThresholdGrams = 15.0;
    /*
     * Sugar and sodium both used to reach full weight at twice their threshold
     * — 30g and 1600mg. Malaysian food routinely runs far past both, so the
     * scale ran out exactly where the interesting cases start: a 63g ais kacang
     * cost the same as a 30g dessert, and a 3390mg bak kut teh the same as a
     * merely salty noodle soup. Across a 30-dish benchmark sugar correlated
     * with ranking error at r = -0.002 — the score could not see it at all.
     * The ramps now run to where the outliers actually sit, and sugar carries
     * its own menu-scale weight rather than the meal scorer's.
     */
    private double menuSugarPenaltyFullGrams = 60.0;
    private double menuSugarPenaltyPoints = 25.0;
    private double menuSodiumPenaltyFullMg = 3000.0;

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
    public int getMenuQualityMaxPoints() { return menuQualityMaxPoints; }
    public void setMenuQualityMaxPoints(int v) { this.menuQualityMaxPoints = v; }
    public int getMenuBalanceMaxPoints() { return menuBalanceMaxPoints; }
    public void setMenuBalanceMaxPoints(int v) { this.menuBalanceMaxPoints = v; }
    public int getMenuPortionMaxPoints() { return menuPortionMaxPoints; }
    public void setMenuPortionMaxPoints(int v) { this.menuPortionMaxPoints = v; }
    public double getMenuPortionFullRatio() { return menuPortionFullRatio; }
    public void setMenuPortionFullRatio(double v) { this.menuPortionFullRatio = v; }
    public double getMenuPortionZeroRatio() { return menuPortionZeroRatio; }
    public void setMenuPortionZeroRatio(double v) { this.menuPortionZeroRatio = v; }
    public double getMenuFatExcessFromFraction() { return menuFatExcessFromFraction; }
    public void setMenuFatExcessFromFraction(double v) { this.menuFatExcessFromFraction = v; }
    public double getMenuFatExcessFullFraction() { return menuFatExcessFullFraction; }
    public void setMenuFatExcessFullFraction(double v) { this.menuFatExcessFullFraction = v; }
    public int getMenuFatExcessPenaltyPoints() { return menuFatExcessPenaltyPoints; }
    public void setMenuFatExcessPenaltyPoints(int v) { this.menuFatExcessPenaltyPoints = v; }
    public double getMenuFatExcessFromGrams() { return menuFatExcessFromGrams; }
    public void setMenuFatExcessFromGrams(double v) { this.menuFatExcessFromGrams = v; }
    public double getMenuFatExcessFullGrams() { return menuFatExcessFullGrams; }
    public void setMenuFatExcessFullGrams(double v) { this.menuFatExcessFullGrams = v; }
    public double getMenuSugarPenaltyFullGrams() { return menuSugarPenaltyFullGrams; }
    public void setMenuSugarPenaltyFullGrams(double v) { this.menuSugarPenaltyFullGrams = v; }
    public double getMenuSugarPenaltyPoints() { return menuSugarPenaltyPoints; }
    public void setMenuSugarPenaltyPoints(double v) { this.menuSugarPenaltyPoints = v; }
    public double getMenuSodiumPenaltyFullMg() { return menuSodiumPenaltyFullMg; }
    public void setMenuSodiumPenaltyFullMg(double v) { this.menuSodiumPenaltyFullMg = v; }
    public double getMenuAdequateProteinFraction() { return menuAdequateProteinFraction; }
    public void setMenuAdequateProteinFraction(double v) { this.menuAdequateProteinFraction = v; }
    public int getMenuLowProteinPenaltyPoints() { return menuLowProteinPenaltyPoints; }
    public void setMenuLowProteinPenaltyPoints(int v) { this.menuLowProteinPenaltyPoints = v; }
    public double getMenuSugarPenaltyThresholdGrams() { return menuSugarPenaltyThresholdGrams; }
    public void setMenuSugarPenaltyThresholdGrams(double v) { this.menuSugarPenaltyThresholdGrams = v; }
    public int getGradeAPlusMin() { return gradeAPlusMin; }
    public void setGradeAPlusMin(int v) { this.gradeAPlusMin = v; }
    public int getGradeAMin() { return gradeAMin; }
    public void setGradeAMin(int v) { this.gradeAMin = v; }
    public int getGradeBMin() { return gradeBMin; }
    public void setGradeBMin(int v) { this.gradeBMin = v; }
    public int getGradeCMin() { return gradeCMin; }
    public void setGradeCMin(int v) { this.gradeCMin = v; }
}
