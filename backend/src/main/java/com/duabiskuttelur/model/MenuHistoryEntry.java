package com.duabiskuttelur.model;

import java.time.Instant;

public record MenuHistoryEntry(
        Long id,
        Instant createdAt,
        int dishCount,
        boolean truncated,
        String summary,
        String thumbnail
) {
}
