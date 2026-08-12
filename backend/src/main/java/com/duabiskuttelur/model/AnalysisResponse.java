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
        boolean persisted,
        // The saved history row this response came from, so the client can send
        // a portion correction back for it. Null for visitors and for a client
        // whose session had expired - neither has a row to correct. Deliberately
        // set on the outgoing response rather than stored inside result_json:
        // the JSON is written before the row has an id, and a copy of the id
        // inside its own row is one more thing that can disagree with reality.
        Long entryId
) {
    /** Same response, now carrying the id of the row it was saved as. */
    public AnalysisResponse withEntryId(Long id) {
        return new AnalysisResponse(foods, totals, score, grade, highlights, concerns,
                suggestions, encouragement, source, scoreBreakdown, persisted, id);
    }

    /** For the pre-persistence construction sites, which have no id yet. */
    public AnalysisResponse(List<FoodItem> foods, Totals totals, int score, String grade,
                            List<String> highlights, List<String> concerns, List<String> suggestions,
                            String encouragement, String source, ScoreBreakdown scoreBreakdown,
                            boolean persisted) {
        this(foods, totals, score, grade, highlights, concerns, suggestions, encouragement,
                source, scoreBreakdown, persisted, null);
    }
    /** The four components ScoringService sums to the final score, so the app can show its work. */
    public record ScoreBreakdown(
            double balance, int balanceMax,
            double quality, int qualityMax,
            double portion, int portionMax,
            double variety, int varietyMax
    ) {
    }
}
