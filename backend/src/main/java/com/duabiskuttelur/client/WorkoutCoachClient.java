package com.duabiskuttelur.client;

import com.duabiskuttelur.model.WorkoutCoachNote;

/**
 * Provider-agnostic coaching prose for a workout session (text-only calls).
 *
 * <p>Note what is <em>not</em> here: the session itself. Exercises, sets and
 * reps come from {@code WorkoutCatalog} and {@code WorkoutPlanner}, and the
 * model only explains them. A model that invents a workout can invent a bad one
 * and there is nothing to check it against; a model that writes a sentence about
 * a workout can only be boring, and {@code WorkoutCoach} has a rule-based
 * sentence ready for when it is unavailable entirely.
 */
public interface WorkoutCoachClient {

    /**
     * @param context      the session and the facts it was built from, already
     *                     assembled in Java — the model derives nothing
     * @param languageName the language to write in, spelled out for a prompt
     *                     ("Bahasa Melayu", not "ms")
     */
    WorkoutCoachNote coachNote(String context, String languageName);

    /** One reply to how the user rated the session they just finished. */
    String sessionReply(String context, String languageName);
}
