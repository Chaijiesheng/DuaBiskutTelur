package com.duabiskuttelur.service;

import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.MenuScanEntity;
import com.duabiskuttelur.persistence.MenuScanRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.UserRepository;
import com.duabiskuttelur.persistence.WaterEntity;
import com.duabiskuttelur.persistence.WaterRepository;
import com.duabiskuttelur.persistence.WeightEntity;
import com.duabiskuttelur.persistence.WeightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB3 asked for foreign keys so that deleting an account cascades and orphan
 * rows become structurally impossible. They are <b>not</b> being added, and the
 * reason was re-measured rather than remembered: H2 auto-creates a
 * single-column index for every foreign key and refuses to drop it ({@code
 * Index "FK_MEAL_ANALYSIS_USER_INDEX_1" belongs to constraint
 * "FK_MEAL_ANALYSIS_USER"}), after which the planner prefers that narrower index
 * for {@code WHERE user_id = ?} and the history list, the achievements read and
 * the menu list all lose V7's index ordering and go back to sorting. Postgres
 * does not auto-create them, so this becomes free on the exit path.
 *
 * <p>Meanwhile the integrity DB3 wanted is upheld in code, by
 * {@code AccountDataService.deleteAccount} listing each child table explicitly.
 * Its javadoc names the hazard exactly — "a table added later and not listed
 * here would silently outlive the account it belongs to" — and until now that
 * was a comment, which is not a mechanism. A cascade cannot be forgotten; a
 * hand-written list can, and the symptom would be personal data surviving an
 * erasure request with nothing to show for it.
 *
 * <p>So this asks the live schema which tables are user-scoped, rather than
 * trusting a list written by the same person who wrote the delete.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:deletion-completeness-test;DB_CLOSE_DELAY=-1"
})
class AccountDeletionCompletenessTest {

    /**
     * Every table carrying a {@code user_id}, and what clears it. Adding a
     * user-scoped table without wiring it into {@code deleteAccount} fails
     * {@link #theSchemaHasNoUserScopedTableThisTestDoesNotKnowAbout()}.
     *
     * <p>{@code nutrition_cache} and {@code local_food} are absent because they
     * carry no {@code user_id} at all — they are keyed by dish name, shared by
     * everyone, and hold nothing about anybody. That is a property of the
     * schema, so the query below excludes them on its own rather than needing an
     * exemption here.
     */
    private static final Set<String> USER_SCOPED_TABLES = Set.of(
            "MEAL_ANALYSIS", "MENU_SCAN", "WATER_ENTRY", "WEIGHT_ENTRY");

    @Autowired private DataSource dataSource;
    @Autowired private AccountDataService accountDataService;
    @Autowired private UserRepository userRepository;
    @Autowired private MealAnalysisRepository mealRepository;
    @Autowired private MenuScanRepository menuScanRepository;
    @Autowired private WaterRepository waterRepository;
    @Autowired private WeightRepository weightRepository;

    private UserEntity leaving;
    private UserEntity staying;

    @BeforeEach
    void seedTwoAccounts() {
        mealRepository.deleteAll();
        menuScanRepository.deleteAll();
        waterRepository.deleteAll();
        weightRepository.deleteAll();
        userRepository.deleteAll();

        leaving = newUser("leaving-sub");
        staying = newUser("staying-sub");
        // Both users get a row in every user-scoped table, so the test can tell
        // "deleted everything" apart from "deleted everyone's everything".
        for (UserEntity user : new UserEntity[]{leaving, staying}) {
            seedChildRows(user.getId(), LocalDate.now().minusDays(user == leaving ? 0 : 1));
        }
    }

