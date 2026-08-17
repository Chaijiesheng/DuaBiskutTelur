package com.duabiskuttelur.model;

/**
 * One swap option in the Replace sheet.
 *
 * <p>{@code why} is the exercise's own coaching cue, not a generated
 * justification. The sheet tells the user these keep the same job in the
 * session, and that promise is kept by <em>how the list is built</em> — same
 * movement pattern, same or less equipment — not by a sentence claiming it.
 */
public record WorkoutAlternative(String key, String name, String target, String why) {
}
