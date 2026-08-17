package com.duabiskuttelur.service;

import com.duabiskuttelur.service.WorkoutCatalog.Equipment;
import com.duabiskuttelur.service.WorkoutCatalog.Exercise;
import com.duabiskuttelur.service.WorkoutCatalog.Level;
import com.duabiskuttelur.service.WorkoutPlanner.Focus;
import com.duabiskuttelur.service.WorkoutPlanner.PlannedExercise;
import com.duabiskuttelur.service.WorkoutPlanner.PlannedSession;
import com.duabiskuttelur.service.WorkoutVocabulary.Feel;
import com.duabiskuttelur.service.WorkoutVocabulary.Goal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a generated session is allowed to be.
 *
 * <p>These run against the real catalogue rather than a fixture, because most of
 * the properties being asserted — that a bodyweight user is never given a
 * barbell, that a beginner is never given a burpee — are joint properties of the
 * planner <em>and</em> the data. A fixture would let the planner pass while the
 * shipped file failed.
 */
class WorkoutPlannerTest {

    private static WorkoutPlanner planner;

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);

    @BeforeAll
    static void loadTheRealCatalogue() {
        WorkoutCatalog catalog = new WorkoutCatalog();
        catalog.load();
        planner = new WorkoutPlanner(catalog);
    }

    private static WorkoutProfile profile(Level level, int days, int minutes, Equipment... kit) {
        return new WorkoutProfile(Goal.GENERAL_FITNESS, level, days, minutes, Set.of(kit), Set.of());
    }

    /**
     * The rule the whole feature rests on. A plan you can re-roll by pulling to
     * refresh is not a plan, and the persistence layer only stops a *second* row
     * being written — if the planner were nondeterministic, losing a session row
     * for any reason would silently hand the user a different workout.
     */
    @Test
    void thesameUserOnTheSameDayGetsTheIdenticalSession() {
        WorkoutProfile p = profile(Level.INTERMEDIATE, 3, 30, Equipment.DUMBBELLS);

        PlannedSession first = planner.plan(7L, MONDAY, p, List.of());
        PlannedSession second = planner.plan(7L, MONDAY, p, List.of());

        assertEquals(keysOf(first), keysOf(second));
        assertEquals(first.focus(), second.focus());
        assertEquals(first.totalSets(), second.totalSets());
        assertEquals(first.minutes(), second.minutes());
    }

    /** Two people training the same day should not be running the same class. */
    @Test
    void differentUsersOnTheSameDayGetDifferentSessions() {
        WorkoutProfile p = profile(Level.INTERMEDIATE, 3, 30, Equipment.DUMBBELLS);

        Set<List<String>> distinct = new HashSet<>();
        for (long userId = 1; userId <= 8; userId++) {
            distinct.add(keysOf(planner.plan(userId, MONDAY, p, List.of())));
        }

        assertTrue(distinct.size() > 1,
                "eight different users all got the same exercises — the seed is not using the user id");
    }

    /** And the same person should not do the identical session every week. */
    @Test
    void theSameUserGetsVarietyAcrossDays() {
        WorkoutProfile p = profile(Level.INTERMEDIATE, 3, 45, Equipment.DUMBBELLS);

        Set<List<String>> distinct = new HashSet<>();
        for (int i = 0; i < 14; i++) {
            distinct.add(keysOf(planner.plan(7L, MONDAY.plusDays(i), p, List.of())));
        }

        assertTrue(distinct.size() >= 4,
                "a fortnight of training produced only " + distinct.size()
                        + " distinct sessions; the day is barely reaching the seed");
    }

    @Test
    void anExerciseNeverAppearsTwiceInOneSession() {
        for (int i = 0; i < 30; i++) {
            PlannedSession session = planner.plan(11L, MONDAY.plusDays(i),
                    profile(Level.ADVANCED, 4, 45, Equipment.GYM, Equipment.DUMBBELLS), List.of());
            List<String> keys = keysOf(session);
            assertEquals(keys.size(), Set.copyOf(keys).size(),
                    "a session repeated a movement: " + keys);
        }
    }

    /**
     * The one that matters most for trust. Somebody who said "no equipment" and
     * is then shown a barbell deadlift has been told the setup questions did not
     * matter.
     */
    @Test
    void aBodyweightUserIsNeverGivenSomethingTheyCannotDo() {
        WorkoutProfile p = profile(Level.ADVANCED, 4, 45, Equipment.NONE);

        for (int i = 0; i < 30; i++) {
            for (PlannedExercise planned : planner.plan(3L, MONDAY.plusDays(i), p, List.of()).exercises()) {
                assertEquals(Equipment.NONE, planned.exercise().equipment(),
                        planned.exercise().name() + " needs equipment this user does not have");
            }
        }
    }

    @Test
    void aBeginnerIsNeverGivenAnAdvancedMovement() {
        WorkoutProfile p = profile(Level.BEGINNER, 3, 30, Equipment.GYM, Equipment.DUMBBELLS, Equipment.BANDS);

        for (int i = 0; i < 30; i++) {
            for (PlannedExercise planned : planner.plan(5L, MONDAY.plusDays(i), p, List.of()).exercises()) {
                assertEquals(Level.BEGINNER, planned.exercise().level(),
                        planned.exercise().name() + " is above a beginner's level");
            }
        }
    }

    /**
     * An advanced user with a full gym should mostly be doing advanced work.
     * Without the tier preference in {@code pick}, the seed would spread evenly
     * across everything they qualify for and hand a serious lifter a session of
     * knee push-ups.
     */
    @Test
    void anAdvancedUserGetsAdvancedWorkWhereItExists() {
        WorkoutProfile p = profile(Level.ADVANCED, 4, 60, Equipment.GYM, Equipment.DUMBBELLS, Equipment.BANDS);

        long advanced = 0;
        long total = 0;
        for (int i = 0; i < 14; i++) {
            for (PlannedExercise planned : planner.plan(9L, MONDAY.plusDays(i), p, List.of()).exercises()) {
                total++;
                if (planned.exercise().level() == Level.ADVANCED) {
                    advanced++;
                }
            }
        }

        assertTrue(advanced * 2 > total,
                "only " + advanced + " of " + total + " movements were advanced; the hardest "
                        + "tier the user qualifies for is not being preferred");
    }

    @Test
    void aSessionFitsInsideTheTimeTheUserSaidTheyHad() {
        for (int minutes : new int[]{15, 30, 45, 60}) {
            for (Level level : Level.values()) {
                PlannedSession session = planner.plan(
                        13L, MONDAY, profile(level, 3, minutes, Equipment.DUMBBELLS), List.of());
                assertTrue(session.minutes() <= minutes,
                        "a " + minutes + "-minute " + level.tag() + " session was planned at "
                                + session.minutes() + " minutes");
            }
        }
    }

    /**
     * Trimming for time must not empty the session. A 15-minute beginner slot is
     * the tightest real case, and "we fitted your workout into the time" is only
     * an answer if there is still a workout.
     */
    @Test
    void eventTheTightestSessionStillHasRealWork() {
        PlannedSession session = planner.plan(
                17L, MONDAY, profile(Level.BEGINNER, 2, 15, Equipment.NONE), List.of());

        assertTrue(session.exercises().size() >= 3,
                "a 15-minute session was trimmed down to " + session.exercises().size() + " exercises");
        assertTrue(session.exercises().stream().allMatch(p -> p.sets() >= 1));
        assertFalse(session.targetSummary().isBlank());
    }

    @Test
    void trainingMoreDaysAWeekSplitsTheBodyUp() {
        Set<Focus> twiceAWeek = focusesOverAFortnight(2);
        Set<Focus> fourTimesAWeek = focusesOverAFortnight(4);

        assertEquals(Set.of(Focus.FULL_BODY), twiceAWeek,
                "training twice a week must stay full-body — a split at that frequency "
                        + "trains each half once a fortnight, which is worse than not splitting");
        assertTrue(fourTimesAWeek.size() > 1, "four days a week never rotated off one focus");
    }

    private static Set<Focus> focusesOverAFortnight(int daysPerWeek) {
        Set<Focus> seen = new HashSet<>();
        for (int i = 0; i < 14; i++) {
            seen.add(planner.plan(21L, MONDAY.plusDays(i),
                    profile(Level.BEGINNER, daysPerWeek, 45, Equipment.NONE), List.of()).focus());
        }
        return seen;
    }

    @Test
    void aHigherLevelMeansMoreWork() {
        int beginner = planner.plan(23L, MONDAY, profile(Level.BEGINNER, 3, 60, Equipment.NONE), List.of())
                .totalSets();
        int advanced = planner.plan(23L, MONDAY, profile(Level.ADVANCED, 3, 60, Equipment.NONE), List.of())
                .totalSets();

        assertTrue(advanced > beginner,
                "an advanced session (" + advanced + " sets) was not harder than a beginner one ("
                        + beginner + " sets)");
    }

    // --------------------------------------------------- feedback adaptation

    /**
     * The design says this out loud on the dashboard — "you rated two of them
     * easy, so today adds one set" — so it has to actually be true.
     */
    @Test
    void twoEasyRatingsInARowAddASet() {
        assertEquals(0, WorkoutPlanner.volumeAdjustment(List.of()));
        assertEquals(0, WorkoutPlanner.volumeAdjustment(List.of(Feel.TOO_EASY)),
                "one easy rating changed the plan; the copy promises it takes two");
        assertEquals(1, WorkoutPlanner.volumeAdjustment(List.of(Feel.TOO_EASY, Feel.TOO_EASY)));
        assertEquals(0, WorkoutPlanner.volumeAdjustment(List.of(Feel.TOO_EASY, Feel.JUST_RIGHT)));
    }

    /** One "too hard" is acted on immediately — being over-asked twice is how people quit. */
    @Test
    void oneTooHardRatingRemovesASetStraightAway() {
        assertEquals(-1, WorkoutPlanner.volumeAdjustment(List.of(Feel.TOO_HARD)));
        assertEquals(-1, WorkoutPlanner.volumeAdjustment(List.of(Feel.TOO_HARD, Feel.TOO_EASY)));
    }

    @Test
    void theAdjustmentReachesTheActualSession() {
        WorkoutProfile p = profile(Level.INTERMEDIATE, 3, 90, Equipment.NONE);

        int neutral = planner.plan(29L, MONDAY, p, List.of()).totalSets();
        int harder = planner.plan(29L, MONDAY, p, List.of(Feel.TOO_EASY, Feel.TOO_EASY)).totalSets();
        int easier = planner.plan(29L, MONDAY, p, List.of(Feel.TOO_HARD)).totalSets();

        assertTrue(harder > neutral, "two easy ratings did not add any work");
        assertTrue(easier < neutral, "a too-hard rating did not remove any work");
    }

    /** Volume can be reduced to one set, never to none. */
    @Test
    void aTooHardRatingNeverEmptiesAnExercise() {
        PlannedSession session = planner.plan(31L, MONDAY,
                profile(Level.BEGINNER, 3, 60, Equipment.NONE), List.of(Feel.TOO_HARD));

        assertFalse(session.exercises().isEmpty());
        assertTrue(session.exercises().stream().allMatch(p -> p.sets() >= 1),
                "an exercise was planned with zero sets");
    }

    // ---------------------------------------------------------- alternatives

    @Test
    void alternativesRespectTheUsersEquipmentNotJustTheExercisesTier() {
        WorkoutProfile bodyweightOnly = profile(Level.ADVANCED, 3, 30, Equipment.NONE);
        PlannedSession session = planner.plan(37L, MONDAY, bodyweightOnly, List.of());
        Exercise first = session.exercises().get(0).exercise();

        List<Exercise> options = planner.alternatives(first, bodyweightOnly);

        assertFalse(options.isEmpty(), "no swap was offered for " + first.name());
        assertTrue(options.stream().allMatch(e -> e.equipment() == Equipment.NONE));
        assertTrue(options.stream().allMatch(e -> e.pattern() == first.pattern()));
        assertTrue(options.stream().noneMatch(e -> e.key().equals(first.key())));
    }

    @Test
    void everySlotOfAKitlessBeginnersSessionCanBeSwapped() {
        WorkoutProfile p = profile(Level.BEGINNER, 3, 45, Equipment.NONE);
        PlannedSession session = planner.plan(41L, MONDAY, p, List.of());

        for (PlannedExercise planned : session.exercises()) {
            assertFalse(planner.alternatives(planned.exercise(), p).isEmpty(),
                    "tapping \"can't do this\" on " + planned.exercise().name()
                            + " would open an empty sheet");
        }
    }

    // -------------------------------------------------- the muscle-group chip

    /**
     * The chip stays a chip, for every session the catalogue can build.
     *
     * <p>A regression test with a real symptom behind it. Joining the
     * catalogue's own target phrasing produced
     * "Legs and Glutes · Chest and Triceps · Glutes and Hamstrings" — 58
     * characters in a {@code shrink-0} span beside the title, which forced the
     * card wider than the phone, collapsed "Full Body" to one word per line, and
     * pushed the fixed bottom navigation off-screen with it.
     *
     * <p>The card now stacks the chip on its own line, so this is no longer
     * defending a pixel budget beside a title — the layout survives any length.
     * What it still defends is that the chip is a <em>label</em>: three groups
     * at most, in short form, rather than a sentence that happens to be styled
     * like a pill. The longest the catalogue can currently produce is
     * "Upper Back · Lower Back · Rear Shoulders" at 40.
     */
    @Test
    void theTargetChipStaysALabelForEverySessionTheCatalogueCanBuild() {
        for (Level level : Level.values()) {
            for (int i = 0; i < 30; i++) {
                PlannedSession session = planner.plan(47L, MONDAY.plusDays(i),
                        profile(level, 4, 60, Equipment.GYM, Equipment.DUMBBELLS, Equipment.BANDS),
                        List.of());
                String chip = session.targetSummary();

                assertFalse(chip.contains(" and "),
                        "the chip is repeating the catalogue's long-form phrasing, which is what "
                                + "broke the card: \"" + chip + "\"");
                assertTrue(chip.chars().filter(c -> c == '·').count() <= 2,
                        "the chip lists more than three muscle groups: \"" + chip + "\"");
                assertTrue(chip.length() <= 48,
                        "the muscle-group chip has grown into a sentence at " + chip.length()
                                + " characters: \"" + chip + "\"");
            }
        }
    }

    @Test
    void theTargetChipSplitsAndDeduplicatesMuscleGroups() {
        assertEquals("Legs · Glutes · Chest", WorkoutPlanner.summariseTargets(
                List.of("Legs and Glutes", "Chest and Triceps", "Glutes and Hamstrings")));
        // A single group survives intact rather than being split on nothing.
        assertEquals("Core", WorkoutPlanner.summariseTargets(List.of("Core", "Core")));
        assertEquals("", WorkoutPlanner.summariseTargets(List.of()));
    }

    @Test
    void aSessionAlwaysDescribesWhatItTrains() {
        PlannedSession session = planner.plan(43L, MONDAY,
                profile(Level.BEGINNER, 3, 30, Equipment.NONE), List.of());

        assertNotEquals("", session.title());
        assertFalse(session.targetSummary().isBlank());
        assertTrue(session.totalSets() > 0);
    }

    private static List<String> keysOf(PlannedSession session) {
        return session.exercises().stream()
                .map(p -> p.exercise().key() + ":" + p.sets() + "x" + p.reps())
                .collect(Collectors.toList());
    }
}
