package com.duabiskuttelur.model;

/** One dish read off a menu, scored on its own (never combined with the other dishes on the same menu). */
public record MenuDish(
        String name,
        String estimatedPortion,
        int score,
        String grade,
        String tier,
        // 1-based position across the whole menu, healthiest first. Menu scans
        // saved before ranking existed deserialize to 0, which the frontend
        // reads as "unranked" and simply doesn't render.
        int rank,
        FoodItem nutrition
) {
}
