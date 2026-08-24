package com.duabiskuttelur.service;

import com.duabiskuttelur.persistence.WorkoutSessionEntity;
import com.duabiskuttelur.persistence.WorkoutSessionEntity.Status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What the rotation says a week should look like, and what the record says
 * actually happened.
 *
 * <p>Shared by the two halves of the workout feature because both need it and
 * neither owns it: {@link WorkoutService} asks whether today is a training day
 * to build the week strip, {@link WorkoutInsights} asks the same question to
 * score a month's consistency. A copy in each would be two answers to one
 * question, and the pair would drift the first time either was corrected.
 *
 * <p>Every function here is pure and takes its date as a parameter. That is why
 * there is no injected {@link java.time.Clock} anywhere in this feature -- the
 * date-sensitive decisions are all in this file, and they are tested by being
 * handed a date rather than by being told what day it is.
 */
final class WorkoutCalendar {

    private WorkoutCalendar() {
    }

    /**
     * "Today" in the user's own calendar.
     *
     * <p>The system default zone, not UTC. The container runs with
     * {@code TZ=Asia/Kuala_Lumpur}, and on UTC the day would roll over at 8am
     * local -- mid-morning of the day whose workout it is meant to end.
     * {@code WeightService} reads the clock the same way, for the same reason.
     */
    static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    /**
     * Whether a future day is a training day.
     *
     * <p>Spreads the week's sessions evenly rather than front-loading them:
     * three days a week becomes Monday, Wednesday, Friday, not Monday, Tuesday,
     * Wednesday and a four-day gap.
     */
    static boolean isTrainingDay(LocalDate day, WorkoutProfile profile) {
        int days = Math.max(1, Math.min(7, profile.daysPerWeek()));
        if (days >= 7) {
            return true;
        }
        int index = day.getDayOfWeek().getValue() - 1;
        return (index * days) / 7 != ((index - 1) * days) / 7 || index == 0;
    }

    /**
     * How many sessions the plan called for so far this month.
     *
     * <p>Counted from the rotation rather than from stored rows, which is the
     * only honest version: a session row exists only for a day the user actually
     * opened the tab, so "completed / sessions in the database" would score
     * somebody who never opened the app at 100%. This asks what they said they
     * would do and compares it to what they did.
     *
     * <p>Only days up to today count -- being at 50% on the 15th is not the same
     * failure as being at 50% on the 31st, and the tile would read as the latter.
     */
    static int expectedSessionsThisMonth(WorkoutProfile profile, LocalDate today) {
        LocalDate first = today.withDayOfMonth(1);
        int count = 0;
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            if (isTrainingDay(day, profile)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Consecutive days ending today (or yesterday) with a completed session.
     *
     * <p>Yesterday counts as the anchor so a streak does not appear broken all
     * morning before you have trained. Rest days do not extend it -- a streak
     * that survives arbitrary gaps is not measuring anything.
     */
    static int streak(List<WorkoutSessionEntity> recent, LocalDate today) {
        Set<LocalDate> done = completedDates(recent, new HashSet<>());
        LocalDate cursor = done.contains(today) ? today : today.minusDays(1);
        int count = 0;
        while (done.contains(cursor)) {
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    /** The longest run of consecutive completed days on record. */
    static int bestStreak(List<WorkoutSessionEntity> completed) {
        Set<LocalDate> done = completedDates(completed, new TreeSet<>());
        int best = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate day : done) {
            run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
            previous = day;
            best = Math.max(best, run);
        }
        return best;
    }

    /**
     * The dates of the completed sessions in a list.
     *
     * <p>Both callers filter by status rather than trusting theirs to be
     * pre-filtered: {@link #streak} is handed a window that includes skipped
     * and planned days, and a skipped day extending a streak would make the
     * number mean nothing.
     */
    private static Set<LocalDate> completedDates(List<WorkoutSessionEntity> sessions, Set<LocalDate> into) {
        return sessions.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .map(WorkoutSessionEntity::getSessionDate)
                .collect(Collectors.toCollection(() -> into));
    }

}
