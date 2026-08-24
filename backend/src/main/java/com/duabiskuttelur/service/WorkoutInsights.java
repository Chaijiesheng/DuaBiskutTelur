package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WorkoutGlanceResponse;
import com.duabiskuttelur.model.WorkoutHistoryResponse;
import com.duabiskuttelur.model.WorkoutStatsResponse;
import com.duabiskuttelur.persistence.WorkoutProfileRepository;
import com.duabiskuttelur.persistence.WorkoutSessionEntity;
import com.duabiskuttelur.persistence.WorkoutSessionEntity.Status;
import com.duabiskuttelur.persistence.WorkoutSessionExerciseEntity;
import com.duabiskuttelur.persistence.WorkoutSessionExerciseRepository;
import com.duabiskuttelur.persistence.WorkoutSessionRepository;
import com.duabiskuttelur.persistence.WorkoutSetLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Everything the workout feature can say about the past.
 *
 * <p>Split out of {@link WorkoutService} because the two halves want different
 * things. This one holds four repositories and reads them; the other holds the
 * planner, the catalogue, the coach and three counters, and writes. Together
 * they meant every read path had a live reference to the model client and to
 * session generation -- the wrong thing to have within reach on screens whose
 * whole job is to look at what already happened.
 *
 * <p>Two comments in the original said exactly that, and both were
 * load-bearing: {@link #glance} must not plan a session, because it backs the
 * app's home screen and generating there would fire a Gemini call on every
 * user's first open of the day; {@link #history} must not either, because
 * looking at last week's training must not create this morning's workout as a
 * side effect. Neither needs to be a rule anybody remembers now -- there is
 * nothing in this class that could plan anything.
 */
@Service
public class WorkoutInsights {

    /**
     * The window the History and Analysis tabs read.
     *
     * <p>Wider than the dashboard's, because those two screens answer questions
     * about the past rather than about today: a 35-day window would silently cap
     * "best streak" and the strength progressions at five weeks, and a personal
     * best that quietly expires is worse than none.
     *
     * <p>Bounded rather than unbounded on purpose -- this is a per-user list read
     * on a tab switch, and "everything ever" is a query whose cost grows with
     * how loyal the user is.
     */
    private static final int HISTORY_WINDOW_DAYS = 120;

    private final WorkoutProfileRepository profiles;
    private final WorkoutSessionRepository sessions;
    private final WorkoutSessionExerciseRepository exercises;
    private final WorkoutSetLogRepository setLogs;

    public WorkoutInsights(WorkoutProfileRepository profiles, WorkoutSessionRepository sessions,
                           WorkoutSessionExerciseRepository exercises, WorkoutSetLogRepository setLogs) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.exercises = exercises;
        this.setLogs = setLogs;
    }

    /**
     * Read straight from the repository rather than through WorkoutService.
     *
     * <p>One line of duplication, against a dependency that would drag the
     * planner, the catalogue and the coach back in through the side door and
     * undo the split.
     */
    private Optional<WorkoutProfile> profile(long userId) {
        return profiles.findByUserId(userId).map(WorkoutProfile::from);
    }

    private List<WorkoutSessionEntity> sessionsSince(long userId, LocalDate today, int days) {
        return sessions.findByUserIdAndSessionDateBetweenOrderBySessionDateAsc(
                userId, today.minusDays(days), today);
    }

    /**
     * One line about today, for the Today card on the Snap tab.
     *
     * <p>Reads the stored session and stops. It must never plan one: this is the
     * app's home screen, so generating here would fire a Gemini call for the
     * coach note on every user's first open of the day — for a sentence that
     * lives two taps away and that most of them will never see.
     */
    public WorkoutGlanceResponse glance(long userId) {
        Optional<WorkoutProfile> maybeProfile = profile(userId);
        if (maybeProfile.isEmpty()) {
            return new WorkoutGlanceResponse(false, false, null);
        }
        LocalDate today = WorkoutCalendar.today();
        boolean trainingDay = WorkoutCalendar.isTrainingDay(today, maybeProfile.get());

        return sessions.findByUserIdAndSessionDate(userId, today)
                .map(s -> {
                    List<WorkoutSessionExerciseEntity> rows =
                            exercises.findBySessionIdOrderByPositionAsc(s.getId());
                    return new WorkoutGlanceResponse(true, trainingDay,
                            new WorkoutGlanceResponse.Session(
                                    s.getId(), s.getTitle(), effectiveMinutes(s), s.getStatus(),
                                    setLogs.findBySessionId(s.getId()).size(),
                                    rows.stream().mapToInt(WorkoutSessionExerciseEntity::getSets).sum()));
                })
                .orElseGet(() -> new WorkoutGlanceResponse(true, trainingDay, null));
    }

    /**
     * The Workouts tab inside the History screen.
     *
     * <p>Read-only, and separate from {@link #today} on purpose: that method
     * plans a session when there isn't one, and looking at last week's training
     * must not create this morning's workout as a side effect.
     */
    public WorkoutHistoryResponse history(long userId) {
        LocalDate today = WorkoutCalendar.today();
        List<WorkoutSessionEntity> recent = sessionsSince(userId, today, HISTORY_WINDOW_DAYS);
        Map<Long, Integer> logged = setCountsFor(recent);
        Map<Long, Integer> plannedBySession = plannedSetCountsFor(recent);

        List<WorkoutHistoryResponse.Entry> entries = recent.stream()
                .sorted(Comparator.comparing(WorkoutSessionEntity::getSessionDate).reversed())
                .map(s -> new WorkoutHistoryResponse.Entry(
                        s.getId(), s.getSessionDate().toString(), s.getTitle(), s.getFocus(),
                        s.getLevel(), effectiveMinutes(s), s.getStatus(),
                        logged.getOrDefault(s.getId(), 0),
                        plannedBySession.getOrDefault(s.getId(), 0)))
                .toList();

        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        Map<LocalDate, Integer> minutesByDate = recent.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .collect(Collectors.toMap(WorkoutSessionEntity::getSessionDate,
                        WorkoutInsights::effectiveMinutes, Integer::sum));

        List<WorkoutHistoryResponse.DayMinutes> week = new ArrayList<>();
        int weekMinutes = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            int minutes = minutesByDate.getOrDefault(day, 0);
            weekMinutes += minutes;
            week.add(new WorkoutHistoryResponse.DayMinutes(day.toString(),
                    day.getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.ENGLISH), minutes));
        }
        return new WorkoutHistoryResponse(entries, week, weekMinutes);
    }

    /** What a session actually took, falling back to what it was planned to take. */
    private static int effectiveMinutes(WorkoutSessionEntity s) {
        return s.getActualMinutes() != null && s.getActualMinutes() > 0
                ? s.getActualMinutes()
                : s.getMinutes();
    }

    private Map<Long, Integer> setCountsFor(List<WorkoutSessionEntity> sessions) {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (WorkoutSessionEntity s : sessions) {
            counts.put(s.getId(), setLogs.findBySessionId(s.getId()).size());
        }
        return counts;
    }

    private Map<Long, Integer> plannedSetCountsFor(List<WorkoutSessionEntity> sessions) {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (WorkoutSessionEntity s : sessions) {
            counts.put(s.getId(), exercises.findBySessionIdOrderByPositionAsc(s.getId()).stream()
                    .mapToInt(WorkoutSessionExerciseEntity::getSets).sum());
        }
        return counts;
    }

    /** Workout figures for the Analysis tab. */
    public WorkoutStatsResponse stats(long userId) {
        Optional<WorkoutProfile> maybeProfile = profile(userId);
        LocalDate today = WorkoutCalendar.today();
        List<WorkoutSessionEntity> recent = sessionsSince(userId, today, HISTORY_WINDOW_DAYS);
        List<WorkoutSessionEntity> completed = recent.stream()
                .filter(s -> Status.COMPLETED.tag().equals(s.getStatus()))
                .toList();
        List<WorkoutSessionEntity> thisMonth = completed.stream()
                .filter(s -> s.getSessionDate().getMonth() == today.getMonth()
                        && s.getSessionDate().getYear() == today.getYear())
                .toList();

        int expected = maybeProfile.map(p -> WorkoutCalendar.expectedSessionsThisMonth(p, today)).orElse(0);
        int consistency = expected == 0 ? 0
                : (int) Math.round(100.0 * Math.min(thisMonth.size(), expected) / expected);

        return new WorkoutStatsResponse(
                maybeProfile.isPresent(),
                thisMonth.size(),
                thisMonth.stream().mapToInt(WorkoutInsights::effectiveMinutes).sum(),
                consistency,
                expected,
                WorkoutCalendar.streak(recent, today),
                WorkoutCalendar.bestStreak(completed),
                progressions(completed));
    }

    /**
     * Exercises whose prescribed dose has gone up.
     *
     * <p>Both ends are prescriptions from completed sessions, never a claim the
     * user typed — the catalogue sets the starting dose and the planner raises
     * it, so this is the app showing its own working rather than flattering
     * anybody. Only increases are listed: a dose that dropped after a "too hard"
     * rating is the system working correctly, and putting it under "getting
     * stronger" would read as a rebuke.
     */
    private List<WorkoutStatsResponse.Progression> progressions(List<WorkoutSessionEntity> completed) {
        if (completed.isEmpty()) {
            return List.of();
        }
        Map<Long, LocalDate> dateBySession = completed.stream()
                .collect(Collectors.toMap(WorkoutSessionEntity::getId, WorkoutSessionEntity::getSessionDate));
        List<WorkoutSessionExerciseEntity> rows =
                exercises.findBySessionIdIn(List.copyOf(dateBySession.keySet()));

        record Dosed(LocalDate date, WorkoutSessionExerciseEntity row) {}
        Map<String, List<Dosed>> byKey = rows.stream()
                .map(r -> new Dosed(dateBySession.get(r.getSessionId()), r))
                .filter(d -> d.date() != null)
                .collect(Collectors.groupingBy(d -> d.row().getExerciseKey()));

        List<WorkoutStatsResponse.Progression> out = new ArrayList<>();
        byKey.forEach((key, list) -> {
            if (list.size() < 2) {
                return;
            }
            List<Dosed> sorted = list.stream().sorted(Comparator.comparing(Dosed::date)).toList();
            WorkoutSessionExerciseEntity first = sorted.get(0).row();
            WorkoutSessionExerciseEntity last = sorted.get(sorted.size() - 1).row();
            if (volumeOf(last) <= volumeOf(first)) {
                return;
            }
            out.add(new WorkoutStatsResponse.Progression(
                    key, last.getName(), describeDose(first), describeDose(last)));
        });
        out.sort(Comparator.comparing(WorkoutStatsResponse.Progression::name));
        return out;
    }

    private static int volumeOf(WorkoutSessionExerciseEntity row) {
        return row.getSets() * row.getReps();
    }

    /** "3 × 12", or "3 × 30s" where the dose is a duration. */
    private static String describeDose(WorkoutSessionExerciseEntity row) {
        String suffix = "sec".equals(row.getUnit()) ? "s" : "";
        return row.getSets() + " × " + row.getReps() + suffix;
    }
}
