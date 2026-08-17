package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One slot of one session, frozen at generation time.
 *
 * <p>Name, target, dose and cue are <em>copied</em> out of
 * {@code workout/exercises.csv} rather than looked up through
 * {@link #exerciseKey} on read. A session is the record of what the user was
 * actually asked to do, and editing a line of that CSV must not quietly rewrite
 * what last month says happened. The key is kept alongside so the Replace sheet
 * can still find same-pattern alternatives.
 */
@Entity
@Table(name = "workout_session_exercise")
public class WorkoutSessionExerciseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    /** 0-based order within the session, and the stable handle the client logs sets against. */
    @Column(nullable = false)
    private Integer position;

    @Column(nullable = false)
    private String exerciseKey;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String target;

    @Column(nullable = false)
    private Integer sets;

    @Column(nullable = false)
    private Integer reps;

    @Column(nullable = false)
    private String unit;

    @Column(length = 512)
    private String cue;

    /**
     * The key this slot held before the user swapped it, or null. Without it a
     * swap is indistinguishable from never having been offered the original —
     * which is exactly the signal worth having about an exercise everyone
     * replaces.
     */
    @Column
    private String replacedFrom;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long v) { this.sessionId = v; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer v) { this.position = v; }
    public String getExerciseKey() { return exerciseKey; }
    public void setExerciseKey(String v) { this.exerciseKey = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getTarget() { return target; }
    public void setTarget(String v) { this.target = v; }
    public Integer getSets() { return sets; }
    public void setSets(Integer v) { this.sets = v; }
    public Integer getReps() { return reps; }
    public void setReps(Integer v) { this.reps = v; }
    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }
    public String getCue() { return cue; }
    public void setCue(String v) { this.cue = v; }
    public String getReplacedFrom() { return replacedFrom; }
    public void setReplacedFrom(String v) { this.replacedFrom = v; }
}
