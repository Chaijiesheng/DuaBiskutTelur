package com.duabiskuttelur.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** One row per user per calendar day — a running total, not an append-only log. */
@Entity
@Table(name = "water_entry")
public class WaterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private int totalMl;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate v) { this.date = v; }
    public int getTotalMl() { return totalMl; }
    public void setTotalMl(int v) { this.totalMl = v; }
}
