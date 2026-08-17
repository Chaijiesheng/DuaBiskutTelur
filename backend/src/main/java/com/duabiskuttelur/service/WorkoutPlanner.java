package com.duabiskuttelur.service;

import com.duabiskuttelur.service.WorkoutCatalog.Exercise;
import com.duabiskuttelur.service.WorkoutCatalog.Level;
import com.duabiskuttelur.service.WorkoutCatalog.Pattern;
import com.duabiskuttelur.service.WorkoutCatalog.Unit;
import com.duabiskuttelur.service.WorkoutVocabulary.Feel;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Builds one day's session from the catalogue. Deterministic, and that is the
 * point.
 *
 * <p>Given the same user, the same date and the same profile this returns the
 * same session every time, because the alternative is a plan you can re-roll by
 * pulling to refresh — and a workout you can reject until you like it is not a
 * plan, it is a slot machine. {@code NutritionCacheService} pins a dish's first
 * nutrition resolution for the same reason, and the persistence layer backs this
 * up: {@code UNIQUE (user_id, session_date)} means today's session is written
 * once.
 *
 * <p>Randomness that <em>is</em> wanted — you should not do the same five
 * exercises every Monday for a year — comes from seeding on
 * {@code (userId, date, slot)} rather than from a clock or an RNG. Variety
 * across days, stability within one.
 */
@Component
public class WorkoutPlanner {

    /** What a session is built around. The title the user reads comes from here. */
    public enum Focus {
        FULL_BODY("Full Body"), UPPER("Upper Body"), LOWER("Lower Body"),
        PUSH("Push"), PULL("Pull"), LEGS("Legs");

        private final String title;

        Focus(String title) { this.title = title; }

        public String title() { return title; }

        public String tag() { return name().toLowerCase(Locale.ROOT); }

