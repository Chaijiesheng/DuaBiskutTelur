package com.duabiskuttelur.service;

import com.duabiskuttelur.persistence.WorkoutProfileEntity;
import com.duabiskuttelur.service.WorkoutCatalog.Equipment;
import com.duabiskuttelur.service.WorkoutCatalog.Level;
import com.duabiskuttelur.service.WorkoutVocabulary.Goal;
import com.duabiskuttelur.service.WorkoutVocabulary.Preference;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The six onboarding answers, parsed.
 *
 * <p>The entity stores tags and comma-joined lists because that is what a row
 * is good at; the planner and the coach want enums and sets because that is what
 * decisions are good at. This record is the one place the two meet, so neither
 * of them ends up splitting strings.
 *
 * @param equipment always contains {@link Equipment#NONE} — see the compact
 *                  constructor for why that is not merely a convenience
 */
public record WorkoutProfile(Goal goal, Level level, int daysPerWeek, int sessionMinutes,
                             Set<Equipment> equipment, Set<Preference> preferences) {

    public WorkoutProfile {
        /*
         * Bodyweight is not equipment you can fail to own. Without this, a user
         * who ticked only "Dumbbells" would be ineligible for every push-up,
         * plank and lunge in the catalogue — the planner would quietly hand them
         * a three-exercise session and nothing would look broken.
         */
        Set<Equipment> withBodyweight = EnumSet.of(Equipment.NONE);
        withBodyweight.addAll(equipment);
        equipment = Set.copyOf(withBodyweight);
        preferences = Set.copyOf(preferences);
    }

    /** Reads a stored row, ignoring any tag that is no longer in the vocabulary. */
    public static WorkoutProfile from(WorkoutProfileEntity e) {
        return new WorkoutProfile(
                Goal.parse(e.getGoal()).orElse(Goal.GENERAL_FITNESS),
                Level.parse(e.getLevel()).orElse(Level.BEGINNER),
                e.getDaysPerWeek(),
                e.getSessionMinutes(),
                splitInto(e.getEquipment(), Equipment::parse),
                splitInto(e.getPreferences(), Preference::parse));
    }

    private static <T extends Enum<T>> Set<T> splitInto(
            String joined, java.util.function.Function<String, java.util.Optional<T>> parse) {
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(joined.split(","))
                .map(parse)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Comma-joins a set back into the column form, in enum order so the value is stable. */
    public static <T extends Enum<T>> String join(Set<T> values) {
        return values.stream()
                .sorted(java.util.Comparator.comparingInt(Enum::ordinal))
                .map(v -> v.name().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.joining(","));
    }
}
