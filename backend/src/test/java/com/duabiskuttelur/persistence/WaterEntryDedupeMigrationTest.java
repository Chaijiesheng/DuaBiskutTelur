package com.duabiskuttelur.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V6 is the one migration in this project that has real repair work to do: it
 * collapses the duplicate water_entry rows the pre-V6 read-modify-write could
 * create before adding the constraint that prevents more of them. Every other
 * test boots against an empty database, so the dedupe statements run over zero
 * rows there and only their syntax is exercised — yet on the live database they
 * get exactly one attempt at rows that are already broken, with no second run
 * to fix a mistake.
 *
 * <p>This drives the real migration files: apply everything up to V5, plant the
 * duplicates the old code produced, then let V6 run and check what survived.
 */
class WaterEntryDedupeMigrationTest {

    private static final String USER_WITH_DUPES = "1";
    private static final String OTHER_USER = "2";

    private String freshDatabaseUrl() {
        return "jdbc:h2:mem:water-dedupe-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private void migrateTo(String url, String version) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    @Test
    void collapsesDuplicateDaysOntoOneRowAndThenBlocksNewOnes() throws SQLException {
        String url = freshDatabaseUrl();
        migrateTo(url, "5");

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement st = conn.createStatement()) {

            // Two racing first-taps on 2026-07-01 produced two rival running
            // totals for one user/day; the same user has a clean second day, and
            // another user has an untouched row that must survive intact.
            st.execute("INSERT INTO water_entry (user_id, date, total_ml) VALUES "
                    + "(" + USER_WITH_DUPES + ", DATE '2026-07-01', 300),"
                    + "(" + USER_WITH_DUPES + ", DATE '2026-07-01', 700),"
                    + "(" + USER_WITH_DUPES + ", DATE '2026-07-02', 1500),"
                    + "(" + OTHER_USER + ", DATE '2026-07-01', 250)");

            migrateTo(url, "6");

            assertEquals(1, countRows(st, USER_WITH_DUPES, "2026-07-01"),
                    "the duplicated day must collapse to a single row");
            assertEquals(700, totalMl(st, USER_WITH_DUPES, "2026-07-01"),
                    "the larger rival total is the closest surviving record of the day's taps");
            assertEquals(1500, totalMl(st, USER_WITH_DUPES, "2026-07-02"),
                    "a day that was never duplicated must be left exactly as it was");
            assertEquals(250, totalMl(st, OTHER_USER, "2026-07-01"),
                    "same date, different user — dedupe must key on the pair, not the date alone");

            // The constraint is what stops this recurring, so prove it is live
            // rather than trusting that the ALTER TABLE parsed.
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO water_entry (user_id, date, total_ml) VALUES "
                            + "(" + USER_WITH_DUPES + ", DATE '2026-07-01', 100)"),
                    "uk_water_entry_user_date must reject a second row for the same user/day");
        }
    }

    private int countRows(Statement st, String userId, String date) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM water_entry"
                + " WHERE user_id = " + userId + " AND date = DATE '" + date + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int totalMl(Statement st, String userId, String date) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT total_ml FROM water_entry"
                + " WHERE user_id = " + userId + " AND date = DATE '" + date + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
