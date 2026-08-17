package com.duabiskuttelur.model;

/**
 * One line about today's training, for the Today card on the Snap tab.
 *
 * <p>The lightest of the three read models on purpose. This one is fetched on
 * the app's <em>home screen</em>, by everybody, on every open — so it carries a
 * title and a status and not the exercise list, and it never plans anything.
 *
 * <p>That last part is the whole reason this exists rather than reusing
 * {@code /api/workout/today}: that endpoint generates a session when there
 * isn't one, and generating includes a Gemini call for the coach note. Wiring
 * it to the landing screen would mean a model call per user per day for a
 * sentence most of them would never scroll to, and {@code
 * workout.session.generated} would count app opens rather than intent.
 *
 * @param session     today's stored session, or null if none has been planned yet
 * @param trainingDay whether the rotation calls for training today — the only
 *                    way to tell "you have not opened the tab yet" apart from
 *                    "today is a rest day", which read identically otherwise
 */
public record WorkoutGlanceResponse(boolean hasProfile, boolean trainingDay, Session session) {

    public record Session(long id, String title, int minutes, String status,
                          int completedSets, int totalSets) {
    }
}
