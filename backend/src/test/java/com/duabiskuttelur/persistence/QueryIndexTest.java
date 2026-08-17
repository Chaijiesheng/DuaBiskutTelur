package com.duabiskuttelur.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An index only helps if its column order matches the query the repository
 * actually issues — get that wrong and the planner quietly ignores it, with no
 * error and no signal beyond a slow query nobody is measuring yet. The
 * migration passing says nothing about that, so this asks H2's own planner
 * which access path it picks for each real query shape.
 *
 * <p>Rows are seeded first because the choice is cost-based: against an empty
 * table a scan is the cheapest plan no matter what indexes exist, so an empty
 * fixture would pass whether or not the index were usable.
 *
 * <p><b>{@link #everyRepositoryQueryHasAPlanAssertion()} is the part that keeps
 * this file honest.</b> Hand-listed plan assertions only cover the queries that
 * existed when someone last thought about it — this file guarded six shapes
 * from V7's era while nine more were added underneath it, none of them checked.
 * The roster test fails the build when a repository gains a query method, so
 * the next one cannot be added without someone deciding whether it needs an
 * index. That is the same parity-table treatment {@code MacroTargets} and
 * {@code CalorieBudget} get, for the same reason: silent drift with no symptom.
 */
class QueryIndexTest {

    private static final int USERS = 5;
    private static final int ROWS_PER_USER = 200;
    /** One session per user per day; unique on (user_id, session_date) caps it at that. */
    private static final int WORKOUT_DAYS_PER_USER = 120;

    /**
     * Every query method declared on a repository, and the index its plan is
     * asserted against below. Inherited {@code JpaRepository} methods are not
     * listed: {@code findById}/{@code save}/{@code deleteById} are primary-key
     * operations by construction.
     *
     * <p>Adding a row here without a matching plan assertion defeats the point.
     */
    private static final Set<String> ASSERTED_QUERIES = Set.of(
            // idx_meal_analysis_user_created
            "MealAnalysisRepository#findHistoryEntries",
            "MealAnalysisRepository#findDailyFacts",
            "MealAnalysisRepository#findByUserIdOrderByCreatedAtDesc",
            "MealAnalysisRepository#findAchievementFacts",
            "MealAnalysisRepository#findLegacyAchievementFacts",
            "MealAnalysisRepository#findPointsSince",
            "MealAnalysisRepository#deleteByUserId",
            // primary key
            "MealAnalysisRepository#findByIdAndUserId",
            "MealAnalysisRepository#findResultJsonByIds",
            "MenuScanRepository#findByIdAndUserId",
            // idx_menu_scan_user_created
            "MenuScanRepository#findHistoryEntries",
            "MenuScanRepository#findByUserIdOrderByCreatedAtDesc",
            "MenuScanRepository#deleteByUserId",
            // idx_weight_entry_user_logged
            "WeightRepository#findByUserIdAndLoggedAtBetween",
            "WeightRepository#findByUserIdOrderByLoggedAtDesc",
            "WeightRepository#findFirstByUserIdOrderByLoggedAtDesc",
            "WeightRepository#deleteByUserId",
            // uk_water_entry_user_date (V6)
            "WaterRepository#findByUserIdAndDate",
            "WaterRepository#adjustTotal",
            "WaterRepository#findByUserIdOrderByDateDesc",
            "WaterRepository#deleteByUserId",
            // unique constraints that predate V7
            "UserRepository#findByGoogleSub",
            "NutritionCacheRepository#findByCanonicalName",
            // V10's own unique constraints
            "LocalFoodRepository#findByCanonicalName",
            "LocalFoodRepository#findByAlias",
            // V11 adds no index of its own -- every workout query is served by a
            // unique constraint's index, and the assertions below are what makes
            // that claim checkable rather than an assumption in a comment.
            // uk_workout_profile_user
            "WorkoutProfileRepository#findByUserId",
            "WorkoutProfileRepository#deleteByUserId",
            // uk_workout_session_user_date
            "WorkoutSessionRepository#findByUserIdAndSessionDate",
            "WorkoutSessionRepository#findByUserIdAndSessionDateBetweenOrderBySessionDateAsc",
            "WorkoutSessionRepository#findIdsByUserId",
            "WorkoutSessionRepository#deleteByUserId",
            // uk_workout_exercise_slot
            "WorkoutSessionExerciseRepository#findBySessionIdOrderByPositionAsc",
            "WorkoutSessionExerciseRepository#findBySessionIdAndPosition",
            "WorkoutSessionExerciseRepository#deleteBySessionIdIn",
            // uk_workout_set_log
            "WorkoutSetLogRepository#findBySessionId",
            "WorkoutSetLogRepository#existsBySessionIdAndExercisePositionAndSetIndex",
            "WorkoutSetLogRepository#deleteOne",
            "WorkoutSetLogRepository#deleteBySessionIdAndExercisePosition",
            "WorkoutSetLogRepository#deleteBySessionIdIn");

    private static final Class<?>[] REPOSITORIES = {
            MealAnalysisRepository.class, MenuScanRepository.class, WaterRepository.class,
            WeightRepository.class, UserRepository.class, NutritionCacheRepository.class,
            LocalFoodRepository.class, LocalFoodAliasRepository.class,
            WorkoutProfileRepository.class, WorkoutSessionRepository.class,
            WorkoutSessionExerciseRepository.class, WorkoutSetLogRepository.class};

    private String url;

    @BeforeEach
    void migrateAndSeed() throws SQLException {
        url = "jdbc:h2:mem:query-index-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            seed(conn);
        }
    }

    private static Instant base() {
        return Instant.now().minus(400, ChronoUnit.DAYS);
    }

    private void seed(Connection conn) throws SQLException {
        Instant base = base();

        // No foreign key requires these (see the handover on why FKs are not
        // worth adding on H2), but a fixture whose user_ids point at nothing
        // would misrepresent the shape the planner sees.
        try (PreparedStatement user = conn.prepareStatement(
                "INSERT INTO app_user (id, google_sub, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            for (long userId = 1; userId <= USERS; userId++) {
                user.setLong(1, userId);
                user.setString(2, "seed-sub-" + userId);
                user.addBatch();
            }
            user.executeBatch();
        }
        try (PreparedStatement meal = conn.prepareStatement(
                     "INSERT INTO meal_analysis (user_id, created_at, score, grade, calories, summary,"
                             + " vegetable_count) VALUES (?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement menu = conn.prepareStatement(
                     "INSERT INTO menu_scan (user_id, created_at, dish_count, truncated, summary)"
                             + " VALUES (?, ?, ?, FALSE, ?)");
             PreparedStatement weight = conn.prepareStatement(
                     "INSERT INTO weight_entry (user_id, weight_kg, logged_at) VALUES (?, ?, ?)");
             PreparedStatement water = conn.prepareStatement(
                     "INSERT INTO water_entry (user_id, date, total_ml) VALUES (?, ?, ?)")) {

            for (long userId = 1; userId <= USERS; userId++) {
                for (int i = 0; i < ROWS_PER_USER; i++) {
                    Timestamp at = Timestamp.from(base.plus(i * 3L, ChronoUnit.HOURS));

                    meal.setLong(1, userId);
                    meal.setTimestamp(2, at);
                    meal.setInt(3, 50 + (i % 50));
                    meal.setString(4, "B");
                    meal.setDouble(5, 600 + i);
                    meal.setString(6, "seeded meal " + i);
                    // A small pre-V2 tail with no denormalized columns, so the two
                    // achievement projections each have rows to find and the
                    // planner is choosing between them on realistic selectivity.
                    if (i < 10) {
                        meal.setNull(7, Types.INTEGER);
                    } else {
                        meal.setInt(7, i % 3);
                    }
                    meal.addBatch();

                    menu.setLong(1, userId);
                    menu.setTimestamp(2, at);
                    menu.setInt(3, 12);
                    menu.setString(4, "seeded menu " + i);
                    menu.addBatch();

                    weight.setLong(1, userId);
                    weight.setDouble(2, 70 + (i % 10));
                    weight.setTimestamp(3, at);
                    weight.addBatch();

                    // One row per user per day is all uk_water_entry_user_date allows.
                    water.setLong(1, userId);
                    water.setObject(2, base.plus(i, ChronoUnit.DAYS).atZone(ZoneOffset.UTC).toLocalDate());
                    water.setInt(3, 250 * (i % 8));
                    water.addBatch();
                }
            }
            meal.executeBatch();
            menu.executeBatch();
            weight.executeBatch();
            water.executeBatch();
        }
        seedWorkoutTables(conn);
        seedLookupTables(conn);
    }

    /**
     * Enough workout history that a table scan is not trivially the cheapest
     * plan. Ids are assigned explicitly so the child rows can point at real
     * parents without a round trip per insert — the shape the planner sees is
     * what matters here, not how the rows got there.
     */
    private void seedWorkoutTables(Connection conn) throws SQLException {
        LocalDate day0 = base().atZone(ZoneOffset.UTC).toLocalDate();
        try (PreparedStatement profile = conn.prepareStatement(
                     "INSERT INTO workout_profile (user_id, goal, level, days_per_week, session_minutes,"
                             + " equipment, created_at, updated_at) VALUES (?, 'maintain', 'beginner',"
                             + " 3, 30, 'none', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
             PreparedStatement session = conn.prepareStatement(
                     "INSERT INTO workout_session (id, user_id, session_date, title, focus, minutes,"
                             + " level, status, created_at) VALUES (?, ?, ?, 'Full Body', 'full_body',"
                             + " 30, 'beginner', 'completed', CURRENT_TIMESTAMP)");
             PreparedStatement exercise = conn.prepareStatement(
                     "INSERT INTO workout_session_exercise (session_id, position, exercise_key, name,"
                             + " target, sets, reps, unit) VALUES (?, ?, 'plank', 'Plank', 'Core',"
                             + " 3, 30, 'sec')");
             PreparedStatement setLog = conn.prepareStatement(
                     "INSERT INTO workout_set_log (session_id, exercise_position, set_index, completed_at)"
                             + " VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {

            long sessionId = 0;
            for (long userId = 1; userId <= USERS; userId++) {
                profile.setLong(1, userId);
                profile.addBatch();

                for (int day = 0; day < WORKOUT_DAYS_PER_USER; day++) {
                    sessionId++;
                    session.setLong(1, sessionId);
                    session.setLong(2, userId);
                    session.setObject(3, day0.plusDays(day));
                    session.addBatch();

                    for (int position = 0; position < 5; position++) {
                        exercise.setLong(1, sessionId);
                        exercise.setInt(2, position);
                        exercise.addBatch();

                        for (int setIndex = 0; setIndex < 3; setIndex++) {
                            setLog.setLong(1, sessionId);
                            setLog.setInt(2, position);
                            setLog.setInt(3, setIndex);
                            setLog.addBatch();
                        }
                    }
                }
            }
            profile.executeBatch();
            session.executeBatch();
            exercise.executeBatch();
            setLog.executeBatch();
        }
    }

    /**
     * The lookup tables are small on purpose — they are small in production too
     * (one row per dish, not per user), so this seeds enough rows that a scan is
     * not trivially the cheapest plan and the assertion means something.
     */
    private void seedLookupTables(Connection conn) throws SQLException {
        try (PreparedStatement local = conn.prepareStatement(
                     "INSERT INTO local_food (canonical_name, display_name, typical_grams,"
                             + " calories_per100g, protein_per100g, carbs_per100g, fat_per100g,"
                             + " fiber_per100g, sugar_per100g, sodium_per100g, source)"
                             + " VALUES (?, ?, 200, 200, 5, 20, 8, 2, 3, 300, 'curated')");
             PreparedStatement alias = conn.prepareStatement(
                     "INSERT INTO local_food_alias (alias, local_food_id) VALUES (?, ?)");
             PreparedStatement cache = conn.prepareStatement(
                     "INSERT INTO nutrition_cache (canonical_name, display_name, resolved_at, source,"
                             + " fried, confidence, grams, calories_per100g, protein_per100g,"
                             + " carbs_per100g, fat_per100g, fiber_per100g, sugar_per100g, sodium_per100g)"
                             + " VALUES (?, ?, CURRENT_TIMESTAMP, 'usda', FALSE, 0.9,"
                             + " 200, 200, 5, 20, 8, 2, 3, 300)")) {
            for (int i = 0; i < 300; i++) {
                local.setString(1, "seeded dish " + i);
                local.setString(2, "Seeded Dish " + i);
                local.addBatch();

                alias.setString(1, "seeded alias " + i);
                alias.setLong(2, i + 1L);
                alias.addBatch();

                cache.setString(1, "seeded dish " + i);
                cache.setString(2, "Seeded Dish " + i);
                cache.addBatch();
            }
            local.executeBatch();
            alias.executeBatch();
            cache.executeBatch();
        }
    }

    // ---------------------------------------------------------------- meal_analysis

    @Test
    void historyListSeeksTheUserCreatedIndexInsteadOfScanning() throws SQLException {
        // MealAnalysisRepository.findHistoryEntries — a projection, so result_json
        // is absent from the column list on purpose (DB4).
        assertUsesIndex("IDX_MEAL_ANALYSIS_USER_CREATED",
                "SELECT id, created_at, score, grade, calories, summary, thumbnail, source"
                        + " FROM meal_analysis WHERE user_id = 3 ORDER BY created_at DESC LIMIT 50");
    }

    @Test
    void todaysDashboardRangeSeeksTheUserCreatedIndex() throws SQLException {
        // DashboardService.todaysEntries via findDailyFacts — the same index as
        // the list above, which is why one index covers both. Runs on every
        // analysis too, not just dashboard loads, and touches neither CLOB.
        assertUsesIndex("IDX_MEAL_ANALYSIS_USER_CREATED",
                "SELECT id, score, calories, protein FROM meal_analysis WHERE user_id = 3"
                        + " AND created_at BETWEEN TIMESTAMP '2026-01-01 00:00:00' AND TIMESTAMP '2026-01-02 00:00:00'");
    }

    /**
     * findResultJsonByIds — the pre-V2 protein fallback. Keyed by id so it is a
     * primary-key seek over the handful of rows that need it, and does not run
     * at all when none do.
     */
    @Test
    void theLegacyProteinFallbackSeeksThePrimaryKey() throws SQLException {
        assertUsesIndex("PRIMARY_KEY",
                "SELECT result_json FROM meal_analysis WHERE id IN (17, 42, 99)");
    }

    @Test
    void achievementsFullHistorySeeksTheUserCreatedIndex() throws SQLException {
        // AchievementsService via findByUserIdOrderByCreatedAtDesc
        assertUsesIndex("IDX_MEAL_ANALYSIS_USER_CREATED",
                "SELECT * FROM meal_analysis WHERE user_id = 3 ORDER BY created_at DESC");
    }

    /**
     * Both achievement projections (B4). Neither predicate is indexable on its
     * own — {@code vegetable_count} carries no index and deliberately so, since
     * it only ever appears alongside an equality on {@code user_id} — so what
     * matters is that the user's rows are still reached through the index
     * rather than by scanning the table for the null test.
     */
    @Test
    void bothAchievementProjectionsSeekTheUserCreatedIndex() throws SQLException {
        assertUsesIndex("IDX_MEAL_ANALYSIS_USER_CREATED",
                "SELECT created_at, grade FROM meal_analysis WHERE user_id = 3"
                        + " AND vegetable_count IS NOT NULL ORDER BY created_at DESC");
        assertUsesIndex("IDX_MEAL_ANALYSIS_USER_CREATED",
                "SELECT created_at, grade FROM meal_analysis WHERE user_id = 3"
                        + " AND vegetable_count IS NULL ORDER BY created_at DESC");
    }

    /**
     * findPointsSince — the weekly trend, added after V7 and uncapped by design,
     * so it is the one meal query whose cost grows with the window rather than
     * being clipped at fifty rows.
     */
    @Test
    void weeklyTrendWindowSeeksTheUserCreatedIndex() throws SQLException {
        assertUsesIndex("IDX_MEAL_ANALYSIS_USER_CREATED",
                "SELECT id, created_at, calories FROM meal_analysis WHERE user_id = 3"
                        + " AND created_at >= TIMESTAMP '2026-01-01 00:00:00' ORDER BY created_at DESC");
    }

    /** findByIdAndUserId — the ownership check on a history detail view. */
    @Test
    void mealDetailSeeksThePrimaryKeyRatherThanTheUserIndex() throws SQLException {
        assertUsesIndex("PRIMARY_KEY", "SELECT * FROM meal_analysis WHERE id = 17 AND user_id = 3");
        assertUsesIndex("PRIMARY_KEY", "SELECT * FROM menu_scan WHERE id = 17 AND user_id = 3");
    }

    // ---------------------------------------------------------------- other tables

    @Test
    void menuHistoryListSeeksItsOwnIndex() throws SQLException {
        // findHistoryEntries (projection, no result_json) and the uncapped
        // findByUserIdOrderByCreatedAtDesc the export uses.
        assertUsesIndex("IDX_MENU_SCAN_USER_CREATED",
                "SELECT id, created_at, dish_count, truncated, summary, thumbnail"
                        + " FROM menu_scan WHERE user_id = 3 ORDER BY created_at DESC LIMIT 50");
        assertUsesIndex("IDX_MENU_SCAN_USER_CREATED",
                "SELECT * FROM menu_scan WHERE user_id = 3 ORDER BY created_at DESC");
    }

    @Test
    void weightTrendWindowSeeksItsOwnIndex() throws SQLException {
        assertUsesIndex("IDX_WEIGHT_ENTRY_USER_LOGGED",
                "SELECT * FROM weight_entry WHERE user_id = 3"
                        + " AND logged_at BETWEEN TIMESTAMP '2026-01-01 00:00:00' AND TIMESTAMP '2026-03-01 00:00:00'");
    }

    /** V7 deliberately adds nothing for water_entry; this is why that's correct. */
    @Test
    void waterLookupIsAlreadyCoveredByItsUniqueConstraint() throws SQLException {
        assertUsesIndex("UK_WATER_ENTRY_USER_DATE",
                "SELECT * FROM water_entry WHERE user_id = 3 AND date = DATE '2026-01-01'");
        // adjustTotal — the atomic per-tap update, on the same two columns.
        assertUsesIndex("UK_WATER_ENTRY_USER_DATE",
                "UPDATE water_entry SET total_ml = total_ml + 250"
                        + " WHERE user_id = 3 AND date = DATE '2026-01-01'");
    }

    /**
     * The account export reads every water and weight row a user has, unordered
     * by anything the index does not already provide. Uncapped, like the trend —
     * an export has to be complete — so an unindexed one would scan the whole
     * table per export.
     */
    @Test
    void accountExportListsSeekTheirUserIndexes() throws SQLException {
        assertUsesIndex("UK_WATER_ENTRY_USER_DATE",
                "SELECT * FROM water_entry WHERE user_id = 3 ORDER BY date DESC");
        assertUsesIndex("IDX_WEIGHT_ENTRY_USER_LOGGED",
                "SELECT * FROM weight_entry WHERE user_id = 3 ORDER BY logged_at DESC");
    }

    /**
     * V11 adds no CREATE INDEX at all, on the claim that every workout query is
     * already served by a unique constraint's own index. That claim is only
     * worth making if something checks it — an extra index costs writes, and a
     * missing one costs a scan, and neither has any symptom but latency.
     */
    @Test
    void everyWorkoutQueryIsServedByAUniqueConstraintsIndex() throws SQLException {
        // uk_workout_profile_user — the whole profile read, once per dashboard.
        assertUsesIndex("UK_WORKOUT_PROFILE_USER", "SELECT * FROM workout_profile WHERE user_id = 3");
        assertUsesIndex("UK_WORKOUT_PROFILE_USER", "DELETE FROM workout_profile WHERE user_id = 3");

        // uk_workout_session_user_date — today's session, the week strip window,
        // the id sweep erasure walks, and the bulk delete.
        assertUsesIndex("UK_WORKOUT_SESSION_USER_DATE",
                "SELECT * FROM workout_session WHERE user_id = 3 AND session_date = DATE '2026-08-17'");
        assertUsesIndex("UK_WORKOUT_SESSION_USER_DATE",
                "SELECT * FROM workout_session WHERE user_id = 3"
                        + " AND session_date BETWEEN DATE '2026-07-01' AND DATE '2026-08-17'"
                        + " ORDER BY session_date ASC");
        assertUsesIndex("UK_WORKOUT_SESSION_USER_DATE", "SELECT id FROM workout_session WHERE user_id = 3");
        assertUsesIndex("UK_WORKOUT_SESSION_USER_DATE", "DELETE FROM workout_session WHERE user_id = 3");

        // uk_workout_exercise_slot — read whole, read by slot, deleted by session.
        assertUsesIndex("UK_WORKOUT_EXERCISE_SLOT",
                "SELECT * FROM workout_session_exercise WHERE session_id = 3 ORDER BY position ASC");
        assertUsesIndex("UK_WORKOUT_EXERCISE_SLOT",
                "SELECT * FROM workout_session_exercise WHERE session_id = 3 AND position = 2");
        assertUsesIndex("UK_WORKOUT_EXERCISE_SLOT",
                "DELETE FROM workout_session_exercise WHERE session_id IN (3, 4)");

        // uk_workout_set_log — the idempotence probe runs on every logged set,
        // which is the highest-frequency write in the feature.
        assertUsesIndex("UK_WORKOUT_SET_LOG", "SELECT * FROM workout_set_log WHERE session_id = 3");
        assertUsesIndex("UK_WORKOUT_SET_LOG",
                "SELECT COUNT(*) FROM workout_set_log"
                        + " WHERE session_id = 3 AND exercise_position = 1 AND set_index = 2");
        assertUsesIndex("UK_WORKOUT_SET_LOG",
                "DELETE FROM workout_set_log WHERE session_id = 3 AND exercise_position = 1 AND set_index = 2");
        assertUsesIndex("UK_WORKOUT_SET_LOG",
                "DELETE FROM workout_set_log WHERE session_id = 3 AND exercise_position = 1");
        assertUsesIndex("UK_WORKOUT_SET_LOG", "DELETE FROM workout_set_log WHERE session_id IN (3, 4)");
    }

    /**
     * Account deletion issues one bulk delete per child table. These are the
     * only writes in the app whose predicate is user_id alone, and an unindexed
     * one would scan four tables to delete one account.
     */
    @Test
    void bulkDeletesSeekTheirUserIndexes() throws SQLException {
        assertUsesIndex("IDX_MEAL_ANALYSIS_USER_CREATED", "DELETE FROM meal_analysis WHERE user_id = 3");
        assertUsesIndex("IDX_MENU_SCAN_USER_CREATED", "DELETE FROM menu_scan WHERE user_id = 3");
        assertUsesIndex("IDX_WEIGHT_ENTRY_USER_LOGGED", "DELETE FROM weight_entry WHERE user_id = 3");
        assertUsesIndex("UK_WATER_ENTRY_USER_DATE", "DELETE FROM water_entry WHERE user_id = 3");
    }

    // ---------------------------------------------------------------- lookup tables

    @Test
    void userAndNutritionCacheLookupsSeekTheirUniqueConstraints() throws SQLException {
        assertUsesIndex("UK_APP_USER_GOOGLE_SUB", "SELECT * FROM app_user WHERE google_sub = 'seed-sub-3'");
        assertUsesIndex("UK_NUTRITION_CACHE_CANONICAL_NAME",
                "SELECT * FROM nutrition_cache WHERE canonical_name = 'seeded dish 7'");
    }

    /**
     * A menu scan runs the alias lookup up to sixty times per request, which is
     * the whole reason aliases are a joined table rather than a delimited column
     * (V10). Both hops have to be index seeks for that argument to hold.
     */
    @Test
    void localFoodLookupsSeekTheirUniqueConstraints() throws SQLException {
        assertUsesIndex("UK_LOCAL_FOOD_CANONICAL_NAME",
                "SELECT * FROM local_food WHERE canonical_name = 'seeded dish 7'");

        String plan = explain("SELECT * FROM local_food f WHERE f.id ="
                + " (SELECT a.local_food_id FROM local_food_alias a WHERE a.alias = 'seeded alias 7')");
        assertTrue(plan.contains("UK_LOCAL_FOOD_ALIAS"),
                "the alias hop should seek its unique index, not scan.\nPlan:\n" + plan);
        assertTrue(plan.contains("PRIMARY_KEY"),
                "the local_food hop should be a primary-key seek.\nPlan:\n" + plan);
    }

    // ---------------------------------------------------------------- the roster

    /**
     * Fails when a repository gains or loses a query method, so no query can be
     * added without someone deciding which index serves it. The failure message
     * is the actionable part: it names the method that has no plan assertion.
     */
    @Test
    void everyRepositoryQueryHasAPlanAssertion() {
        Set<String> declared = new TreeSet<>();
        for (Class<?> repository : REPOSITORIES) {
            for (Method method : repository.getDeclaredMethods()) {
                declared.add(repository.getSimpleName() + "#" + method.getName());
            }
        }

        // The delta, not the two sets. assertEquals on 23-element sets prints
        // both in full and buries the one name that matters.
        Set<String> unguarded = new TreeSet<>(declared);
        unguarded.removeAll(ASSERTED_QUERIES);
        Set<String> stale = new TreeSet<>(ASSERTED_QUERIES);
        stale.removeAll(declared);

        assertEquals(Set.of(), unguarded,
                "these repository queries have no plan assertion in this file. Add each query's shape "
                        + "above and list it in ASSERTED_QUERIES — an unindexed per-user query is a full "
                        + "table scan with no symptom but latency.");
        assertEquals(Set.of(), stale,
                "ASSERTED_QUERIES lists repository queries that no longer exist. Remove them, along with "
                        + "the plan assertion above — and check whether an index went with them.");
    }

    // ---------------------------------------------------------------- helpers

    private void assertUsesIndex(String expectedIndex, String query) throws SQLException {
        String plan = explain(query);
        assertTrue(plan.contains(expectedIndex),
                "expected " + expectedIndex + " to be used.\nQuery: " + query + "\nPlan:\n" + plan);
    }

    private String explain(String query) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN " + query)) {
            rs.next();
            return rs.getString(1).toUpperCase(Locale.ROOT);
        }
    }
}
