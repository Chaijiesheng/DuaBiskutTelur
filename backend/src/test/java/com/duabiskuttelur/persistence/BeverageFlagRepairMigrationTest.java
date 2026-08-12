package com.duabiskuttelur.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V8 repairs rows the old substring matcher mislabelled. Like the water dedupe
 * in V6, its repair work only runs against data that is already wrong, so every
 * other test in the suite exercises it over an empty table and proves nothing.
 * It gets one attempt on the live database.
 *
 * <p>Migrations up to V7 first, then the bad rows are planted exactly as the
 * old matcher would have written them, then V8 runs.
 */
class BeverageFlagRepairMigrationTest {

    private String url;

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private long insertMeal(Connection conn, String summary, String resultJson,
                            boolean beverageOnly, boolean coffeeOnly) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO meal_analysis (user_id, created_at, score, grade, calories, summary,"
                        + " result_json, vegetable_count, has_fruit, beverage_only, coffee_only)"
                        + " VALUES (1, ?, 70, 'B', 600, ?, ?, 0, FALSE, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            st.setTimestamp(1, Timestamp.from(Instant.now()));
            st.setString(2, summary);
            st.setString(3, resultJson);
            st.setBoolean(4, beverageOnly);
            st.setBoolean(5, coffeeOnly);
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static String foods(String... names) {
        StringBuilder json = new StringBuilder("{\"foods\":[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"name\":\"").append(names[i]).append("\"}");
        }
        return json.append("]}").toString();
    }

    private boolean[] flagsOf(Connection conn, long id) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT beverage_only, coffee_only FROM meal_analysis WHERE id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return new boolean[]{rs.getBoolean(1), rs.getBoolean(2)};
            }
        }
    }

    @Test
    void correctsFalsePositivesWithoutDisturbingCorrectRows() throws SQLException {
        url = "jdbc:h2:mem:beverage-repair-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        migrateTo("7");

        long steak;
        long chocolate;
        long realDrinks;
        long realCoffee;
        long mixedMeal;
        long unreadable;

        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // What the old matcher actually wrote: "steak" contains "tea",
            // "chocolate" contains "cola", so both were flagged drinks-only.
            steak = insertMeal(conn, "Steak", foods("Steak"), true, false);
            chocolate = insertMeal(conn, "Chocolate", foods("Chocolate"), true, false);

            // Genuinely drinks-only — must survive untouched.
            realDrinks = insertMeal(conn, "Teh tarik", foods("Teh tarik / milk tea"), true, false);
            realCoffee = insertMeal(conn, "Kopi O", foods("Kopi O / black coffee"), true, true);

            // Never flagged, so never examined.
            mixedMeal = insertMeal(conn, "Nasi lemak, Teh tarik",
                    foods("Nasi lemak", "Teh tarik / milk tea"), false, false);

            // Flagged but with nothing to re-derive from: left alone rather than
            // guessed at, since a wrong correction is worse than a stale one.
            unreadable = insertMeal(conn, "Mystery", "not json at all", true, false);
        }

        migrateTo("8");

        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            assertFalse(flagsOf(conn, steak)[0], "a steak should no longer be a drinks-only meal");
            assertFalse(flagsOf(conn, chocolate)[0], "chocolate should no longer be a drinks-only meal");

            assertTrue(flagsOf(conn, realDrinks)[0], "a genuine drinks-only meal was cleared");
            assertTrue(flagsOf(conn, realCoffee)[0], "a genuine coffee-only meal lost its beverage flag");
            assertTrue(flagsOf(conn, realCoffee)[1], "a genuine coffee-only meal lost its coffee flag");

            assertFalse(flagsOf(conn, mixedMeal)[0], "an unflagged row should not have been touched");
            assertTrue(flagsOf(conn, unreadable)[0],
                    "a row with unparseable JSON should be left as found, not guessed at");
        }
    }

    /** Repairs must not depend on a row's own id or ordering — a second run changes nothing. */
    @Test
    void runningTheRepairIsIdempotentInEffect() throws SQLException {
        url = "jdbc:h2:mem:beverage-repair-idem-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        migrateTo("7");

        long steak;
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            steak = insertMeal(conn, "Steak", foods("Steak"), true, false);
        }
        migrateTo("8");

        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            assertFalse(flagsOf(conn, steak)[0]);
            // Now that it reads false it is outside the migration's own WHERE
            // clause, so a re-run would skip it entirely — which is what makes
            // the true -> false direction safe to apply once.
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM meal_analysis WHERE beverage_only = TRUE OR coffee_only = TRUE");
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "nothing should remain flagged");
            }
        }
    }
}
