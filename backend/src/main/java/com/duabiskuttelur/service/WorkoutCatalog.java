package com.duabiskuttelur.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Every exercise a generated session can contain, read from
 * {@code workout/exercises.csv}.
 *
 * <p>Sessions are assembled from this table by {@link WorkoutPlanner}, never
 * written by the model. A generated plan can invent an exercise, prescribe a
 * dose nobody should do, or come back empty on a bad day; a table cannot. The
 * model's job in this feature is one coaching sentence — see {@link WorkoutCoach}
 * — and the prototype's own "coach unavailable, standard plan" state is the
 * design admitting the same split.
 *
 * <p>Loading mirrors {@link LocalDishTable} — {@code @PostConstruct}, a
 * {@code ClassPathResource}, {@code #} comments and blank lines skipped — with
 * one deliberate difference: <strong>a failure here stops the application from
 * starting</strong>, where a failed dish table only disables itself. That is not
 * inconsistency. A missing dish row falls through to USDA and the meal still
 * gets graded; a missing exercise table has nothing behind it, so the graceful
 * version of this class would serve every user an empty workout and log about
 * it. The file ships inside the jar, so the only way it can be unreadable is a
 * broken build, and a broken build should fail to boot rather than deploy.
 */
@Component
public class WorkoutCatalog {

    private static final Logger log = LoggerFactory.getLogger(WorkoutCatalog.class);
    private static final String RESOURCE = "workout/exercises.csv";

    /** key, name, pattern, target, equipment, level, unit, base_reps, cue. */
    private static final int FIELDS = 9;

    /**
     * Movement patterns, in the order a session reads best: legs before push
     * before pull, the trunk after the limbs that need it fresh, and the two
     * that finish a session last. {@link WorkoutPlanner} relies on this order,
     * so it is declared here rather than restated there.
     */
    public enum Pattern {
        SQUAT, HINGE, PUSH, PULL, CORE, CARDIO, MOBILITY;

        public String tag() { return name().toLowerCase(Locale.ROOT); }
    }

    /**
     * What you must own to do a movement, weakest first.
     *
     * <p>The ordinal is load-bearing twice over: the Replace sheet offers
     * alternatives at the same tier <em>or lower</em>, and the planner prefers
     * the richest option a user can actually reach. {@code NONE} first is what
     * makes both of those mean "no worse off than before".
     */
    public enum Equipment {
        NONE, BANDS, DUMBBELLS, GYM;

        public String tag() { return name().toLowerCase(Locale.ROOT); }

        public static Optional<Equipment> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String v = raw.trim().toUpperCase(Locale.ROOT);
            for (Equipment e : values()) {
                if (e.name().equals(v)) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The <em>minimum</em> level a movement is appropriate for, easiest first.
     * A beginner is never shown an intermediate row; an intermediate user can be
     * shown beginner ones, because "easy enough" is not a defect.
     */
    public enum Level {
        BEGINNER, INTERMEDIATE, ADVANCED;

        public String tag() { return name().toLowerCase(Locale.ROOT); }

        public static Optional<Level> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String v = raw.trim().toUpperCase(Locale.ROOT);
            for (Level l : values()) {
                if (l.name().equals(v)) {
                    return Optional.of(l);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * How a dose is counted. Kept as a closed set because the UI renders each
     * differently and a plank prescribed in "reps" is wrong in a way no amount
     * of number-tuning fixes.
     */
    public enum Unit {
        REPS, SEC, EACH_SIDE;

        public String tag() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One row of the table. Immutable, and copied into a session at generation time. */
    public record Exercise(String key, String name, Pattern pattern, String target,
                           Equipment equipment, Level level, Unit unit, int baseReps, String cue) {
    }

    private List<Exercise> exercises = List.of();

    public WorkoutCatalog() {
    }

    /**
     * A catalogue with fixed contents, for tests that need a specific, tiny
     * table rather than the real one — the same escape hatch
     * {@link LocalDishTable} provides.
     */
    WorkoutCatalog(List<Exercise> exercises) {
        this.exercises = List.copyOf(exercises);
    }

    @PostConstruct
    void load() {
        List<Exercise> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                parse(trimmed).ifPresent(loaded::add);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + RESOURCE
                    + "; the workout feature has no data to build sessions from", e);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " parsed to zero exercises");
        }
        // Hardest first within a pattern, so the planner can take the first row a
        // user qualifies for instead of scanning for the best one. Key breaks
        // ties, which is what keeps generation deterministic across JVM runs —
        // the order a file happens to be read in is not a stable seed.
        loaded.sort(Comparator.comparing(Exercise::pattern)
                .thenComparing(Comparator.comparing(Exercise::level).reversed())
                .thenComparing(Exercise::equipment, Comparator.reverseOrder())
                .thenComparing(Exercise::key));
        exercises = List.copyOf(loaded);
        log.info("Loaded {} exercises from {}", exercises.size(), RESOURCE);
    }

    private static Optional<Exercise> parse(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length != FIELDS) {
            log.warn("Skipping malformed exercise row (expected {} fields, got {}): {}", FIELDS, f.length, line);
            return Optional.empty();
        }
        try {
            Pattern pattern = null;
            String rawPattern = f[2].trim().toUpperCase(Locale.ROOT);
            for (Pattern p : Pattern.values()) {
                if (p.name().equals(rawPattern)) {
                    pattern = p;
                }
            }
            Unit unit = null;
            String rawUnit = f[6].trim().toUpperCase(Locale.ROOT);
            for (Unit u : Unit.values()) {
                if (u.name().equals(rawUnit)) {
                    unit = u;
                }
            }
            Optional<Equipment> equipment = Equipment.parse(f[4]);
            Optional<Level> level = Level.parse(f[5]);
            if (pattern == null || unit == null || equipment.isEmpty() || level.isEmpty()) {
                log.warn("Skipping exercise row with an unknown vocabulary value: {}", line);
                return Optional.empty();
            }
            return Optional.of(new Exercise(f[0].trim(), f[1].trim(), pattern, f[3].trim(),
                    equipment.get(), level.get(), unit, Integer.parseInt(f[7].trim()), f[8].trim()));
        } catch (NumberFormatException e) {
            log.warn("Skipping exercise row with unparseable base_reps: {}", line);
            return Optional.empty();
        }
    }

    /** Every row, in the sorted order described in {@link #load()}. */
    public List<Exercise> all() {
        return exercises;
    }

    public Optional<Exercise> byKey(String key) {
        return exercises.stream().filter(e -> e.key().equals(key)).findFirst();
    }

    /**
     * The rows a user could be given: the right pattern, equipment they said
     * they have, and not harder than the level they said they are.
     */
    public List<Exercise> candidates(Pattern pattern, Set<Equipment> owned, Level level) {
        return exercises.stream()
                .filter(e -> e.pattern() == pattern)
                .filter(e -> owned.contains(e.equipment()))
                .filter(e -> e.level().ordinal() <= level.ordinal())
                .toList();
    }

    /**
     * Swap options for one slot of a session.
     *
     * <p>The Replace sheet tells the user "each of these keeps the same job in
     * your session". That is only true if a swap holds the pattern, so this
     * filters to it rather than offering whatever else is nearby — the whole
     * point of the {@code pattern} column.
     *
     * <p>Equipment is capped at the current exercise's tier, not the user's:
     * somebody tapping "can't do this" on a barbell squat is usually standing in
     * front of an occupied rack, and offering them a different barbell movement
     * answers the wrong question. Their own kit still bounds the list, so a
     * bodyweight-only user is never offered dumbbells.
     */
    public List<Exercise> alternatives(Exercise current, Set<Equipment> owned, Level level) {
        return exercises.stream()
                .filter(e -> e.pattern() == current.pattern())
                .filter(e -> !e.key().equals(current.key()))
                .filter(e -> owned.contains(e.equipment()))
                .filter(e -> e.equipment().ordinal() <= current.equipment().ordinal())
                .filter(e -> e.level().ordinal() <= level.ordinal())
                .toList();
    }

    /** The distinct patterns present in the table, for the coverage guard in tests. */
    Set<Pattern> patternsPresent() {
        return exercises.stream().map(Exercise::pattern)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
