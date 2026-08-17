package com.duabiskuttelur.model;

import java.util.List;

/**
 * Everything the Workout tab's dashboard draws, in one round trip.
 *
 * <p>One request rather than four, because this screen is the tab's landing
 * page: splitting the plan card, the coach note, the week strip and the stats
 * into separate calls would make the most-visited screen in the feature the
 * slowest, and would let it render in four stages on a bad connection.
 *
 * @param hasProfile  false before onboarding — the client shows the empty state
 *                    and never looks at {@code session}, which is null
 * @param coachSource "ai" or "rules". The client shows a plainer card for
 *                    "rules" rather than passing a template off as coaching.
 */
public record WorkoutTodayResponse(
        boolean hasProfile,
        WorkoutSessionView session,
        WorkoutCoachNote coach,
        String coachSource,
        List<WeekDay> week,
        Stats stats
) {
    /**
     * One column of the week strip.
     *
     * @param state "done", "today", "planned" or "rest". Derived on the server
     *              because "is today a training day" depends on the rotation,
     *              which the client does not and should not know.
     */
    public record WeekDay(String date, String label, String state) {
    }

    /**
     * The three tiles under the week strip.
     *
     * @param weightKg the latest weigh-in, or null if there has never been one —
     *                 this comes from the existing weight feature, not from
     *                 anything the workout tab collects
     */
    public record Stats(Double weightKg, int workoutsThisMonth, int streakDays) {
    }
}
