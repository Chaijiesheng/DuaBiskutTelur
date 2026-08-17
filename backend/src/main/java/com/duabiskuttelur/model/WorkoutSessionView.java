package com.duabiskuttelur.model;

import java.util.List;

/**
 * One session as the app renders it — the plan plus how far through it you are.
 *
 * @param date         ISO local date. The session belongs to a day in the user's
 *                     calendar, not to an instant, so it crosses the wire as one.
 * @param targetSummary the muscle-group chip under the title ("Legs · Chest · Core")
 * @param completedSets denormalised from the per-set log so the dashboard can
 *                      draw a progress bar without fetching every exercise
 */
public record WorkoutSessionView(
        long id,
        String date,
        String title,
        String focus,
        int minutes,
        String level,
        String status,
        String targetSummary,
        int totalSets,
        int completedSets,
        List<Exercise> exercises
) {
    /**
     * One slot of the session.
     *
     * @param position     0-based, and the handle the client logs sets against.
     *                     Stable for the life of the session even across a swap.
     * @param unit         "reps", "sec" or "each_side" — the client renders the
     *                     dose differently for each, and a plank shown as
     *                     "30 reps" is wrong in a way no styling fixes.
     * @param completedSets which set indexes are already done, 0-based
     * @param pattern       the movement pattern, which is what the planner chose
     *                      this exercise for. The client uses it for the
     *                      placeholder shown while an exercise has no drawn
     *                      figure yet — so that placeholder says something true
     *                      rather than apologising.
     * @param mistake       the common error for this movement, shown in the
     *                      How-to sheet. Resolved live from the catalogue rather
     *                      than frozen into the session: it describes the
     *                      movement, not the prescription, so improving the
     *                      wording should reach old sessions too. Null if the
     *                      exercise has since left the catalogue.
     * @param videoUrl      a curated YouTube video where one has been picked,
     *                      and a YouTube search for the exercise otherwise — so
     *                      the button works everywhere from the first release.
     */
    public record Exercise(
            int position,
            String key,
            String name,
            String target,
            int sets,
            int reps,
            String unit,
            String cue,
            List<Integer> completedSets,
            String pattern,
            String mistake,
            String videoUrl
    ) {
    }
}
