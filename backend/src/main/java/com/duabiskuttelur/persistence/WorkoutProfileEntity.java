package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The six answers from workout onboarding. One row per user, updated in place.
 *
 * <p>{@code goal} is deliberately not the same value set as {@code users.goal}:
 * that one decides a calorie budget, this one decides a strength/cardio mix.
 * They happen to agree today, and collapsing them would make the day they stop
 * agreeing a migration instead of a value.
 */
@Entity
@Table(name = "workout_profile")
public class WorkoutProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String goal;

    @Column(nullable = false)
    private String level;

    @Column(nullable = false)
    private Integer daysPerWeek;

    @Column(nullable = false)
    private Integer sessionMinutes;

    /** Comma-joined tags. Only ever read whole, alongside the rest of this row. */
    @Column(nullable = false)
    private String equipment;

    /** Comma-joined tags, and genuinely optional — the last onboarding step is skippable. */
    @Column
    private String preferences;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getGoal() { return goal; }
    public void setGoal(String v) { this.goal = v; }
    public String getLevel() { return level; }
    public void setLevel(String v) { this.level = v; }
    public Integer getDaysPerWeek() { return daysPerWeek; }
    public void setDaysPerWeek(Integer v) { this.daysPerWeek = v; }
    public Integer getSessionMinutes() { return sessionMinutes; }
    public void setSessionMinutes(Integer v) { this.sessionMinutes = v; }
    public String getEquipment() { return equipment; }
    public void setEquipment(String v) { this.equipment = v; }
    public String getPreferences() { return preferences; }
    public void setPreferences(String v) { this.preferences = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
