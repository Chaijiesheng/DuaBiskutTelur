package com.duabiskuttelur.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A weekly or monthly report.
 *
 * <p>Every number in here is computed in Java from stored columns. The model's
 * only contribution is {@code narrative}, and {@code narrativeSource} says
 * which path produced it, exactly as {@code coach_source} does for workouts --
 * so a provider outage costs a sentence rather than a report, and the UI can be
 * honest about which it is showing.
 *
 * <p>{@code previous} is null when the preceding window holds too little to
 * compare against. A delta measured against two logged days is noise wearing
 * the costume of a trend, and the honest move is to show no delta at all.
 */
public record TrendReportResponse(
        String period,
        LocalDate from,
        LocalDate to,
        int daysInWindow,
        int calorieBudget,
        boolean enoughData,
        List<TrendDay> days,
        TrendTotals totals,
        TrendTotals previous,
        Map<String, Integer> gradeMix,
        String bestDayGrade,
        LocalDate bestDayDate,
        String narrative,
        String narrativeSource
) {
}
