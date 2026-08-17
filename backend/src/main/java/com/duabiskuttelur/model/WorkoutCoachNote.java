package com.duabiskuttelur.model;

import java.util.List;

/**
 * The "Why this workout" card: one paragraph, plus the factors behind it.
 *
 * <p>The two parts are separate because the UI reveals them separately — the
 * summary is always visible, the factors sit behind "What did you look at?".
 * That disclosure is the honest shape for this card: the paragraph is a claim,
 * and the factors are what makes it checkable rather than something the app
 * merely asserts about you.
 *
 * @param summary a short paragraph justifying today's session
 * @param factors the specific things it was built from, one short line each
 */
public record WorkoutCoachNote(String summary, List<String> factors) {

    public WorkoutCoachNote {
        factors = factors == null ? List.of() : List.copyOf(factors);
    }
}
