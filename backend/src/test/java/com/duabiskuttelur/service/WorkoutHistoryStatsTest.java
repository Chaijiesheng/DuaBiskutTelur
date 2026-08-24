package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WorkoutCompleteRequest;
import com.duabiskuttelur.model.WorkoutHistoryResponse;
import com.duabiskuttelur.model.WorkoutProfileRequest;
import com.duabiskuttelur.model.WorkoutSessionView;
import com.duabiskuttelur.model.WorkoutStatsResponse;
import com.duabiskuttelur.persistence.WorkoutProfileRepository;
import com.duabiskuttelur.persistence.WorkoutSessionEntity;
import com.duabiskuttelur.persistence.WorkoutSessionExerciseRepository;
import com.duabiskuttelur.persistence.WorkoutSessionRepository;
import com.duabiskuttelur.persistence.WorkoutSetLogRepository;
import com.duabiskuttelur.service.WorkoutCatalog.Equipment;
import com.duabiskuttelur.service.WorkoutCatalog.Level;
import com.duabiskuttelur.service.WorkoutVocabulary.Goal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two read-only screens: the Workouts tab inside History, and the workout
 * figures on the Analysis tab.
 *
 * <p>The property worth stating up front is that neither of them writes
 * anything. {@code today()} plans a session when there isn't one, and if these
 * had been built on top of it then opening History to look at last week would
 * have quietly created this morning's workout.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:workout-history-test;DB_CLOSE_DELAY=-1"
})
class WorkoutHistoryStatsTest {

    private static final long USER = 5001L;
    private static final long OTHER_USER = 5002L;

    // The read side under test, and the write side used only to arrange a
    // history for it to read. Two fields rather than one is the point of the
    // split: nothing this file asserts on can plan or write anything.
    @Autowired private WorkoutInsights insights;
    @Autowired private WorkoutService workouts;
    @Autowired private WorkoutProfileRepository profiles;
    @Autowired private WorkoutSessionRepository sessions;
    @Autowired private WorkoutSessionExerciseRepository exercises;
    @Autowired private WorkoutSetLogRepository setLogs;

    @BeforeEach
    void clean() {
        setLogs.deleteAll();
        exercises.deleteAll();
        sessions.deleteAll();
        profiles.deleteAll();
    }

    private static WorkoutProfileRequest onboarding() {
        return new WorkoutProfileRequest("lose_weight", "beginner", 3, 30, List.of("none"), List.of());
    }

    /** A completed session on a past date, written directly so the date can be chosen. */
    private WorkoutSessionEntity completedOn(long userId, LocalDate date, int minutes) {
        WorkoutSessionEntity e = new WorkoutSessionEntity();
        e.setUserId(userId);
        e.setSessionDate(date);
        e.setTitle("Full Body");
        e.setFocus("full_body");
        e.setMinutes(30);
        e.setActualMinutes(minutes);
        e.setLevel("beginner");
        e.setStatus("completed");
        e.setCreatedAt(java.time.Instant.now());
        return sessions.save(e);
    }

    // ------------------------------------------------------------- history

    /**
     * The reason these endpoints exist separately at all. If History were built
     * on {@code today()}, this assertion would fail with one session in the
     * database that nobody asked to be planned.
     */
    @Test
    void readingHistoryNeverPlansAWorkout() {
        workouts.saveProfile(USER, onboarding());
        assertEquals(0, sessions.findAll().size(), "onboarding alone must not plan anything");

        insights.history(USER);
        insights.stats(USER);

        assertEquals(0, sessions.findAll().size(),
                "reading history or stats created a session — opening a past-tense screen "
                        + "must not plan today's workout");
    }

    // --------------------------------------------------------------- glance

    /**
     * The strictest version of the same rule. This one runs on the app's home
     * screen for everybody, so planning here would fire a Gemini call for the
     * coach note on every user's first open of the day.
     */
    @Test
    void theSnapRowNeverPlansAWorkoutEither() {
        workouts.saveProfile(USER, onboarding());

        insights.glance(USER);

        assertEquals(0, sessions.findAll().size(),
                "the Snap tab's Today row planned a session — that is a model call "
                        + "per user per app open, for a sentence two taps away");
    }

