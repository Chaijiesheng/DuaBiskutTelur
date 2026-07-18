package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A single weigh-in. Logged whenever the user wants — history is week-bucketed and averaged on read. */
@Entity
@Table(name = "weight_entry")
public class WeightEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Double weightKg;

    @Column(nullable = false)
    private Instant loggedAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public Double getWeightKg() { return weightKg; }
    public void setWeightKg(Double v) { this.weightKg = v; }
    public Instant getLoggedAt() { return loggedAt; }
    public void setLoggedAt(Instant v) { this.loggedAt = v; }
}
