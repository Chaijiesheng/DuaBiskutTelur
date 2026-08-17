package com.duabiskuttelur.service;

import com.duabiskuttelur.service.WorkoutCatalog.Equipment;
import com.duabiskuttelur.service.WorkoutCatalog.Exercise;
import com.duabiskuttelur.service.WorkoutCatalog.Level;
import com.duabiskuttelur.service.WorkoutCatalog.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards on {@code workout/exercises.csv}.
 *
 * <p>Deliberately whole-file rather than spot-checks. {@code LocalDishTableTest}
 * originally sampled five of fifty-five rows, which meant a bad row had a
 * roughly one-in-eleven chance of being noticed; the checks here ask every row
 * every question. A curated data file is only as good as the assertion that it
 * still parses, and rows are added by hand.
 *
 * <p>The load is exercised through the real {@code @PostConstruct} path, so a
 * malformed line that the loader silently skips shows up as a missing row here
 * rather than as a short session in production.
 */
class WorkoutCatalogTest {

    private static WorkoutCatalog catalog;

    @BeforeAll
    static void loadTheRealFile() {
        catalog = new WorkoutCatalog();
        catalog.load();
    }

    @Test
    void theFileLoadsAndIsNotTrivial() {
        assertTrue(catalog.all().size() >= 40,
                "the catalogue is unexpectedly small — a parse failure silently skips rows, "
                        + "and a short catalogue becomes short sessions: " + catalog.all().size());
    }

    @Test
    void everyRowHasAKeyANameATargetAndACue() {
        for (Exercise e : catalog.all()) {
            assertFalse(e.key().isBlank(), "an exercise has no key");
            assertFalse(e.name().isBlank(), e.key() + " has no name");
            assertFalse(e.target().isBlank(), e.key() + " has no target");
            assertFalse(e.cue() == null || e.cue().isBlank(),
                    e.key() + " has no cue — the cue is the one thing shown mid-set to keep "
                            + "the movement safe, so a blank one is not a cosmetic gap");
        }
    }

    @Test
    void everyKeyIsUnique() {
        Set<String> keys = catalog.all().stream().map(Exercise::key).collect(Collectors.toSet());
        assertEquals(catalog.all().size(), keys.size(),
                "two rows share a key. Sessions store the key, so a duplicate makes "
                        + "'what did I actually do' unanswerable for every session using it");
    }

    @Test
    void everyRowsDoseIsPositive() {
        for (Exercise e : catalog.all()) {
            assertTrue(e.baseReps() > 0, e.key() + " has a base dose of " + e.baseReps());
        }
    }

    /**
     * The invariant the planner leans on.
     *
     * <p>Without it, a beginner who owns nothing simply has no candidate for
     * some pattern, and {@code WorkoutPlanner} skips the slot. The result is a
     * session that is quietly one exercise shorter with no error anywhere — the
     * worst kind of failure, because everything still looks like it worked.
     */
    @Test
    void everyPatternHasANoEquipmentBeginnerOption() {
        for (Pattern pattern : Pattern.values()) {
            List<Exercise> reachable = catalog.candidates(
                    pattern, Set.of(Equipment.NONE), Level.BEGINNER);
            assertFalse(reachable.isEmpty(),
                    "a beginner with no equipment has no option for the " + pattern.tag()
                            + " pattern, so their session silently loses that slot");
        }
    }

    @Test
    void everyPatternInTheVocabularyIsActuallyUsed() {
        assertEquals(EnumSet.allOf(Pattern.class), EnumSet.copyOf(catalog.patternsPresent()),
                "a pattern exists in the enum with no rows behind it, or vice versa");
    }

    /**
     * A pattern with one row cannot fill a slot twice, and an upper-body day asks
     * for two pushes and two pulls. The planner degrades gracefully — it drops
     * the slot rather than repeating the movement — so this would show up as a
     * short session, not a crash.
     */
    @Test
    void everyPatternCanFillARepeatedSlotForAKitlessBeginner() {
        for (Pattern pattern : Pattern.values()) {
            List<Exercise> reachable = catalog.candidates(
                    pattern, Set.of(Equipment.NONE), Level.BEGINNER);
            assertTrue(reachable.size() >= 2,
                    "the " + pattern.tag() + " pattern has only " + reachable.size()
                            + " no-equipment beginner option(s), so a day that uses it twice "
                            + "loses the second slot");
        }
    }

    /** An advanced user is offered strictly more than a beginner, never less. */
    @Test
    void levelWidensRatherThanShiftsWhatIsAvailable() {
        for (Pattern pattern : Pattern.values()) {
            Set<String> beginner = keysFor(pattern, Level.BEGINNER);
            Set<String> advanced = keysFor(pattern, Level.ADVANCED);
            assertTrue(advanced.containsAll(beginner),
                    "an advanced user lost access to a beginner " + pattern.tag()
                            + " movement; level is a minimum, not a band");
        }
    }

    private static Set<String> keysFor(Pattern pattern, Level level) {
        return catalog.candidates(pattern, EnumSet.allOf(Equipment.class), level).stream()
                .map(Exercise::key).collect(Collectors.toSet());
    }

    /**
     * The Replace sheet's promise, checked against the mechanism rather than the
     * copy: "each of these keeps the same job in your session".
     */
    @Test
    void alternativesKeepThePatternAndNeverNeedMoreEquipment() {
        Set<Equipment> everything = EnumSet.allOf(Equipment.class);
        for (Exercise current : catalog.all()) {
            for (Exercise alt : catalog.alternatives(current, everything, Level.ADVANCED)) {
                assertEquals(current.pattern(), alt.pattern(),
                        alt.key() + " was offered as a swap for " + current.key()
                                + " but does a different job in the session");
                assertTrue(alt.equipment().ordinal() <= current.equipment().ordinal(),
                        alt.key() + " needs more equipment than the " + current.key()
                                + " the user just said they could not do");
                assertFalse(alt.key().equals(current.key()), "an exercise was offered as its own alternative");
            }
        }
    }

    @Test
    void alternativesNeverExceedTheUsersOwnEquipment() {
        Exercise gymMovement = catalog.all().stream()
                .filter(e -> e.equipment() == Equipment.GYM)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the catalogue has no gym movement to test with"));

        List<Exercise> offered = catalog.alternatives(gymMovement, Set.of(Equipment.NONE), Level.ADVANCED);

        assertFalse(offered.isEmpty(), "a bodyweight user was offered nothing at all");
        assertTrue(offered.stream().allMatch(e -> e.equipment() == Equipment.NONE),
                "a user who owns no equipment was offered something they cannot do: " + offered);
    }

    /** A key that is not in the file must miss rather than resolve to something near it. */
    @Test
    void anUnknownKeyResolvesToNothing() {
        assertTrue(catalog.byKey("not_a_real_exercise").isEmpty());
        assertTrue(catalog.byKey("").isEmpty());
    }
}
