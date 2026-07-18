package com.duabiskuttelur.model;

import java.util.List;

public record AnalysisResponse(
        List<FoodItem> foods,
        Totals totals,
        int score,
        String grade,
        List<String> highlights,
        List<String> concerns,
        List<String> suggestions,
        String encouragement,
        String source,
        // Null for rows saved before this field existed — the frontend falls
        // back to showing the four factors without this meal's actual split.
        ScoreBreakdown scoreBreakdown,
        // Whether this analysis was attributed to a signed-in account. False for
        // anonymous visitors AND for a client whose session cookie expired
        // server-side (analyze is permitAll, so that request silently downgrades
        // to visitor) — the frontend uses it right after an analysis to warn
        // that the meal wasn't saved. Only meaningful on the live analyze/
        // barcode response; ignore it when re-reading stored history JSON,
        // where old rows deserialize it as false.
        boolean persisted
) {
    /** The four components ScoringService sums to the final score, so the app can show its work. */
    public record ScoreBreakdown(
            double balance, int balanceMax,
            double quality, int qualityMax,
            double portion, int portionMax,
            double variety, int varietyMax
    ) {
    }
}
