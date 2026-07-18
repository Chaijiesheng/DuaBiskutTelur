package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "meal_analysis")
public class MealAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owner (app_user.id). Null only for legacy rows created before login existed. */
    @Column
    private Long userId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false, length = 2)
    private String grade;

    @Column(nullable = false)
    private double calories;

    /** Short comma-separated list of food names for the history list. */
    @Column(length = 512)
    private String summary;

    /** Small base64 JPEG thumbnail (data URL payload only). */
    @Lob
    @Column
    private String thumbnail;

    /** Full AnalysisResponse as JSON, so past results can be reopened. */
    @Lob
    @Column
    private String resultJson;

    /** "photo" or "barcode". Null for rows created before this column existed — treat as "photo". */
    @Column
    private String source;

    /**
     * Denormalized fields so achievements/dashboard can read a meal's totals
     * without deserializing resultJson on every request (see AchievementsService,
     * DashboardService) — same "null means legacy row, fall back to resultJson"
     * convention as source above.
     */
    @Column
    private Double protein;

    /** Count of individual food items in this meal with foodGroup == "vegetable". */
    @Column
    private Integer vegetableCount;

    /** Whether any food item in the meal has foodGroup == "fruit". */
    @Column
    private Boolean hasFruit;

    /** Whether every food item's name matches the beverage keyword list (and the meal isn't empty). */
    @Column
    private Boolean beverageOnly;

    /** Whether every food item's name matches the coffee keyword list (and the meal isn't empty). */
    @Column
    private Boolean coffeeOnly;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setId(Long id) { this.id = id; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public double getCalories() { return calories; }
    public void setCalories(double calories) { this.calories = calories; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getThumbnail() { return thumbnail; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Double getProtein() { return protein; }
    public void setProtein(Double protein) { this.protein = protein; }
    public Integer getVegetableCount() { return vegetableCount; }
    public void setVegetableCount(Integer vegetableCount) { this.vegetableCount = vegetableCount; }
    public Boolean getHasFruit() { return hasFruit; }
    public void setHasFruit(Boolean hasFruit) { this.hasFruit = hasFruit; }
    public Boolean getBeverageOnly() { return beverageOnly; }
    public void setBeverageOnly(Boolean beverageOnly) { this.beverageOnly = beverageOnly; }
    public Boolean getCoffeeOnly() { return coffeeOnly; }
    public void setCoffeeOnly(Boolean coffeeOnly) { this.coffeeOnly = coffeeOnly; }
}
