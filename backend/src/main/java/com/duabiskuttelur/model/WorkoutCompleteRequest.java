package com.duabiskuttelur.model;

/**
 * Finishing a session, with the two optional questions the completion screen asks.
 *
 * @param feel         "too_easy", "just_right" or "too_hard", or null if skipped.
 *                     This is the one that changes the next session's volume.
 * @param energy       "great", "normal" or "tired", or null. Recorded, not acted on.
 * @param actualMinutes what it really took, from the client's own clock — the
 *                      server cannot tell a paused session from a slow one
 * @param lang          which language to write the coach's reply in
 */
public record WorkoutCompleteRequest(String feel, String energy, Integer actualMinutes, String lang) {
}