    private UserEntity newUser(String googleSub) {
        UserEntity user = new UserEntity();
        user.setGoogleSub(googleSub);
        user.setEmail(googleSub + "@example.test");
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private void seedChildRows(Long userId, LocalDate waterDay) {
        MealAnalysisEntity meal = new MealAnalysisEntity();
        meal.setUserId(userId);
        meal.setCreatedAt(Instant.now());
        meal.setScore(72);
        meal.setGrade("B");
        meal.setCalories(640);
        meal.setSummary("seeded meal");
        mealRepository.save(meal);

        MenuScanEntity menu = new MenuScanEntity();
        menu.setUserId(userId);
        menu.setCreatedAt(Instant.now());
        menu.setDishCount(12);
        menu.setTruncated(false);
        menu.setSummary("seeded menu");
        menuScanRepository.save(menu);

        WaterEntity water = new WaterEntity();
        water.setUserId(userId);
        water.setDate(waterDay);
        water.setTotalMl(1500);
        waterRepository.save(water);

        WeightEntity weight = new WeightEntity();
        weight.setUserId(userId);
        weight.setWeightKg(70.5);
        weight.setLoggedAt(Instant.now());
        weightRepository.save(weight);
    }

    /**
     * The roster. Reads the live schema, so a migration that adds a user-scoped
     * table fails here until someone decides how erasure reaches it.
     */
    @Test
    void theSchemaHasNoUserScopedTableThisTestDoesNotKnowAbout() throws SQLException {
        assertEquals(new TreeSet<>(USER_SCOPED_TABLES), new TreeSet<>(userScopedTables()),
                "a table carrying user_id is not covered by this test, or a covered one is gone. "
                        + "There are no foreign keys to cascade through, so AccountDataService.deleteAccount "
                        + "must clear it explicitly — otherwise a deleted account's rows outlive it.");
    }

    /** Erasure actually empties every one of them — asked of the schema, not of a list. */
    @Test
    void deletingAnAccountLeavesNoRowInAnyUserScopedTable() throws SQLException {
        Long leavingId = leaving.getId();
        Map<String, Integer> before = rowCountsFor(leavingId);
        assertTrue(before.values().stream().allMatch(count -> count > 0),
                "the fixture must put a row in every user-scoped table, otherwise this passes vacuously: "
                        + before);

        accountDataService.deleteAccount(leaving);

        assertEquals(Map.of(), nonEmpty(rowCountsFor(leavingId)),
                "these tables still hold rows for the deleted account");
        assertTrue(userRepository.findByGoogleSub("leaving-sub").isEmpty(), "the account row survived");
    }

    /** The same sweep must not take anyone else's rows with it. */
    @Test
    void deletingAnAccountLeavesEveryOtherAccountIntact() throws SQLException {
        accountDataService.deleteAccount(leaving);

        Map<String, Integer> survivors = rowCountsFor(staying.getId());
        assertEquals(USER_SCOPED_TABLES.size(), nonEmpty(survivors).size(),
                "the other account lost rows to a delete that was not theirs: " + survivors);
        assertTrue(userRepository.findByGoogleSub("staying-sub").isPresent(), "the wrong account was deleted");
    }

    // ---------------------------------------------------------------- helpers

    /** Every base table in the app schema with a {@code user_id} column. */
    private Set<String> userScopedTables() throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT c.table_name FROM information_schema.columns c
                       JOIN information_schema.tables t
                         ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                      WHERE c.table_schema = 'PUBLIC' AND c.column_name = 'USER_ID'
                        AND t.table_type = 'BASE TABLE'
                     """);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                tables.add(rs.getString(1).toUpperCase(java.util.Locale.ROOT));
            }
        }
        return tables;
    }

    private Map<String, Integer> rowCountsFor(Long userId) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            for (String table : userScopedTables()) {
                // The table name comes from information_schema, not from input.
                try (PreparedStatement st = conn.prepareStatement(
                        "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?")) {
                    st.setLong(1, userId);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        counts.put(table, rs.getInt(1));
                    }
                }
            }
        }
        return counts;
    }

    private static Map<String, Integer> nonEmpty(Map<String, Integer> counts) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        counts.forEach((table, count) -> {
            if (count > 0) {
                remaining.put(table, count);
            }
        });
        return remaining;
    }
}
