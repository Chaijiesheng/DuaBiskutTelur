package com.duabiskuttelur.client;

import com.duabiskuttelur.model.FeedbackResult;

/**
 * Provider-agnostic feedback generation for a scored meal (text-only call).
 */
public interface FeedbackClient {

    FeedbackResult generateFeedback(String mealContext);
}
