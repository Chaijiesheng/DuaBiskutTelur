package com.duabiskuttelur.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.duabiskuttelur.model.HistoryEntry;
import com.duabiskuttelur.model.MenuHistoryEntry;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.MenuScanEntity;
import com.duabiskuttelur.persistence.MenuScanRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB4: {@code thumbnail} (~6 KB of base64) and {@code result_json} (~3 KB) sit
 * inline in {@code meal_analysis} and {@code menu_scan}, so any read of those
 * rows drags both through the buffer whether or not it wants them. The review's
 * suggested remedy was a separate {@code meal_analysis_blob} table behind a 1:1
 * lazy association. That is not what was done, for two reasons: a lazy
 * {@code @OneToOne} on the owning side is <em>not</em> reliably lazy — Hibernate
 * has to know whether the row exists, so it issues the query anyway unless the
 * association is optional=false and mapped from the other side — so the
 * migration could easily buy nothing; and the benefit it is after is available
 * without a schema change at all, by not selecting the columns.
 *
 * <p>So the three hot reads became projections, and this is the test that can
 * tell. <b>The results are byte-identical either way</b> — same list, same
 * totals, same JSON on the wire — so a behavioural test passes with every
 * projection reverted to a full entity read. Only the emitted SQL knows.
 *
 * <p>{@code AchievementsQueryShapeTest} makes the same argument for the
 * achievements catalog (B4); this covers the three that were left.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:hot-read-shape-test;DB_CLOSE_DELAY=-1"
})
class HotReadQueryShapeTest {

    private static final String BIG_THUMBNAIL = "data:image/jpeg;base64," + "A".repeat(6_000);
    private static final String STORED_RESULT =
            "{\"totals\":{\"calories\":600,\"protein\":30.0},\"foods\":[]}";

    @Autowired private AnalysisService analysisService;
    @Autowired private MenuRankingService menuRankingService;
    @Autowired private DashboardService dashboardService;
    @Autowired private MealAnalysisRepository mealRepository;
    @Autowired private MenuScanRepository menuScanRepository;
    @Autowired private UserRepository userRepository;

    private ch.qos.logback.classic.Logger sqlLogger;
    private ListAppender<ILoggingEvent> statements;
    private Level originalLevel;
    private UserEntity user;

    @BeforeEach
    void captureSqlAndSeed() {
        mealRepository.deleteAll();
        menuScanRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity fresh = new UserEntity();
        fresh.setGoogleSub("hot-read-sub");
        fresh.setCreatedAt(Instant.now());
        user = userRepository.save(fresh);

        sqlLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        originalLevel = sqlLogger.getLevel();
        sqlLogger.setLevel(Level.DEBUG);
        statements = new ListAppender<>();
        statements.start();
        sqlLogger.addAppender(statements);
    }

    @AfterEach
    void releaseSqlLogger() {
        sqlLogger.detachAppender(statements);
        statements.stop();
        sqlLogger.setLevel(originalLevel);
    }

    private void saveMeal(String summary, Double protein) {
        MealAnalysisEntity meal = new MealAnalysisEntity();
        meal.setUserId(user.getId());
        meal.setCreatedAt(Instant.now().minus(mealRepository.count() + 1, ChronoUnit.MINUTES));
        meal.setScore(80);
        meal.setGrade("A");
        meal.setCalories(600);
        meal.setSummary(summary);
        meal.setThumbnail(BIG_THUMBNAIL);
        meal.setResultJson(STORED_RESULT);
        meal.setProtein(protein);
        mealRepository.save(meal);
    }

    private void saveMenuScan(String summary) {
        MenuScanEntity scan = new MenuScanEntity();
        scan.setUserId(user.getId());
        scan.setCreatedAt(Instant.now().minus(menuScanRepository.count() + 1, ChronoUnit.MINUTES));
        scan.setDishCount(12);
        scan.setTruncated(false);
        scan.setSummary(summary);
        scan.setThumbnail(BIG_THUMBNAIL);
        scan.setResultJson("{\"tiers\":[]}");
        menuScanRepository.save(scan);
    }

