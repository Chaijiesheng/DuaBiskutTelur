package com.duabiskuttelur.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedbackResult(
        List<String> highlights,
        List<String> concerns,
        List<String> suggestions,
        String encouragement
) {
}
