package com.duabiskuttelur.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Relabels ScoringService's existing A+..D grade bands as the 5-tier meme list
 * requested for menu ranking. Pure presentation-layer lookup — no new
 * ScoringProperties config, since the 5 tiers map 1:1 onto the 5 existing grades.
 */
public final class TierMapping {

    /** code is the stable identifier sent to the frontend; label is the literal meme string. */
    public record Tier(String code, String label) {
    }

    // Best-to-worst, matching the reference tier list.
    private static final List<Tier> ORDER = List.of(
            new Tier("HANG", "夯"),
            new Tier("TOP", "顶级"),
            new Tier("RENSHANGREN", "人上人"),
            new Tier("NPC", "NPC"),
            new Tier("LAWANLE", "拉完了"));

    private TierMapping() {
    }

    public static List<Tier> orderedTiers() {
        return ORDER;
    }

    public static String tierFor(String grade) {
        return switch (grade) {
            case "A+" -> "HANG";
            case "A" -> "TOP";
            case "B" -> "RENSHANGREN";
            case "C" -> "NPC";
            default -> "LAWANLE";
        };
    }

    /**
     * Tier code per position for {@code dishCount} dishes already ordered
     * healthiest-first, spread as evenly as the count allows: every tier gets
     * {@code dishCount / 5}, and any leftover goes to the better tiers first.
     * Used when absolute grades would clump everything into one or two tiers
     * (see MenuRankingService.useRelativeTiers) — the returned list is
     * positional, so index i is the tier for the i-th healthiest dish.
     */
    public static List<String> evenlySpreadTiers(int dishCount) {
        int base = dishCount / ORDER.size();
        int remainder = dishCount % ORDER.size();
        List<String> assignment = new ArrayList<>(dishCount);
        for (int tier = 0; tier < ORDER.size(); tier++) {
            int take = base + (tier < remainder ? 1 : 0);
            for (int i = 0; i < take; i++) {
                assignment.add(ORDER.get(tier).code());
            }
        }
        return assignment;
    }
}
