package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One dish's resolved nutrition profile, keyed by its canonicalized name.
 * Values are stored per 100g so a scan can scale them to its own portion;
 * {@code grams}/{@code portion} record the portion first resolved for this dish,
 * used by flows that have no observed portion of their own (menu scans).
 */
@Entity
@Table(name = "nutrition_cache")
public class NutritionCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lowercased, punctuation-flattened dish name — the cache key (see NutritionCacheService.canonicalize). */
    @Column(nullable = false, unique = true, length = 255)
    private String canonicalName;

    /** The dish name as first seen, kept only to make the table readable when inspecting entries. */
    @Column(nullable = false, length = 255)
    private String displayName;

    @Column(nullable = false)
    private Instant resolvedAt;

    /** "usda" or "estimated" — pinned with the numbers so the trust signal is stable too. */
    @Column(nullable = false, length = 32)
    private String source;

    @Column(length = 64)
    private String foodGroup;

    /**
     * Superseded by {@link #cookingMethod} but kept and still written, so a row
     * pinned by this version is still readable by the previous one during a
     * rolling deploy.
     */
    @Column(nullable = false)
    private boolean fried;

    /** One of FoodTaxonomy.COOKING_METHODS; null on rows pinned before it existed. */
    @Column(length = 32)
    private String cookingMethod;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false)
    private double grams;

    /** The portion bracket, pinned alongside grams so a menu dish cannot re-roll its range between scans. */
    @Column(nullable = false)
    private double gramsLow;

    @Column(nullable = false)
    private double gramsHigh;

    @Column(length = 255)
    private String portion;

    @Column(name = "calories_per100g", nullable = false)
    private double caloriesPer100g;

    @Column(name = "protein_per100g", nullable = false)
    private double proteinPer100g;

    @Column(name = "carbs_per100g", nullable = false)
    private double carbsPer100g;

    @Column(name = "fat_per100g", nullable = false)
    private double fatPer100g;

    @Column(name = "fiber_per100g", nullable = false)
    private double fiberPer100g;

    @Column(name = "sugar_per100g", nullable = false)
    private double sugarPer100g;

    @Column(name = "sodium_per100g", nullable = false)
    private double sodiumPer100g;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getFoodGroup() { return foodGroup; }
    public void setFoodGroup(String foodGroup) { this.foodGroup = foodGroup; }
    public boolean isFried() { return fried; }
    public void setFried(boolean fried) { this.fried = fried; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getCookingMethod() { return cookingMethod; }
    public void setCookingMethod(String cookingMethod) { this.cookingMethod = cookingMethod; }
    public double getGrams() { return grams; }
    public void setGrams(double grams) { this.grams = grams; }
    public double getGramsLow() { return gramsLow; }
    public void setGramsLow(double gramsLow) { this.gramsLow = gramsLow; }
    public double getGramsHigh() { return gramsHigh; }
    public void setGramsHigh(double gramsHigh) { this.gramsHigh = gramsHigh; }
    public String getPortion() { return portion; }
    public void setPortion(String portion) { this.portion = portion; }
    public double getCaloriesPer100g() { return caloriesPer100g; }
    public void setCaloriesPer100g(double v) { this.caloriesPer100g = v; }
    public double getProteinPer100g() { return proteinPer100g; }
    public void setProteinPer100g(double v) { this.proteinPer100g = v; }
    public double getCarbsPer100g() { return carbsPer100g; }
    public void setCarbsPer100g(double v) { this.carbsPer100g = v; }
    public double getFatPer100g() { return fatPer100g; }
    public void setFatPer100g(double v) { this.fatPer100g = v; }
    public double getFiberPer100g() { return fiberPer100g; }
    public void setFiberPer100g(double v) { this.fiberPer100g = v; }
    public double getSugarPer100g() { return sugarPer100g; }
    public void setSugarPer100g(double v) { this.sugarPer100g = v; }
    public double getSodiumPer100g() { return sodiumPer100g; }
    public void setSodiumPer100g(double v) { this.sodiumPer100g = v; }
}
