package com.duabiskuttelur.model;

import java.util.List;

/**
 * The Workouts tab inside the existing History screen.
 *
 * <p>Deliberately <em>not</em> served by {@code /api/workout/today}, even though
 * that endpoint already returns a week strip. {@code today} generates today's
 * session when there isn't one, and opening History to look at last week must
 * not have the side effect of planning a workout for a day the user never asked
 * about. Read-only questions get read-only endpoints.
 *
 * @param entries most recent first
 * @param week    Monday-to-Sunday minutes, for the bar chart above the list
 */
public record WorkoutHistoryResponse(List<Entry> entries, List<DayMinutes> week, int weekMinutes) {

    /**
     * One past session.
     *
     * @param status       "completed", "skipped", "in_progress" or "planned" —
     *                     the list shows all of them, because a skipped day is
     *                     part of an honest record rather than something to hide
     * @param completedSets what was actually logged, against {@code totalSets}
     *                      planned. A session abandoned after two sets should not
     *                      read the same as one finished.
     */
    public record Entry(
            long id,
            String date,
            String title,
            String focus,
            String level,
            int minutes,
            String status,
            int completedSets,
            int totalSets
    ) {
    }

    /** One bar. {@code minutes} is 0 for a day with no completed session. */
    public record DayMinutes(String date, String label, int minutes) {
    }
}
