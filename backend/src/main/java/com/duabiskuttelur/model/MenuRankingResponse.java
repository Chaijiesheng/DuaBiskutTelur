package com.duabiskuttelur.model;

import java.util.List;

public record MenuRankingResponse(
        // Always exactly 5 groups, best-to-worst, even when a tier has no dishes in
        // it — the frontend renders all 5 rows every time, matching the reference
        // tier-list format regardless of how the menu happened to score.
        List<TierGroup> tiers,
        // Extras, condiments and drinks, listed but deliberately not tiered —
        // ranking a spoon of sambal against a nasi lemak compares things that
        // aren't alternatives to each other. Empty for scans saved before the
        // split existed, and for menus with no add-on section.
        List<MenuDish> addOns,
        int dishCount,
        boolean truncated,
        // True when tiers rank the dishes against each other rather than against
        // the absolute grade bands — see MenuRankingService.useRelativeTiers.
        // The frontend shows a notice so a 夯 on an all-fried-food menu isn't
        // mistaken for "this dish is genuinely healthy".
        boolean relative,
        // Same semantics as AnalysisResponse.persisted: false for anonymous visitors
        // or an expired session, so the frontend can show a "sign in to save this"
        // banner right after a scan.
        boolean persisted
) {
    /** label is the literal tier string (e.g. "夯"), kept in sync with frontend/src/tierMeta.js. */
    public record TierGroup(String tier, String label, List<MenuDish> dishes) {
    }
}
