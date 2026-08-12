package com.duabiskuttelur.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The friendly-feedback section: either the model's prose or the rule-based
 * fallback's, in the same shape.
 *
 * <p>This is the one user-visible surface a successful prompt injection could
 * steer, so the model's answer is bounded here rather than trusted: each string
 * is scrubbed and capped, and each list is capped at
 * {@link UntrustedText#MAX_FEEDBACK_ITEMS} entries. The prompt asks for 2–3 and
 * the rule-based path already limited itself to 3; the AI path did not, so a
 * steered model could return fifty "suggestions" and the UI would render every
 * one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedbackResult(
        List<String> highlights,
        List<String> concerns,
        List<String> suggestions,
        String encouragement
) {
    public FeedbackResult {
        highlights = UntrustedText.cleanAll(
                highlights, UntrustedText.MAX_FEEDBACK_ITEMS, UntrustedText.MAX_FEEDBACK_LINE);
        concerns = UntrustedText.cleanAll(
                concerns, UntrustedText.MAX_FEEDBACK_ITEMS, UntrustedText.MAX_FEEDBACK_LINE);
        suggestions = UntrustedText.cleanAll(
                suggestions, UntrustedText.MAX_FEEDBACK_ITEMS, UntrustedText.MAX_FEEDBACK_LINE);
        encouragement = UntrustedText.clean(encouragement, UntrustedText.MAX_FEEDBACK_LINE);
    }
}
