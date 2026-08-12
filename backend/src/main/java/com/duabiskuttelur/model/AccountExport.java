package com.duabiskuttelur.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the app holds about one account, in the shape a person can
 * actually read — the point of a data export is that the subject can inspect
 * it, so stored analyses are embedded as real nested JSON rather than the
 * escaped {@code result_json} string they live as in the database.
 *
 * <p>Deliberately excludes {@code nutrition_cache}: it is keyed by dish name,
 * shared by every user, and holds no personal data. Nothing in it is "about"
 * the person asking, so including it would be noise rather than transparency —
 * and the same reasoning is why account deletion leaves it alone.
 */
public record AccountExport(
        Instant exportedAt,
        Profile profile,
        List<Meal> meals,
        List<MenuScan> menuScans,
        List<WaterDay> water,
        List<WeighIn> weighIns
) {

    public record Profile(
            String email,
            String name,
            String pictureUrl,
            Instant createdAt,
            Integer age,
            String sex,
            Double weightKg,
            Double heightCm,
            Integer steps,
            String exerciseFrequency,
            String goal,
            Integer dailyBudget,
            Integer waterTargetMl
    ) {
    }

    /** thumbnail is the stored base64 data URL — the only image the app keeps, so it belongs in the export. */
    public record Meal(
            Long id,
            Instant createdAt,
            String source,
            int score,
            String grade,
            double calories,
            String summary,
            String thumbnail,
            JsonNode result
    ) {
    }

    public record MenuScan(
            Long id,
            Instant createdAt,
            int dishCount,
            boolean truncated,
            String summary,
            String thumbnail,
            JsonNode result
    ) {
    }

    public record WaterDay(LocalDate date, int totalMl) {
    }

    public record WeighIn(Instant loggedAt, double weightKg) {
    }
}
