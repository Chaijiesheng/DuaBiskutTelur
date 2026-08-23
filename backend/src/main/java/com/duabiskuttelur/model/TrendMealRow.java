package com.duabiskuttelur.model;

import java.time.Instant;

/**
 * One meal reduced to what a trend report adds up.
 *
 * <p>Six narrow columns instead of the entity, for the same reason
 * {@link DailyMealFact} exists: {@code meal_analysis} carries {@code thumbnail}
 * (~6 KB) and {@code result_json} (~3 KB) inline, and a month of meals read as
 * entities would drag roughly a megabyte through the buffer to compute a
 * handful of averages.
 *
 * <p>{@code protein}, {@code vegetableCount} and {@code hasFruit} are nullable
 * on purpose. They were added by later migrations, so rows written before them
 * genuinely have no value — and "no vegetables recorded" is a different fact
 * from "zero vegetables", which is what lets the report decline to average a
 * column rather than quietly reporting a low number that only means the data is
 * old.
 */
public record TrendMealRow(
        Instant createdAt,
        int score,
        double calories,
        Double protein,
        Integer vegetableCount,
        Boolean hasFruit
) {
}
