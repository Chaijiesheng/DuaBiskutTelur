package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WorkoutAlternative;
import com.duabiskuttelur.model.WorkoutCompleteRequest;
import com.duabiskuttelur.model.WorkoutCompletionResponse;
import com.duabiskuttelur.model.WorkoutProfileRequest;
import com.duabiskuttelur.model.WorkoutSessionView;
import com.duabiskuttelur.model.WorkoutTodayResponse;
import com.duabiskuttelur.persistence.WorkoutProfileRepository;
import com.duabiskuttelur.persistence.WorkoutSessionEntity;
import com.duabiskuttelur.persistence.WorkoutSessionExerciseRepository;
import com.duabiskuttelur.persistence.WorkoutSessionRepository;
import com.duabiskuttelur.persistence.WorkoutSetLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workout feature end to end against a real database.
 *
 * <p>Against H2 rather than mocks on purpose: the two properties that matter
 * most here — a day's session being written exactly once, and a set log being
 * idempotent — are enforced by unique constraints, so a mocked repository would
 * happily pass a test of behaviour the schema is actually providing.
 *
 * <p>No API key is configured, so {@code WorkoutCoach} takes its rule-based path
 * throughout and nothing reaches the network.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:workout-service-test;DB_CLOSE_DELAY=-1"
})
class WorkoutServiceTest {

    private static final long USER = 4001L;
    private static final long OTHER_USER = 4002L;

