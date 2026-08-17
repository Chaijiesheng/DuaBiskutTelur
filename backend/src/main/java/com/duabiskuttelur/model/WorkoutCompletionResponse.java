package com.duabiskuttelur.model;

/**
 * The completion screen: what was done, and the coach's reaction to how it felt.
 *
 * @param coachReply empty when the user skipped the rating — the card only
 *                   appears once there is something to reply to, and inventing a
 *                   reaction to an answer nobody gave is worse than staying quiet
 */
public record WorkoutCompletionResponse(
        WorkoutSessionView session,
        int minutes,
        int exercises,
        int sets,
        String coachReply,
        String coachSource
) {
}
