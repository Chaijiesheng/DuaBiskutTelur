package com.duabiskuttelur.model;

import java.util.List;

/**
 * The six onboarding answers, as the client sends them.
 *
 * <p>Every field is a raw string or list of strings and every one is validated
 * against a closed vocabulary in {@code WorkoutService.saveProfile} — the client
 * is not trusted to have sent a tag that exists, and a rejected value is a 400
 * rather than a row that later reads back as a default nobody chose.
 *
 * @param preferences the skippable last step, so empty and null both mean "no
 *                    preference" rather than being an error
 */
public record WorkoutProfileRequest(
        String goal,
        String level,
        Integer daysPerWeek,
        Integer sessionMinutes,
        List<String> equipment,
        List<String> preferences
) {
}
