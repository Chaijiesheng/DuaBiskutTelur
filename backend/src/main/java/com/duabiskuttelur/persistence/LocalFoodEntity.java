package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One curated Malaysian dish, per 100g, consulted before USDA.
 *
 * <p>Read-only from the app's point of view: rows arrive through the repeatable
 * seed migration, never through a request. Nothing in the running application
 * writes here, which is deliberate — a composition table that anything can
 * update is a composition table nobody can cite.
 */
@Entity
@Table(name = "local_food")
public class LocalFoodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Canonicalized by the same function as the nutrition cache, so the two agree on dish identity. */
    @Column(nullable = false, unique = true, length = 255)
    private String canonicalName;

    @Column(nullable = false, length = 255)
    private String displayName;

    /** One typical serving. Menu scans have no plate to measure, so they replay this. */
    @Column(nullable = false)
    private double typicalGrams;

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

    @Column(length = 64)
    private String foodGroup;

    @Column(length = 32)
    private String cookingMethod;

    /** 'MyFCD' | 'HPB' | 'curated' — what makes a wrong number traceable. */
    @Column(nullable = false, length = 64)
    private String source;

    /** Edition, page or URL. A composition figure with no citation cannot be checked. */
    @Column(length = 512)
    private String provenance;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public double getTypicalGrams() { return typicalGrams; }
    public void setTypicalGrams(double typicalGrams) { this.typicalGrams = typicalGrams; }
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
    public String getFoodGroup() { return foodGroup; }
    public void setFoodGroup(String foodGroup) { this.foodGroup = foodGroup; }
    public String getCookingMethod() { return cookingMethod; }
    public void setCookingMethod(String cookingMethod) { this.cookingMethod = cookingMethod; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getProvenance() { return provenance; }
    public void setProvenance(String provenance) { this.provenance = provenance; }
}
