package com.duabiskuttelur.model;

import java.time.Instant;

/**
 * One meal reduced to what a trend needs: when, and how many calories.
 *
 * <p>Exists because the weekly chart and the Analysis tab's stats were computed
 * from {@code /api/history}, which is capped at fifty entries. That cap is a
 * sensible page size for a list, but it silently truncated the arithmetic — a
 * user logging eight meals a day exhausts fifty in six days, so their "total
 * this week" and "average daily" were quietly wrong, with nothing on screen to
 * suggest it. A window is bounded by time rather than by row count, so it can
 * be complete; carrying three fields instead of whole rows is what makes
 * dropping the cap affordable.
 */
public record RecentMealPoint(Long id, Instant createdAt, double calories) {
}
