package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A user, keyed by their stable Google account id (the OpenID "sub" claim).
 * Holds identity from Google plus the optional fitness profile used to
 * calculate their daily calorie budget.
 */
@Entity
@Table(name = "app_user")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String googleSub;

    private String email;
    private String name;

    @Column(length = 1024)
    private String pictureUrl;

    // Fitness profile — all nullable until the user fills it in.
    private Integer age;
    private String sex;
    private Double weightKg;
    private Double heightCm;
    private Integer steps;
    private String exerciseFrequency;
    private String goal;
    private Integer dailyBudget;

    /** Daily water target in ml. Null until the user picks one; defaults to 2000ml. */
    private Integer waterTargetMl;

    @Column(nullable = false)
    private Instant createdAt;

    public boolean hasProfile() {
        return age != null && sex != null && weightKg != null
                && heightCm != null && exerciseFrequency != null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGoogleSub() { return googleSub; }
    public void setGoogleSub(String v) { this.googleSub = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getPictureUrl() { return pictureUrl; }
    public void setPictureUrl(String v) { this.pictureUrl = v; }
    public Integer getAge() { return age; }
    public void setAge(Integer v) { this.age = v; }
    public String getSex() { return sex; }
    public void setSex(String v) { this.sex = v; }
    public Double getWeightKg() { return weightKg; }
    public void setWeightKg(Double v) { this.weightKg = v; }
    public Double getHeightCm() { return heightCm; }
    public void setHeightCm(Double v) { this.heightCm = v; }
    public Integer getSteps() { return steps; }
    public void setSteps(Integer v) { this.steps = v; }
    public String getExerciseFrequency() { return exerciseFrequency; }
    public void setExerciseFrequency(String v) { this.exerciseFrequency = v; }
    public String getGoal() { return goal; }
    public void setGoal(String v) { this.goal = v; }
    public Integer getDailyBudget() { return dailyBudget; }
    public void setDailyBudget(Integer v) { this.dailyBudget = v; }
    public Integer getWaterTargetMl() { return waterTargetMl; }
    public void setWaterTargetMl(Integer v) { this.waterTargetMl = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