    @Autowired private WorkoutService service;
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
        return new WorkoutProfileRequest("lose_weight", "beginner", 3, 30,
                List.of("none", "dumbbells"), List.of("strength"));
    }

    private WorkoutSessionView todaysSession(long userId) {
        return service.today(userId, "en").session();
    }

    // ------------------------------------------------------------- profile

    @Test
    void beforeOnboardingThereIsNoPlanAndNoCoachNote() {
        WorkoutTodayResponse response = service.today(USER, "en");

        assertFalse(response.hasProfile());
        assertNull(response.session(), "a plan was built for someone who never answered the questions");
        assertNull(response.coach());
        assertNotNull(response.stats(), "the empty state still shows the stat tiles");
    }

    @Test
    void savingTheAnswersMakesAPlanPossible() {
        service.saveProfile(USER, onboarding());

        WorkoutTodayResponse response = service.today(USER, "en");

        assertTrue(response.hasProfile());
        assertNotNull(response.session());
        assertFalse(response.session().exercises().isEmpty());
        assertEquals(7, response.week().size(), "the week strip must always be seven columns");
        assertEquals("rules", response.coachSource(), "no API key is configured in this test");
        assertFalse(response.coach().summary().isBlank());
    }

    @Test
    void anAnswerOutsideTheVocabularyIsRejectedRatherThanDefaulted() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.saveProfile(USER, new WorkoutProfileRequest(
                        "get_swole", "beginner", 3, 30, List.of("none"), List.of())));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(profiles.findByUserId(USER).isEmpty(), "a rejected profile was stored anyway");
    }

    @Test
    void anImpossibleFrequencyOrDurationIsRejected() {
        assertThrows(ResponseStatusException.class, () -> service.saveProfile(USER,
                new WorkoutProfileRequest("maintain", "beginner", 0, 30, List.of("none"), List.of())));
        assertThrows(ResponseStatusException.class, () -> service.saveProfile(USER,
                new WorkoutProfileRequest("maintain", "beginner", 9, 30, List.of("none"), List.of())));
        assertThrows(ResponseStatusException.class, () -> service.saveProfile(USER,
                new WorkoutProfileRequest("maintain", "beginner", 3, 5, List.of("none"), List.of())));
    }

    /** The last onboarding step is skippable, so no preferences must be valid input. */
    @Test
    void preferencesAreOptional() {
        service.saveProfile(USER, new WorkoutProfileRequest(
                "maintain", "beginner", 3, 30, List.of("none"), null));

        assertTrue(service.profile(USER).isPresent());
        assertTrue(service.profile(USER).get().preferences().isEmpty());
    }

    /**
     * Re-answering the questions must not leave today's plan describing the old
     * answers — the user would change "45 minutes" to "15" and be shown the same
     * 45-minute session.
     */
    @Test
    void changingTheAnswersRebuildsTodaysUnstartedPlan() {
        service.saveProfile(USER, new WorkoutProfileRequest(
                "maintain", "advanced", 4, 60, List.of("none", "gym"), List.of()));
        WorkoutSessionView before = todaysSession(USER);

        service.saveProfile(USER, new WorkoutProfileRequest(
                "maintain", "beginner", 3, 15, List.of("none"), List.of()));
        WorkoutSessionView after = todaysSession(USER);

        assertTrue(after.minutes() <= 15,
                "the plan still runs to " + after.minutes() + " minutes after the user said 15");
        assertTrue(after.totalSets() < before.totalSets(),
                "an advanced 60-minute plan survived a switch to beginner and 15 minutes");
    }

    /** But a session already under way is left alone — rewriting it mid-set is worse than stale. */
    @Test
    void changingTheAnswersLeavesAStartedSessionAlone() {
        service.saveProfile(USER, onboarding());
        WorkoutSessionView started = todaysSession(USER);
        service.start(USER, started.id());

        service.saveProfile(USER, new WorkoutProfileRequest(
                "maintain", "beginner", 3, 15, List.of("none"), List.of()));

        assertEquals(started.id(), todaysSession(USER).id(),
                "the session the user was part-way through was replaced under them");
    }

    // -------------------------------------------------------------- today

    /**
     * The rule the whole feature rests on: today's workout is written once, so it
     * cannot be re-rolled by reopening the tab.
     */
    @Test
    void todaysSessionIsGeneratedOnceAndThenReturned() {
        service.saveProfile(USER, onboarding());

        WorkoutSessionView first = todaysSession(USER);
        WorkoutSessionView second = todaysSession(USER);
        WorkoutSessionView third = todaysSession(USER);

        assertEquals(first.id(), second.id());
        assertEquals(first.id(), third.id());
        assertEquals(1, sessions.findAll().size(), "more than one plan exists for today");
        assertEquals(first.exercises().size(),
                exercises.findBySessionIdOrderByPositionAsc(first.id()).size());
    }

    @Test
    void twoUsersGetTheirOwnSessions() {
        service.saveProfile(USER, onboarding());
        service.saveProfile(OTHER_USER, onboarding());

        assertFalse(todaysSession(USER).id() == todaysSession(OTHER_USER).id());
        assertEquals(2, sessions.findAll().size());
    }

    // ---------------------------------------------------------- set logging

    /**
     * The property that makes the offline queue safe. The client replays whatever
     * it has without knowing what landed, so logging the same set twice must
     * count once.
     */
    @Test
    void loggingTheSameSetTwiceCountsItOnce() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();

        service.logSet(USER, id, 0, 0, true);
        service.logSet(USER, id, 0, 0, true);
        WorkoutSessionView view = service.logSet(USER, id, 0, 0, true);

        assertEquals(1, view.completedSets());
        assertEquals(List.of(0), view.exercises().get(0).completedSets());
        assertEquals(1, setLogs.findBySessionId(id).size());
    }

    @Test
    void unloggingASetRemovesItAndIsAlsoIdempotent() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();
        service.logSet(USER, id, 0, 0, true);

        service.logSet(USER, id, 0, 0, false);
        WorkoutSessionView view = service.logSet(USER, id, 0, 0, false);

        assertEquals(0, view.completedSets());
        assertTrue(setLogs.findBySessionId(id).isEmpty());
    }

    @Test
    void completedSetsAreReportedInOrderPerExercise() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();

        service.logSet(USER, id, 0, 1, true);
        WorkoutSessionView view = service.logSet(USER, id, 0, 0, true);

        assertEquals(List.of(0, 1), view.exercises().get(0).completedSets(),
                "set indexes came back out of order, so the UI would tick the wrong circles");
    }

    @Test
    void aSetOutsideTheExercisesRangeIsRejected() {
        service.saveProfile(USER, onboarding());
        WorkoutSessionView session = todaysSession(USER);
        int sets = session.exercises().get(0).sets();

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.logSet(USER, session.id(), 0, sets, true)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.logSet(USER, session.id(), 0, -1, true)).getStatusCode());
    }

    @Test
    void anUnknownExercisePositionIsRejected() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.logSet(USER, id, 99, 0, true)).getStatusCode());
    }

    /** Logging a set is starting the session, however the client got there. */
    @Test
    void loggingASetStartsASessionThatWasNeverExplicitlyStarted() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();
        assertEquals("planned", todaysSession(USER).status());

        service.logSet(USER, id, 0, 0, true);

        assertEquals("in_progress", todaysSession(USER).status());
    }

    // ------------------------------------------------------------ ownership

    /**
     * 404 rather than 403 throughout: whether somebody else's session id exists
     * is not this caller's business, and a 403 answers exactly that question.
     */
    @Test
    void anotherUsersSessionIsInvisibleRatherThanForbidden() {
        service.saveProfile(OTHER_USER, onboarding());
        long theirs = todaysSession(OTHER_USER).id();

        for (Runnable attempt : List.<Runnable>of(
                () -> service.start(USER, theirs),
                () -> service.logSet(USER, theirs, 0, 0, true),
                () -> service.setSkipped(USER, theirs, true),
                () -> service.alternatives(USER, theirs, 0),
                () -> service.complete(USER, theirs, new WorkoutCompleteRequest(null, null, null, "en")))) {
            assertEquals(HttpStatus.NOT_FOUND,
                    assertThrows(ResponseStatusException.class, attempt::run).getStatusCode());
        }
        assertTrue(setLogs.findBySessionId(theirs).isEmpty(), "another user wrote to this session");
    }

    // ---------------------------------------------------------- replacement

    @Test
    void replacingAnExerciseKeepsTheSlotAndRecordsWhatItWas() {
        service.saveProfile(USER, onboarding());
        WorkoutSessionView session = todaysSession(USER);
        String originalKey = session.exercises().get(0).key();
        List<WorkoutAlternative> options = service.alternatives(USER, session.id(), 0);
        assertFalse(options.isEmpty(), "no swap was offered");

        WorkoutSessionView after =
                service.replaceExercise(USER, session.id(), 0, options.get(0).key());

        assertEquals(session.exercises().size(), after.exercises().size(),
                "a swap changed how many exercises the session has");
        assertEquals(options.get(0).key(), after.exercises().get(0).key());
        assertEquals(originalKey,
                exercises.findBySessionIdAndPosition(session.id(), 0).orElseThrow().getReplacedFrom());
    }

    /**
     * Sets logged against the old movement must not be credited to the new one.
     * The position is the same; the exercise is not.
     */
    @Test
    void replacingAnExerciseClearsTheSetsLoggedAgainstIt() {
        service.saveProfile(USER, onboarding());
        WorkoutSessionView session = todaysSession(USER);
        service.logSet(USER, session.id(), 0, 0, true);
        service.logSet(USER, session.id(), 1, 0, true);

        String swapTo = service.alternatives(USER, session.id(), 0).get(0).key();
        WorkoutSessionView after = service.replaceExercise(USER, session.id(), 0, swapTo);

        assertEquals(List.of(), after.exercises().get(0).completedSets(),
                "work done on the old movement was credited to the new one");
        assertEquals(List.of(0), after.exercises().get(1).completedSets(),
                "an unrelated exercise lost its logged sets");
    }

    @Test
    void anExerciseThatWasNotOfferedCannotBeSwappedIn() {
        service.saveProfile(USER, onboarding());
        WorkoutSessionView session = todaysSession(USER);

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.replaceExercise(USER, session.id(), 0, "barbell_deadlift")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.replaceExercise(USER, session.id(), 0, "not_an_exercise")).getStatusCode());
    }

    @Test
    void everyOfferedAlternativeCanActuallyBeSwappedIn() {
        service.saveProfile(USER, onboarding());
        WorkoutSessionView session = todaysSession(USER);

        for (WorkoutAlternative option : service.alternatives(USER, session.id(), 0)) {
            WorkoutSessionView after =
                    service.replaceExercise(USER, session.id(), 0, option.key());
            assertEquals(option.key(), after.exercises().get(0).key());
            assertTrue(after.exercises().get(0).reps() > 0,
                    option.key() + " was swapped in with no dose");
        }
    }

    // -------------------------------------------------------- skip and finish

    @Test
    void skippingAndUnskippingMoveTheSessionBackAndForth() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();

        assertEquals("skipped", service.setSkipped(USER, id, true).status());
        assertEquals("planned", service.setSkipped(USER, id, false).status());
    }

    @Test
    void aFinishedSessionCannotBeSkipped() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();
        service.complete(USER, id, new WorkoutCompleteRequest("just_right", null, 25, "en"));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.setSkipped(USER, id, true)).getStatusCode());
    }

    @Test
    void completingASessionRecordsTheRatingAndAnswersIt() {
        service.saveProfile(USER, onboarding());
        WorkoutSessionView session = todaysSession(USER);
        service.logSet(USER, session.id(), 0, 0, true);
        service.logSet(USER, session.id(), 0, 1, true);

        WorkoutCompletionResponse done = service.complete(USER, session.id(),
                new WorkoutCompleteRequest("too_hard", "tired", 26, "en"));

        assertEquals("completed", done.session().status());
        assertEquals(26, done.minutes());
        assertEquals(session.exercises().size(), done.exercises());
        assertEquals(2, done.sets(), "only the sets actually logged should be counted");
        assertFalse(done.coachReply().isBlank());

        WorkoutSessionEntity stored = sessions.findById(session.id()).orElseThrow();
        assertEquals("too_hard", stored.getFeel());
        assertEquals("tired", stored.getEnergy());
        assertNotNull(stored.getCompletedAt());
    }

    /** Both questions are optional, and skipping them must not invent an answer. */
    @Test
    void completingWithoutRatingSaysNothingRatherThanSomethingGeneric() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();

        WorkoutCompletionResponse done = service.complete(USER, id,
                new WorkoutCompleteRequest(null, null, null, "en"));

        assertEquals("", done.coachReply());
        assertEquals("completed", done.session().status());
        assertTrue(done.minutes() > 0, "with no reported duration the planned length should stand in");
    }

    @Test
    void anInvalidRatingIsRejectedRatherThanIgnored() {
        service.saveProfile(USER, onboarding());
        long id = todaysSession(USER).id();

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.complete(USER, id, new WorkoutCompleteRequest("amazing", null, null, "en")))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.complete(USER, id, new WorkoutCompleteRequest(null, "buzzing", null, "en")))
                .getStatusCode());
    }

    // ------------------------------------------------------------- deletion

    @Test
    void deletingAnAccountLeavesNoWorkoutRowBehind() {
        service.saveProfile(USER, onboarding());
        service.saveProfile(OTHER_USER, onboarding());
        long mine = todaysSession(USER).id();
        long theirs = todaysSession(OTHER_USER).id();
        service.logSet(USER, mine, 0, 0, true);
        service.logSet(OTHER_USER, theirs, 0, 0, true);

        service.deleteAllForUser(USER);

        assertTrue(profiles.findByUserId(USER).isEmpty());
        assertTrue(sessions.findByUserIdAndSessionDate(USER, LocalDate.now()).isEmpty());
        assertTrue(exercises.findBySessionIdOrderByPositionAsc(mine).isEmpty(),
                "session exercises carry no user_id, so they outlived the account");
        assertTrue(setLogs.findBySessionId(mine).isEmpty(),
                "set logs carry no user_id, so they outlived the account");

        assertTrue(profiles.findByUserId(OTHER_USER).isPresent(), "the wrong account was erased");
        assertFalse(exercises.findBySessionIdOrderByPositionAsc(theirs).isEmpty());
        assertFalse(setLogs.findBySessionId(theirs).isEmpty());
    }

    // --------------------------------------------------------------- streak

    @Test
    void aStreakCountsConsecutiveCompletedDays() {
        LocalDate today = LocalDate.of(2026, 8, 17);

        assertEquals(3, WorkoutCalendar.streak(List.of(
                completedOn(today.minusDays(2)), completedOn(today.minusDays(1)), completedOn(today)), today));
    }

    /** Not having trained yet today must not read as a broken streak all morning. */
    @Test
    void aStreakSurvivesTodayNotBeingDoneYet() {
        LocalDate today = LocalDate.of(2026, 8, 17);

        assertEquals(2, WorkoutCalendar.streak(List.of(
                completedOn(today.minusDays(2)), completedOn(today.minusDays(1))), today));
    }

    @Test
    void aGapBreaksTheStreak() {
        LocalDate today = LocalDate.of(2026, 8, 17);

        assertEquals(1, WorkoutCalendar.streak(List.of(
                completedOn(today.minusDays(5)), completedOn(today)), today));
        assertEquals(0, WorkoutCalendar.streak(List.of(completedOn(today.minusDays(4))), today));
        assertEquals(0, WorkoutCalendar.streak(List.of(), today));
    }

    /** A planned or skipped day is not a trained day. */
    @Test
    void onlyCompletedSessionsExtendAStreak() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        WorkoutSessionEntity skipped = completedOn(today.minusDays(1));
        skipped.setStatus("skipped");

        assertEquals(1, WorkoutCalendar.streak(List.of(skipped, completedOn(today)), today));
    }

    private static WorkoutSessionEntity completedOn(LocalDate date) {
        WorkoutSessionEntity e = new WorkoutSessionEntity();
        e.setSessionDate(date);
        e.setStatus("completed");
        return e;
    }
}
