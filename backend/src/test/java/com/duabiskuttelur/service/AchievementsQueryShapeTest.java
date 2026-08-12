package com.duabiskuttelur.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.duabiskuttelur.model.AchievementsResponse;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The achievements catalog is recomputed from a user's whole history on every
 * request, and {@code meal_analysis} carries two CLOBs per row —
 * {@code thumbnail} (a base64 data URL, ~6 KB) and {@code result_json} (~3 KB).
 * Loading entities meant a user with a thousand meals moved roughly 9 MB per
 * Profile tab open to read a handful of ints and booleans.
 *
 * <p>Whether that is fixed is a property of the SQL, not of the result — the
 * badges come out identical either way, so a behavioural test would pass with
 * the projection reverted. These assertions read the statements Hibernate
 * actually emits.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:achievements-shape-test;DB_CLOSE_DELAY=-1"
})
class AchievementsQueryShapeTest {

    private static final String BIG_THUMBNAIL = "data:image/jpeg;base64," + "A".repeat(6_000);

    @Autowired private AchievementsService achievementsService;
    @Autowired private MealAnalysisRepository mealRepository;
    @Autowired private UserRepository userRepository;

    private ch.qos.logback.classic.Logger sqlLogger;
    private ListAppender<ILoggingEvent> statements;
    private Level originalLevel;
    private Long userId;

    @BeforeEach
    void captureSqlAndSeed() {
        mealRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity user = new UserEntity();
        user.setGoogleSub("shape-test-sub");
        user.setCreatedAt(Instant.now());
        userId = userRepository.save(user).getId();

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

    /** A row as written since V2: the denormalized columns are populated. */
    private void saveModernMeal(String grade, String summary, int vegetableCount, boolean hasFruit) {
        MealAnalysisEntity e = baseMeal(grade, summary);
        e.setVegetableCount(vegetableCount);
        e.setHasFruit(hasFruit);
        e.setBeverageOnly(false);
        e.setCoffeeOnly(false);
        e.setProtein(20.0);
        mealRepository.save(e);
    }

    /** A row from before V2: the columns are null and the facts live only in result_json. */
    private void saveLegacyMeal(String grade, String summary, String foodName, String foodGroup) {
        MealAnalysisEntity e = baseMeal(grade, summary);
        e.setResultJson("{\"foods\":[{\"name\":\"" + foodName + "\",\"foodGroup\":\"" + foodGroup + "\"}]}");
        mealRepository.save(e);
    }

    private MealAnalysisEntity baseMeal(String grade, String summary) {
        MealAnalysisEntity e = new MealAnalysisEntity();
        e.setUserId(userId);
        e.setCreatedAt(Instant.now().minus(mealRepository.count() + 1, ChronoUnit.HOURS));
        e.setScore(80);
        e.setGrade(grade);
        e.setCalories(600);
        e.setSummary(summary);
        e.setThumbnail(BIG_THUMBNAIL);
        return e;
    }

    private List<String> selectsAgainstMealAnalysis() {
        return statements.list.stream()
                .map(event -> event.getFormattedMessage().toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("select") && sql.contains("meal_analysis"))
                .toList();
    }

    @Test
    void readingAchievementsNeverSelectsTheThumbnailColumn() {
        saveModernMeal("A", "Broccoli, Rice", 1, false);
        saveModernMeal("B", "Chicken rice", 0, false);
        statements.list.clear();

        achievementsService.forUser(userId, "en");

        List<String> selects = selectsAgainstMealAnalysis();
        assertTrue(!selects.isEmpty(), "expected at least one read of meal_analysis");
        selects.forEach(sql -> assertTrue(!sql.contains("thumbnail"),
                "a thumbnail CLOB was read to compute badges:\n" + sql));
    }

    /**
     * The legacy query is issued unconditionally — it simply matches nothing on
     * an account with no pre-V2 rows — so the property worth pinning isn't that
     * {@code result_json} goes unmentioned, but that it is never selected
     * without the filter that restricts it to rows which have no alternative.
     * Widening the main projection to include it would defeat the whole split,
     * and this is what would catch that.
     */
    @Test
    void resultJsonIsOnlyEverSelectedUnderTheLegacyFilter() {
        saveModernMeal("A", "Broccoli, Rice", 1, false);
        saveModernMeal("A+", "Salad", 2, true);
        statements.list.clear();

        achievementsService.forUser(userId, "en");

        selectsAgainstMealAnalysis().stream()
                .filter(sql -> sql.contains("result_json"))
                .forEach(sql -> assertTrue(sql.contains("vegetable_count is null"),
                        "result_json was selected without restricting to pre-V2 rows:\n" + sql));
    }

    /**
     * The split has to be exhaustive: a row landing in neither query would
     * silently vanish from every statistic, and the totals are the only place
     * that would show it.
     */
    @Test
    void modernAndLegacyRowsAreBothCounted() {
        saveModernMeal("A", "Broccoli, Rice", 1, false);
        saveModernMeal("B", "Chicken rice", 0, false);
        saveLegacyMeal("A", "Bayam, Rice", "Bayam", "vegetable");

        AchievementsResponse response = achievementsService.forUser(userId, "en");

        assertEquals(3, response.totalMealsLogged(), "a row fell into neither the modern nor the legacy query");
    }

    /** Legacy rows only have their facts inside result_json, so that CLOB must still be read for them. */
    @Test
    void legacyRowsStillHaveTheirFactsRecoveredFromResultJson() {
        saveLegacyMeal("A", "Bayam, Rice", "Bayam", "vegetable");
        saveLegacyMeal("A", "Kangkung", "Kangkung", "vegetable");
        saveLegacyMeal("A", "Sawi", "Sawi", "vegetable");
        saveLegacyMeal("A", "Kailan", "Kailan", "vegetable");
        saveLegacyMeal("A", "Salad", "Salad", "vegetable");

        AchievementsResponse response = achievementsService.forUser(userId, "en");

        // rabbit_mode needs 5 meals containing a vegetable — a fact that exists
        // nowhere but result_json for these rows.
        var rabbitMode = response.badges().stream().filter(b -> b.id().equals("rabbit_mode")).findFirst().orElse(null);
        assertNotNull(rabbitMode);
        assertTrue(rabbitMode.unlocked(), "vegetable facts were not recovered from result_json");
    }
}