    @Test
    void theSnapRowSaysNothingBeforeOnboarding() {
        var glance = insights.glance(USER);

        assertFalse(glance.hasProfile());
        assertEquals(null, glance.session());
    }

    /**
     * With no stored session, "you have not opened the Workout tab yet" and
     * "today is a rest day" are the same absence in the database. Without
     * {@code trainingDay} the row would either nag on a rest day or let a
     * training day pass in silence.
     */
    @Test
    void theSnapRowDistinguishesAnUnopenedTrainingDayFromARestDay() {
        workouts.saveProfile(USER, new WorkoutProfileRequest(
                "maintain", "beginner", 7, 30, List.of("none"), List.of()));

        assertTrue(insights.glance(USER).trainingDay(),
                "training every day should be a training day whatever today is");
        assertEquals(null, insights.glance(USER).session(), "nothing has been planned yet");
    }

    /**
     * The rotation itself, on fixed dates rather than on "today".
     *
     * <p>Asserted here rather than through {@code glance} because the answer
     * depends on the day of the week, and a test that reads the clock would pass
     * six days out of seven.
     */
    @Test
    void theRotationSpreadsTrainingDaysAcrossTheWeek() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        WorkoutProfile threeDays = new WorkoutProfile(
                Goal.MAINTAIN, Level.BEGINNER, 3, 30, Set.of(Equipment.NONE), Set.of());
        WorkoutProfile everyDay = new WorkoutProfile(
                Goal.MAINTAIN, Level.BEGINNER, 7, 30, Set.of(Equipment.NONE), Set.of());

        int trainingDays = 0;
        for (int i = 0; i < 7; i++) {
            if (WorkoutCalendar.isTrainingDay(monday.plusDays(i), threeDays)) {
                trainingDays++;
            }
            assertTrue(WorkoutCalendar.isTrainingDay(monday.plusDays(i), everyDay),
                    "a 7-day plan called " + monday.plusDays(i).getDayOfWeek() + " a rest day");
        }

