package com.duabiskuttelur.service;

import java.util.Locale;
import java.util.Optional;

/**
 * The closed value sets the workout feature accepts from a client.
 *
 * <p>Everything the six onboarding questions and the two post-session questions
 * can answer lives here, as enums with a {@code parse}. The alternative — free
 * strings validated at the controller — is how a column ends up holding
 * {@code "Lose weight"}, {@code "lose_weight"} and {@code "weight loss"} for the
 * same intent, at which point every read has to guess. {@code UserService}
 * already validates its profile against fixed sets for the same reason; this is
 * that idea with somewhere to live.
 *
 * <p>Tags are the lowercase enum name and are what cross the wire and land in
 * the database, so renaming a constant is a data migration.
 */
public final class WorkoutVocabulary {

    private WorkoutVocabulary() {
    }

    /**
     * What the user is training for.
     *
     * <p>Not the same set as {@code users.goal}, which decides a calorie budget.
     * These decide a strength/cardio mix. They correspond today and the day they
     * stop corresponding should be a value change, not a schema change.
     */
    public enum Goal {
        LOSE_WEIGHT, BUILD_MUSCLE, MAINTAIN, GENERAL_FITNESS;

        public String tag() { return tagOf(name()); }

        public static Optional<Goal> parse(String raw) { return parseInto(values(), raw); }
    }

    /** How the user rated the session they just finished. Drives the next one's volume. */
    public enum Feel {
        TOO_EASY, JUST_RIGHT, TOO_HARD;

        public String tag() { return tagOf(name()); }

        public static Optional<Feel> parse(String raw) { return parseInto(values(), raw); }
    }

    /**
     * How the user felt afterwards. Recorded and shown back; nothing plans
     * against it yet, and pretending otherwise would be the kind of fake
     * personalisation this feature is trying not to be.
     */
    public enum Energy {
        GREAT, NORMAL, TIRED;

        public String tag() { return tagOf(name()); }

        public static Optional<Energy> parse(String raw) { return parseInto(values(), raw); }
    }

    /**
     * Optional training preferences from the last onboarding step.
     *
     * <p><b>Collected and stored, but nothing plans against them yet.</b> Said
     * plainly because the alternative is a reader assuming otherwise: the step is
     * skippable, its help text promises only that "we learn from what you
     * finish", and {@link WorkoutPlanner} never reads this set.
     *
     * <p>When it does, it should be a tiebreak and not a filter — honouring "I
     * like cardio" by dropping every other pattern would build a worse plan than
     * the one the person asked to have built for them.
     */
    public enum Preference {
        STRENGTH, CARDIO, MOBILITY, RUNNING, HOME, GYM;

        public String tag() { return tagOf(name()); }

        public static Optional<Preference> parse(String raw) { return parseInto(values(), raw); }
    }

    private static String tagOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> Optional<E> parseInto(E[] values, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (E e : values) {
            if (e.name().equals(v)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }
}
