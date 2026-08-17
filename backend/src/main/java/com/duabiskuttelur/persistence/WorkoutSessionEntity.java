package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One day's planned workout.
 *
 * <p>{@code sessionDate} is a {@link LocalDate} rather than an {@link Instant}
 * on purpose: "today's workout" is a day in the user's own calendar, and an
 * instant would put the rollover at UTC midnight — 8am in Kuala Lumpur, which is
 * mid-morning of the day whose workout it is meant to end.
 */
@Entity
@Table(name = "workout_session")
public class WorkoutSessionEntity {

    /** The four states a session moves through. Stored as the lowercase tag. */
    public enum Status {
        PLANNED, IN_PROGRESS, COMPLETED, SKIPPED;

        public String tag() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate sessionDate;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String focus;

    /** Planned length; {@link #actualMinutes} is what it really took. */
    @Column(nullable = false)
    private Integer minutes;

    @Column(nullable = false)
    private String level;

    @Column(nullable = false)
    private String status;

    /** Model output, so bounded by the prompt rather than by this schema. */
    @Lob
    @Column
    private String coachNote;

    /** The "What did you look at?" bullets, as a JSON array of strings. */
    @Lob
    @Column
    private String coachFactors;

    /** "ai" or "rules" — the only way to tell a plain note from a degraded one. */
    @Column
    private String coachSource;

    @Column
    private Instant startedAt;

    @Column
    private Instant completedAt;

    @Column
    private Integer actualMinutes;

    @Column
    private String feel;

    @Column
    private String energy;

    @Column(nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public LocalDate getSessionDate() { return sessionDate; }
    public void setSessionDate(LocalDate v) { this.sessionDate = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getFocus() { return focus; }
    public void setFocus(String v) { this.focus = v; }
    public Integer getMinutes() { return minutes; }
    public void setMinutes(Integer v) { this.minutes = v; }
    public String getLevel() { return level; }
    public void setLevel(String v) { this.level = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCoachNote() { return coachNote; }
    public void setCoachNote(String v) { this.coachNote = v; }
    public String getCoachFactors() { return coachFactors; }
    public void setCoachFactors(String v) { this.coachFactors = v; }
    public String getCoachSource() { return coachSource; }
    public void setCoachSource(String v) { this.coachSource = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public Integer getActualMinutes() { return actualMinutes; }
    public void setActualMinutes(Integer v) { this.actualMinutes = v; }
    public String getFeel() { return feel; }
    public void setFeel(String v) { this.feel = v; }
    public String getEnergy() { return energy; }
    public void setEnergy(String v) { this.energy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