    private List<String> selectsAgainst(String table) {
        return statements.list.stream()
                .map(event -> event.getFormattedMessage().toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("select") && sql.contains(table))
                .toList();
    }

    private void assertNoneSelect(String table, String column, String why) {
        List<String> selects = selectsAgainst(table);
        assertFalse(selects.isEmpty(), "expected at least one read of " + table);
        selects.forEach(sql -> assertFalse(sql.contains(column), why + "\n" + sql));
    }

    /**
     * The history list renders a thumbnail, so that CLOB has to come along. The
     * stored analysis does not — at the fifty-row cap that was ~150 KB read and
     * discarded on every History tab open.
     */
    @Test
    void theHistoryListReadsThumbnailsButNotStoredResults() {
        saveMeal("Nasi lemak", 30.0);
        saveMeal("Char kway teow", 25.0);
        statements.list.clear();

        List<HistoryEntry> history = analysisService.history(user.getId(), null, null).entries();

        assertEquals(2, history.size());
        assertEquals(BIG_THUMBNAIL, history.get(0).thumbnail(), "the list still needs its thumbnails");
        assertNoneSelect("meal_analysis", "result_json",
                "the history list read the stored analysis it never looks at:");
    }

    @Test
    void theMenuHistoryListReadsThumbnailsButNotStoredResults() {
        saveMenuScan("Mamak menu");
        statements.list.clear();

        List<MenuHistoryEntry> history = menuRankingService.history(user.getId());

        assertEquals(1, history.size());
        assertEquals(BIG_THUMBNAIL, history.get(0).thumbnail());
        assertNoneSelect("menu_scan", "result_json",
                "the menu history list read the stored ranking it never looks at:");
    }

    /**
     * The hottest read in the app — every dashboard load, and every analysis,
     * since goal-aware feedback needs the day's remaining budget. It sums three
     * numbers and wants neither CLOB.
     */
    @Test
    void theDashboardReadsNeitherClob() {
        saveMeal("Nasi lemak", 30.0);
        saveMeal("Teh tarik", 5.0);
        statements.list.clear();

        var response = dashboardService.today(user);

        assertEquals(2, response.mealCount());
        assertEquals(35.0, response.totalProtein(), 0.01, "the totals must still be right");
        assertNoneSelect("meal_analysis", "thumbnail", "the dashboard read a thumbnail to sum calories:");
        assertNoneSelect("meal_analysis", "result_json", "the dashboard read the stored analysis:");
    }

    /** Same read, same requirement, reached through the analysis path instead. */
    @Test
    void theRemainingBudgetLookupReadsNeitherClob() {
        saveMeal("Nasi lemak", 30.0);
        statements.list.clear();

        dashboardService.todaySoFar(user);

        assertNoneSelect("meal_analysis", "thumbnail", "the remaining-budget lookup read a thumbnail:");
        assertNoneSelect("meal_analysis", "result_json", "the remaining-budget lookup read the stored analysis:");
    }

    /**
     * The pre-V2 fallback still has to work — and still has to be the only thing
     * that reads {@code result_json} on this path. A row written before V2 has a
     * null {@code protein} column and its total lives only in the CLOB.
     */
    @Test
    void aPreV2RowStillGetsItsProteinCountedViaASecondQuery() {
        saveMeal("Modern meal", 30.0);
        saveMeal("Pre-V2 meal", null);
        statements.list.clear();

        var response = dashboardService.today(user);

        assertEquals(60.0, response.totalProtein(), 0.01,
                "30g from the column plus 30g parsed out of the legacy row's result_json");
        assertTrue(selectsAgainst("meal_analysis").stream().anyMatch(sql -> sql.contains("result_json")),
                "the legacy row's protein can only come from result_json, so one query must read it");
        assertNoneSelect("meal_analysis", "thumbnail",
                "even the legacy fallback has no use for a thumbnail:");
    }
}
