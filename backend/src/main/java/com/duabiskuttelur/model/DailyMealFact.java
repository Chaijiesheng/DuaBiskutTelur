package com.duabiskuttelur.model;

/**
 * One of today's meals, reduced to what the dashboard totals actually add up.
 *
 * <p>Exists for the same reason {@link RecentMealPoint} does, but on a hotter
 * path: {@code meal_analysis} carries {@code thumbnail} (a ~6 KB base64 data
 * URL) and {@code result_json} (~3 KB) in the row, and reading entities dragged
 * both through the buffer to sum three numbers. That read runs on every
 * dashboard load <em>and</em> on every analysis, since goal-aware feedback needs
 * to know how much budget is left for the day.
 *
 * <p>{@code protein} is nullable on purpose — it is the denormalized column
 * added in V2, and rows written before it exists have to fall back to parsing
 * {@code result_json}. Carrying the null through rather than defaulting it to
 * zero is what lets the caller tell "no protein logged" apart from "this row
 * predates the column", which are not the same number.
 */
public record DailyMealFact(Long id, int score, double calories, Double protein) {
}
