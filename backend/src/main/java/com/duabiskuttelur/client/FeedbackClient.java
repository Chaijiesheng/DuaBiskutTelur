package com.duabiskuttelur.client;

import com.duabiskuttelur.model.FeedbackResult;

/**
 * Provider-agnostic feedback generation for a scored meal (text-only call).
 */
public interface FeedbackClient {

    /**
     * @param mealContext the scored meal, with the model-derived part fenced off
     *                    as data (see FeedbackService.buildContext)
     * @param languageName the language to write in, spelled out for a prompt
     *                     ("Simplified Chinese", not "zh"). Passed separately so
     *                     the implementation can put it in a system instruction
     *                     rather than in the same turn as the fenced text.
     */
    FeedbackResult generateFeedback(String mealContext, String languageName);
}
