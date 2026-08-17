package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One completed set.
 *
 * <p>A row rather than a counter, because the unique constraint on
 * {@code (session_id, exercise_position, set_index)} is what makes logging
 * idempotent — and idempotence is what makes the offline story work. Sets
 * finished without a connection queue on the device and replay on reconnect; a
 * replay that arrives twice must not count twice. A counter would have to be
 * right about how many times it had already been incremented, which is exactly
 * the thing a flaky connection makes impossible to know.
 */
@Entity
@Table(name = "workout_set_log")
public class WorkoutSetLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Integer exercisePosition;

    /** 0-based within the exercise. */
    @Column(nullable = false)
    private Integer setIndex;

    @Column(nullable = false)
    private Instant completedAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long v) { this.sessionId = v; }
    public Integer getExercisePosition() { return exercisePosition; }
    public void setExercisePosition(Integer v) { this.exercisePosition = v; }
    public Integer getSetIndex() { return setIndex; }
    public void setSetIndex(Integer v) { this.setIndex = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
}
