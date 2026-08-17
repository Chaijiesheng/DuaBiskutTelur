package com.duabiskuttelur.model;

import java.util.List;

/**
 * Workout figures for the existing Analysis tab.
 *
 * <p>They extend that tab rather than starting a Progress page of their own, so
 * nutrition and activity are read together — which is the whole premise of
 * putting a workout feature inside a meal tracker.
 *
 * @param consistencyPercent completed against {@link #expectedThisMonth}
 * @param bestStreakDays     the longest streak on record, so a current streak of
 *                           5 means something relative to what this person has
 *                           actually managed before
 * @param progressions       exercises whose prescribed dose has gone up
 */
public record WorkoutStatsResponse(
        boolean hasProfile,
        int workoutsThisMonth,
        int minutesThisMonth,
        int consistencyPercent,
        int expectedThisMonth,
        int streakDays,
        int bestStreakDays,
        List<Progression> progressions
) {
    /**
     * One exercise getting harder over time.
     *
     * <p>Both doses are what was <em>prescribed</em> in a completed session, not
     * a personal best the user claimed. The catalogue sets the starting dose and
     * the planner raises it, so this is the app showing its own working.
     *
     * @param from earliest completed prescription, e.g. "3 × 12"
     * @param to   most recent
     */
    public record Progression(String key, String name, String from, String to) {
    }
}
