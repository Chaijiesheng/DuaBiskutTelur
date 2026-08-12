package com.duabiskuttelur.persistence;

import java.time.Instant;

/**
 * The seven columns the achievement catalog is actually computed from.
 *
 * <p>Exists so that reading them doesn't drag {@code thumbnail} and
 * {@code result_json} along with it. Both are CLOBs on the same row — roughly
 * 6 KB and 3 KB each — and the catalog is recomputed from a user's entire
 * history on every request, so loading whole entities meant a user with a
 * thousand meals moved ~9 MB per Profile tab open to read a handful of ints
 * and booleans.
 */
public interface AchievementFacts {

    Instant getCreatedAt();

    String getGrade();

    String getSummary();

    Integer getVegetableCount();

    Boolean getHasFruit();

    Boolean getBeverageOnly();

    Boolean getCoffeeOnly();
}