        assertEquals(3, trainingDays, "a 3-day plan should mark exactly three days of the week");
        assertTrue(WorkoutCalendar.isTrainingDay(monday, threeDays),
                "the week should start with a training day rather than a rest day");
    }

    @Test
    void theSnapRowReportsAnExistingSessionAndItsProgress() {
        workouts.saveProfile(USER, onboarding());
        WorkoutSessionView session = workouts.today(USER, "en").session();
        workouts.logSet(USER, session.id(), 0, 0, true);

        var glance = insights.glance(USER);

        assertTrue(glance.hasProfile());
        assertEquals(session.id(), glance.session().id());
        // The session's own title, not a hardcoded one: which focus today lands
        // on is a function of the date, so asserting "Full Body" here passed on
        // one day in three and failed on the other two.
        assertEquals(session.title(), glance.session().title());
        assertFalse(glance.session().title().isBlank());
        assertEquals("in_progress", glance.session().status());
        assertEquals(1, glance.session().completedSets());
        assertEquals(session.totalSets(), glance.session().totalSets());
    }

    @Test
    void theSnapRowNeverLeaksAnotherUsersSession() {
        workouts.saveProfile(OTHER_USER, onboarding());
        workouts.today(OTHER_USER, "en");
        workouts.saveProfile(USER, onboarding());

        assertEquals(null, insights.glance(USER).session(),
                "one user's Today row showed another user's session");
    }

    @Test
    void historyIsEmptyButWellFormedForSomeoneWhoHasNeverTrained() {
        WorkoutHistoryResponse response = insights.history(USER);

        assertTrue(response.entries().isEmpty());
        assertEquals(7, response.week().size(), "the bar chart is always seven columns");
        assertEquals(0, response.weekMinutes());
    }

    @Test
    void historyListsSessionsNewestFirst() {
        LocalDate today = LocalDate.now();
        completedOn(USER, today.minusDays(4), 25);
        completedOn(USER, today.minusDays(1), 31);
        completedOn(USER, today.minusDays(9), 28);

        List<String> dates = insights.history(USER).entries().stream()
                .map(WorkoutHistoryResponse.Entry::date).toList();

        assertEquals(List.of(today.minusDays(1).toString(), today.minusDays(4).toString(),
                today.minusDays(9).toString()), dates);
    }

    /** A skipped day belongs in an honest record; hiding it would flatter the list. */
    @Test
    void historyKeepsSkippedSessionsRatherThanHidingThem() {
        workouts.saveProfile(USER, onboarding());
        long id = workouts.today(USER, "en").session().id();
        workouts.setSkipped(USER, id, true);

        List<WorkoutHistoryResponse.Entry> entries = insights.history(USER).entries();

        assertEquals(1, entries.size());
        assertEquals("skipped", entries.get(0).status());
    }

    /**
     * A session abandoned after two sets must not read the same as one finished,
     * so the list carries what was logged against what was planned.
     */
    @Test
    void historyShowsHowMuchOfEachSessionWasActuallyDone() {
        workouts.saveProfile(USER, onboarding());
        WorkoutSessionView session = workouts.today(USER, "en").session();
        workouts.logSet(USER, session.id(), 0, 0, true);
        workouts.logSet(USER, session.id(), 0, 1, true);

        WorkoutHistoryResponse.Entry entry = insights.history(USER).entries().get(0);

        assertEquals(2, entry.completedSets());
        assertEquals(session.totalSets(), entry.totalSets());
        assertTrue(entry.totalSets() > 2, "the fixture needs an unfinished session to be meaningful");
    }

    /** Minutes trained is what it took, not what it was planned to take. */
    @Test
    void theWeeklyBarsUseRealDurationsAndOnlyCountCompletedDays() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        completedOn(USER, monday, 22);
        // A planned-but-not-done day contributes nothing.
        workouts.saveProfile(USER, onboarding());
        workouts.today(USER, "en");

        WorkoutHistoryResponse response = insights.history(USER);
        int mondayMinutes = response.week().stream()
                .filter(d -> d.date().equals(monday.toString()))
                .mapToInt(WorkoutHistoryResponse.DayMinutes::minutes).sum();

        assertEquals(22, mondayMinutes);
        assertEquals(22, response.weekMinutes(),
                "an unfinished session added minutes to the week");
    }

    /**
     * Anchored to this week's Monday rather than to "yesterday". The week strip
     * runs Monday to Sunday, so a relative date would put the fixture in last
     * week whenever this ran on a Monday — a test that passes six days out of
     * seven is worse than no test.
     */
    @Test
    void historyNeverLeaksAnotherUsersSessions() {
        LocalDate today = LocalDate.now();
        LocalDate thisWeek = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        completedOn(USER, thisWeek, 30);
        completedOn(OTHER_USER, thisWeek, 45);

        assertEquals(1, insights.history(USER).entries().size());
        assertEquals(30, insights.history(USER).weekMinutes());
        assertEquals(45, insights.history(OTHER_USER).weekMinutes());
    }

    // --------------------------------------------------------------- stats

    @Test
    void statsSayNothingUsefulButAreWellFormedBeforeOnboarding() {
        WorkoutStatsResponse stats = insights.stats(USER);

        assertFalse(stats.hasProfile());
        assertEquals(0, stats.workoutsThisMonth());
        assertEquals(0, stats.consistencyPercent());
        assertEquals(0, stats.expectedThisMonth(), "no plan means nothing was expected");
        assertTrue(stats.progressions().isEmpty());
    }

    @Test
    void statsCountOnlyThisMonthsCompletedWork() {
        LocalDate today = LocalDate.now();
        workouts.saveProfile(USER, onboarding());
        completedOn(USER, today, 30);
        completedOn(USER, today.minusMonths(1).withDayOfMonth(15), 45);

        WorkoutStatsResponse stats = insights.stats(USER);

        assertEquals(1, stats.workoutsThisMonth(), "last month's session was counted as this month's");
        assertEquals(30, stats.minutesThisMonth());
    }

    /**
     * Consistency is measured against what the plan asked for, not against the
     * sessions that happen to exist. A session row only exists for a day the
     * user opened the tab, so "completed / rows in the database" would score
     * somebody who never opened the app at 100%.
     */
    @Test
    void consistencyIsMeasuredAgainstThePlanNotAgainstWhatWasGenerated() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        WorkoutProfile threeDays = new WorkoutProfile(
                Goal.MAINTAIN, Level.BEGINNER, 3, 30, Set.of(Equipment.NONE), Set.of());

        int expected = WorkoutCalendar.expectedSessionsThisMonth(threeDays, today);

        // Seventeen days at three a week is around seven or eight sessions —
        // never zero, which is what makes 0% mean something.
        assertTrue(expected >= 6 && expected <= 9,
                "17 days of a 3-day plan should expect roughly 7 sessions, got " + expected);
    }

    /** Only days up to today count — being at 50% on the 15th is not failing the month. */
    @Test
    void consistencyDoesNotCountDaysThatHaveNotHappenedYet() {
        WorkoutProfile everyDay = new WorkoutProfile(
                Goal.MAINTAIN, Level.BEGINNER, 7, 30, Set.of(Equipment.NONE), Set.of());

        assertEquals(3, WorkoutCalendar.expectedSessionsThisMonth(everyDay, LocalDate.of(2026, 8, 3)));
        assertEquals(31, WorkoutCalendar.expectedSessionsThisMonth(everyDay, LocalDate.of(2026, 8, 31)));
    }

    @Test
    void consistencyNeverExceedsOneHundredPercent() {
        LocalDate today = LocalDate.now();
        workouts.saveProfile(USER, new WorkoutProfileRequest(
                "maintain", "beginner", 1, 30, List.of("none"), List.of()));
        // Train far more than a once-a-week plan asked for.
        for (int i = 0; i < 20 && today.minusDays(i).getMonth() == today.getMonth(); i++) {
            completedOn(USER, today.minusDays(i), 30);
        }

        assertTrue(insights.stats(USER).consistencyPercent() <= 100,
                "over-delivering produced a figure above 100%");
    }

    @Test
    void bestStreakIsTheLongestRunOnRecordNotTheCurrentOne() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        List<WorkoutSessionEntity> completed = List.of(
                stub(today.minusDays(20)), stub(today.minusDays(19)), stub(today.minusDays(18)),
                stub(today.minusDays(17)),
                stub(today.minusDays(1)), stub(today));

        assertEquals(4, WorkoutCalendar.bestStreak(completed));
        assertEquals(2, WorkoutCalendar.streak(completed, today));
    }

    @Test
    void bestStreakIsZeroWithNothingCompleted() {
        assertEquals(0, WorkoutCalendar.bestStreak(List.of()));
    }

    /**
     * A skipped day is not a completed one, whichever list it arrives in.
     *
     * <p>bestStreak used to trust its caller to have filtered already, while
     * its sibling streak() filtered for itself. Moving the pair into
     * WorkoutCalendar put that inconsistency side by side, and it is the only
     * behaviour this refactor changed: an unfiltered list now gets the right
     * answer instead of a silently inflated one.
     */
    @Test
    void bestStreakIgnoresSessionsThatWereNotCompleted() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        List<WorkoutSessionEntity> mixed = List.of(
                stub(today.minusDays(3)), skipped(today.minusDays(2)), stub(today.minusDays(1)));

        assertEquals(1, WorkoutCalendar.bestStreak(mixed), "a skipped day bridged two runs into one");
    }

    /**
     * The read side is read-only by construction now, not by convention.
     *
     * <p>glance() must not plan a session: it backs the app's home screen, and
     * generating there would fire a Gemini call on every user's first open of
     * the day. history() must not either, or looking at last week's training
     * would create this morning's workout. Both used to be comments on a class
     * that held the planner and the coach one field access away. This is the
     * version that fails when somebody injects one back.
     */
    @Test
    void theReadSideHoldsNothingThatCouldWrite() {
        Set<String> held = java.util.Arrays.stream(WorkoutInsights.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(f -> f.getType().getSimpleName())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("WorkoutProfileRepository", "WorkoutSessionRepository",
                        "WorkoutSessionExerciseRepository", "WorkoutSetLogRepository"), held,
                "the read side picked up a collaborator that can plan, coach or record");
    }

    private static WorkoutSessionEntity skipped(LocalDate date) {
        WorkoutSessionEntity e = new WorkoutSessionEntity();
        e.setSessionDate(date);
        e.setStatus("skipped");
        return e;
    }

    private static WorkoutSessionEntity stub(LocalDate date) {
        WorkoutSessionEntity e = new WorkoutSessionEntity();
        e.setSessionDate(date);
        e.setStatus("completed");
        return e;
    }

    // -------------------------------------------------------- progressions

    /** One session is a starting point, not progress. */
    @Test
    void aSingleSessionShowsNoProgression() {
        workouts.saveProfile(USER, onboarding());
        long id = workouts.today(USER, "en").session().id();
        workouts.complete(USER, id, new WorkoutCompleteRequest("just_right", null, 30, "en"));

        assertTrue(insights.stats(USER).progressions().isEmpty());
    }

    /**
     * The dose going up is the claim, and it has to come from what was actually
     * prescribed in completed sessions — never from something the user typed.
     */
    @Test
    void aRisingDoseAcrossCompletedSessionsShowsAsProgress() {
        LocalDate today = LocalDate.now();
        WorkoutSessionEntity older = completedOn(USER, today.minusDays(7), 30);
        WorkoutSessionEntity newer = completedOn(USER, today.minusDays(1), 30);
        prescribe(older.getId(), "plank", "Plank", 2, 25, "sec");
        prescribe(newer.getId(), "plank", "Plank", 3, 30, "sec");

        List<WorkoutStatsResponse.Progression> progressions = insights.stats(USER).progressions();

        assertEquals(1, progressions.size());
        WorkoutStatsResponse.Progression plank = progressions.get(0);
        assertEquals("Plank", plank.name());
        assertEquals("2 × 25s", plank.from());
        assertEquals("3 × 30s", plank.to());
    }

    /**
     * A dose that dropped after a "too hard" rating is the system working, and
     * listing it under "getting stronger" would read as a rebuke.
     */
    @Test
    void aFallingDoseIsNotListedAsProgress() {
        LocalDate today = LocalDate.now();
        WorkoutSessionEntity older = completedOn(USER, today.minusDays(7), 30);
        WorkoutSessionEntity newer = completedOn(USER, today.minusDays(1), 30);
        prescribe(older.getId(), "push_up", "Push Up", 3, 10, "reps");
        prescribe(newer.getId(), "push_up", "Push Up", 2, 10, "reps");

        assertTrue(insights.stats(USER).progressions().isEmpty());
    }

    /** An unfinished session is not evidence of anything. */
    @Test
    void onlyCompletedSessionsCountTowardsProgress() {
        LocalDate today = LocalDate.now();
        WorkoutSessionEntity older = completedOn(USER, today.minusDays(7), 30);
        WorkoutSessionEntity abandoned = completedOn(USER, today.minusDays(1), 30);
        abandoned.setStatus("in_progress");
        sessions.save(abandoned);
        prescribe(older.getId(), "plank", "Plank", 2, 25, "sec");
        prescribe(abandoned.getId(), "plank", "Plank", 4, 40, "sec");

        assertTrue(insights.stats(USER).progressions().isEmpty());
    }

    private void prescribe(long sessionId, String key, String name, int sets, int reps, String unit) {
        com.duabiskuttelur.persistence.WorkoutSessionExerciseEntity row =
                new com.duabiskuttelur.persistence.WorkoutSessionExerciseEntity();
        row.setSessionId(sessionId);
        row.setPosition(0);
        row.setExerciseKey(key);
        row.setName(name);
        row.setTarget("Core");
        row.setSets(sets);
        row.setReps(reps);
        row.setUnit(unit);
        exercises.save(row);
    }
}
