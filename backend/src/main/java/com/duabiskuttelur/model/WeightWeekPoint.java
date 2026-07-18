package com.duabiskuttelur.model;

/** One week's worth of weigh-ins, averaged. weekStart is an ISO-8601 date (Monday of that week). */
public record WeightWeekPoint(String weekStart, double avgWeightKg, int entryCount) {
}