        public static Optional<Focus> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String v = raw.trim().toUpperCase(Locale.ROOT);
            for (Focus f : values()) {
                if (f.name().equals(v)) {
                    return Optional.of(f);
                }
            }
            return Optional.empty();
        }
    }

    /** One slot of a built session: which exercise, and the dose prescribed. */
    public record PlannedExercise(Exercise exercise, int sets, int reps) {
        public Unit unit() { return exercise.unit(); }
    }

    /** A complete session, not yet persisted. */
    public record PlannedSession(Focus focus, String title, Level level, int minutes,
                                 List<PlannedExercise> exercises) {
        public PlannedSession {
            exercises = List.copyOf(exercises);
        }

        /** "Chest and Triceps · Legs and Glutes · Core" — the chip under the title. */
        public String targetSummary() {
            return exercises.stream().map(p -> p.exercise().target()).distinct()
                    .limit(3).reduce((a, b) -> a + " · " + b).orElse("");
        }

        public int totalSets() {
            return exercises.stream().mapToInt(PlannedExercise::sets).sum();
        }
    }

    /**
     * The rotation for a given weekly frequency.
     *
     * <p>Training twice a week and splitting into push/pull days means each half
     * of the body is trained once a fortnight, which is worse than not splitting
     * at all — so low frequencies stay full-body and only open up as there are
     * enough days to come back around.
     */
    private static List<Focus> rotationFor(int daysPerWeek) {
        if (daysPerWeek <= 2) {
            return List.of(Focus.FULL_BODY);
        }
        if (daysPerWeek == 3) {
            return List.of(Focus.FULL_BODY, Focus.UPPER, Focus.LOWER);
        }
        return List.of(Focus.PUSH, Focus.PULL, Focus.LEGS, Focus.FULL_BODY);
    }

    /**
     * The movement patterns a focus is made of, in the order they are performed.
     *
     * <p>A pattern may repeat — an upper day is two pushes and two pulls — and
     * the picker guarantees a repeat draws a <em>different</em> exercise, so
     * "push, push" is never the same movement twice.
     */
    private static List<Pattern> slotsFor(Focus focus) {
        return switch (focus) {
            case FULL_BODY -> List.of(Pattern.SQUAT, Pattern.PUSH, Pattern.HINGE, Pattern.PULL, Pattern.CORE);
            case UPPER -> List.of(Pattern.PUSH, Pattern.PULL, Pattern.PUSH, Pattern.PULL, Pattern.CORE);
            case LOWER, LEGS -> List.of(Pattern.SQUAT, Pattern.HINGE, Pattern.SQUAT, Pattern.HINGE, Pattern.CORE);
            case PUSH -> List.of(Pattern.PUSH, Pattern.PUSH, Pattern.SQUAT, Pattern.CORE);
            case PULL -> List.of(Pattern.PULL, Pattern.PULL, Pattern.HINGE, Pattern.CORE);
        };
    }

    /**
     * The last slot, chosen by goal. Everything above is the training; this is
     * what the training is <em>for</em>, and it is the only place the goal
     * answer visibly changes the session.
     */
    private static Pattern finisherFor(WorkoutVocabulary.Goal goal) {
        return switch (goal) {
            case LOSE_WEIGHT, GENERAL_FITNESS -> Pattern.CARDIO;
            case BUILD_MUSCLE -> Pattern.CORE;
            case MAINTAIN -> Pattern.MOBILITY;
        };
    }

    /** Working sets per exercise, before any adjustment for how the last sessions felt. */
    private static int baseSetsFor(Level level, Pattern pattern) {
        if (pattern == Pattern.MOBILITY) {
            return 1;
        }
        if (pattern == Pattern.CARDIO) {
            return 2;
        }
        return switch (level) {
            case BEGINNER -> 2;
            case INTERMEDIATE -> 3;
            case ADVANCED -> 4;
        };
    }

    /** Dose, scaled off the catalogue's beginner baseline. */
    private static int repsFor(Level level, int baseReps) {
        double factor = switch (level) {
            case BEGINNER -> 1.0;
            case INTERMEDIATE -> 1.25;
            case ADVANCED -> 1.5;
        };
        return Math.max(1, (int) Math.round(baseReps * factor));
    }

    /**
     * How the last sessions were rated, turned into a change in working sets.
     *
     * <p>The design promises this out loud — "you rated two of them easy, so
     * today adds one set" — so it has to be true. Two consecutive *too easy*
     * ratings add a set; a single *too hard* removes one immediately, because
     * being asked to do too much twice before anything changes is how people
     * stop opening the app.
     */
    static int volumeAdjustment(List<Feel> recentFeelsNewestFirst) {
        if (recentFeelsNewestFirst.isEmpty()) {
            return 0;
        }
        if (recentFeelsNewestFirst.get(0) == Feel.TOO_HARD) {
            return -1;
        }
        boolean twoEasy = recentFeelsNewestFirst.size() >= 2
                && recentFeelsNewestFirst.get(0) == Feel.TOO_EASY
                && recentFeelsNewestFirst.get(1) == Feel.TOO_EASY;
        return twoEasy ? 1 : 0;
    }

    /** Seconds one set of this dose plausibly takes, work only. */
    private static int workSeconds(Unit unit, int reps) {
        return switch (unit) {
            case REPS -> reps * 3;
            case EACH_SIDE -> reps * 3 * 2;
            case SEC -> reps;
        };
    }

    /** Rest between sets, matching the 45s the in-session rest timer counts down. */
    private static final int REST_SECONDS = 45;

    /** Getting changed, finding space, and the walk between movements. */
    private static final int OVERHEAD_SECONDS = 180;

    /** Below this a session stops being a session, so trimming for time stops here. */
    private static final int MIN_EXERCISES = 3;

    private static int estimatedSeconds(List<PlannedExercise> exercises) {
        int total = OVERHEAD_SECONDS;
        for (PlannedExercise p : exercises) {
            total += p.sets() * (workSeconds(p.unit(), p.reps()) + REST_SECONDS);
        }
        return total;
    }

    private final WorkoutCatalog catalog;

    public WorkoutPlanner(WorkoutCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Builds the session for one user on one date.
     *
     * @param recentFeelsNewestFirst how the last completed sessions were rated,
     *                               most recent first; empty for a first session
     */
    public PlannedSession plan(long userId, LocalDate date, WorkoutProfile profile,
                               List<Feel> recentFeelsNewestFirst) {
        List<Focus> rotation = rotationFor(profile.daysPerWeek());
        Focus focus = rotation.get(Math.floorMod(date.toEpochDay(), rotation.size()));

        List<Pattern> slots = new ArrayList<>(slotsFor(focus));
        slots.add(finisherFor(profile.goal()));

        int adjustment = volumeAdjustment(recentFeelsNewestFirst);
        Set<String> used = new LinkedHashSet<>();
        List<PlannedExercise> chosen = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Pattern pattern = slots.get(i);
            Optional<Exercise> picked = pick(pattern, profile, used, seed(userId, date, i));
            if (picked.isEmpty()) {
                // No row this user qualifies for. WorkoutCatalogTest guarantees
                // every pattern has a no-equipment beginner option, so this can
                // only mean the pattern's whole roster is already in the session
                // — a shorter session, not a broken one.
                continue;
            }
            Exercise exercise = picked.get();
            used.add(exercise.key());
            int sets = Math.max(1, baseSetsFor(profile.level(), pattern) + adjustment);
            chosen.add(new PlannedExercise(exercise, sets, repsFor(profile.level(), exercise.baseReps())));
        }

        List<PlannedExercise> fitted = fitToTime(chosen, profile.sessionMinutes());
        return new PlannedSession(focus, focus.title(), profile.level(),
                Math.max(1, (int) Math.round(estimatedSeconds(fitted) / 60.0)), fitted);
    }

    /**
     * Trims a built session down to the time the user said they had.
     *
     * <p>Sets go first and exercises second. Losing a set from everything keeps
     * the shape of the session — every movement still happens — where dropping
     * exercises first would quietly delete whole patterns and turn a full-body
     * day into an arms day. Only once volume is at its floor does the tail get
     * cut, and never below {@link #MIN_EXERCISES}.
     */
    private static List<PlannedExercise> fitToTime(List<PlannedExercise> exercises, int sessionMinutes) {
        int budget = sessionMinutes * 60;
        List<PlannedExercise> working = new ArrayList<>(exercises);

        while (estimatedSeconds(working) > budget && working.stream().anyMatch(p -> p.sets() > 1)) {
            working = working.stream()
                    .map(p -> p.sets() > 1 ? new PlannedExercise(p.exercise(), p.sets() - 1, p.reps()) : p)
                    .toList();
        }
        while (estimatedSeconds(working) > budget && working.size() > MIN_EXERCISES) {
            working = new ArrayList<>(working.subList(0, working.size() - 1));
        }
        return working;
    }

    /**
     * Picks one exercise for a pattern.
     *
     * <p>Hardest tier the user qualifies for first, and only widening to easier
     * tiers once that tier is exhausted — so an advanced user is not handed a
     * knee push-up while a real option sat unused, and a repeated pattern still
     * finds a second movement rather than giving up.
     */
    private Optional<Exercise> pick(Pattern pattern, WorkoutProfile profile, Set<String> used, long seed) {
        List<Exercise> candidates = catalog.candidates(pattern, profile.equipment(), profile.level());
        for (Level tier : tiersDescending(profile.level())) {
            List<Exercise> inTier = candidates.stream()
                    .filter(e -> e.level() == tier)
                    .sorted(Comparator.comparing(Exercise::key))
                    .toList();
            if (inTier.isEmpty()) {
                continue;
            }
            int start = Math.floorMod((int) seed, inTier.size());
            for (int i = 0; i < inTier.size(); i++) {
                Exercise candidate = inTier.get((start + i) % inTier.size());
                if (!used.contains(candidate.key())) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static List<Level> tiersDescending(Level ceiling) {
        List<Level> out = new ArrayList<>();
        for (int i = ceiling.ordinal(); i >= 0; i--) {
            out.add(Level.values()[i]);
        }
        return out;
    }

    /**
     * A stable pseudo-random value for one slot.
     *
     * <p>SplitMix64's finalizer, because the inputs are small consecutive
     * integers and a naive combination of those produces neighbouring seeds —
     * which, after the modulo in {@link #pick}, means consecutive days keep
     * landing on the same exercise. The mixing is what turns "day 401 and day
     * 402" into two unrelated choices.
     */
    private static long seed(long userId, LocalDate date, int slot) {
        long z = userId * 0x9E3779B97F4A7C15L + date.toEpochDay() * 0xBF58476D1CE4E5B9L + slot * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Exposed for the Replace sheet: the swap options for one slot. */
    public List<Exercise> alternatives(Exercise current, WorkoutProfile profile) {
        return catalog.alternatives(current, profile.equipment(), profile.level());
    }

    /** Exposed so callers can resolve a stored key back to its catalogue row. */
    public Optional<Exercise> exercise(String key) {
        return catalog.byKey(key);
    }
}
