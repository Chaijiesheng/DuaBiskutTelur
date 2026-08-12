package com.duabiskuttelur.persistence;

import java.time.Instant;

/**
 * The same facts for rows saved before V2 added the denormalized columns, where
 * they can only be recovered by parsing {@code result_json}.
 *
 * <p>Split into its own query rather than widening {@link AchievementFacts} so
 * the CLOB is fetched only for the rows that genuinely need it. That set is
 * fixed and shrinking — every row written since V2 populates the columns — so
 * the expensive path costs nothing on an account created after that point.
 */
public interface LegacyAchievementFacts {

    Instant getCreatedAt();

    String getGrade();

    String getSummary();

    String getResultJson();
}
