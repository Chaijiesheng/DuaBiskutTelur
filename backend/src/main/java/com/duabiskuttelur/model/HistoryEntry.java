package com.duabiskuttelur.model;

import java.time.Instant;

public record HistoryEntry(
        Long id,
        Instant createdAt,
        int score,
        String grade,
        double calories,
        String summary,
        String thumbnail,
        String source
) {
}
