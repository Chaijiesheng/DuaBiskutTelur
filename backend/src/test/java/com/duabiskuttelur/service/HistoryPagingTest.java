package com.duabiskuttelur.service;

import com.duabiskuttelur.model.HistoryEntry;
import com.duabiskuttelur.model.HistoryPage;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paging the history past the first page.
 *
 * <p>It could not be done at all: the endpoint took no parameters and the
 * service asked for {@code PageRequest.of(0, 50)}, so meal fifty-one was in the
 * database and on no screen. The constant was already called
 * {@code HISTORY_PAGE_SIZE}, which is what a page size looks like when there is
 * only ever one page.
 *
 * <p>The interesting half of these is not "does page two arrive" but what
 * happens when the list changes underneath the reader -- which on a food log it
 * constantly does, since logging a meal is the point and deleting one is two
 * taps away.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:history-paging-test;DB_CLOSE_DELAY=-1"
})
class HistoryPagingTest {

    private static final int PAGE = AnalysisService.HISTORY_PAGE_SIZE;
    private static final String STORED_RESULT = "{\"totals\":{\"calories\":600},\"foods\":[]}";

    @Autowired private AnalysisService analysisService;
    @Autowired private MealAnalysisRepository meals;
    @Autowired private UserRepository users;

    private UserEntity user;
    private Instant base;

    @BeforeEach
    void seed() {
        meals.deleteAll();
        users.deleteAll();
        UserEntity fresh = new UserEntity();
        fresh.setGoogleSub("paging-sub");
        fresh.setCreatedAt(Instant.now());
        user = users.save(fresh);
        base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private MealAnalysisEntity save(Long userId, String summary, Instant createdAt) {
        MealAnalysisEntity meal = new MealAnalysisEntity();
        meal.setUserId(userId);
        meal.setCreatedAt(createdAt);
        meal.setScore(80);
        meal.setGrade("A");
        meal.setCalories(600);
        meal.setSummary(summary);
        meal.setResultJson(STORED_RESULT);
        return meals.save(meal);
    }

    /** Newest first, so a higher index is an older meal. */
    private void seedMeals(int count) {
        for (int i = 0; i < count; i++) {
            save(user.getId(), "meal " + i, base.minus(i, ChronoUnit.MINUTES));
        }
    }

    private HistoryPage firstPage() {
        return analysisService.history(user.getId(), null, null);
    }

    private HistoryPage after(HistoryEntry last) {
        return analysisService.history(user.getId(), last.createdAt(), last.id());
    }

    /** Every page, followed to the end, in order. */
    private List<HistoryEntry> walkAll() {
        List<HistoryEntry> all = new ArrayList<>();
        HistoryPage page = firstPage();
        all.addAll(page.entries());
        int guard = 0;
        while (page.hasMore()) {
            if (++guard > 50) {
                throw new AssertionError("paging never terminated -- " + all.size() + " rows so far");
            }
            page = after(all.get(all.size() - 1));
            assertFalse(page.entries().isEmpty(), "hasMore was true but the next page came back empty");
            all.addAll(page.entries());
        }
        return all;
    }

    @Test
    void fillsTheFirstPageAndSaysThereIsAnother() {
        seedMeals(120);

        HistoryPage page = firstPage();

        assertEquals(PAGE, page.entries().size());
        assertTrue(page.hasMore());
        assertEquals("meal 0", page.entries().get(0).summary(), "newest meal is not at the top");
    }

    /** The bug, stated directly. */
    @Test
    void reachesEveryMealExactlyOnce() {
        seedMeals(120);

        List<HistoryEntry> all = walkAll();

        assertEquals(120, all.size());
        assertEquals(120, all.stream().map(HistoryEntry::id).distinct().count(), "a meal appeared on two pages");
        for (int i = 0; i < 120; i++) {
            assertEquals("meal " + i, all.get(i).summary(), "wrong meal at position " + i);
        }
    }

    @Test
    void stopsSayingThereIsMoreOnTheLastPage() {
        seedMeals(PAGE);

        HistoryPage page = firstPage();

        assertEquals(PAGE, page.entries().size());
        assertFalse(page.hasMore(), "a full first page with nothing behind it still offered another");
    }

    @Test
    void reportsNoFurtherPagesForAHistoryThatFitsOnOne() {
        seedMeals(3);

        HistoryPage page = firstPage();

        assertEquals(3, page.entries().size());
        assertFalse(page.hasMore());
    }

    @Test
    void handlesAnEmptyHistory() {
        HistoryPage page = firstPage();

        assertTrue(page.entries().isEmpty());
        assertFalse(page.hasMore());
    }

    /**
     * The reason this is a cursor and not an offset.
     *
     * <p>A meal logged between page one and page two pushes every row down by
     * one. Under offset paging page two would then start on the row page one
     * ended with -- so the reader sees that meal twice and never sees the one
     * pushed past the boundary. A cursor names a row, and new rows land above it.
     */
    @Test
    void aMealLoggedWhileReadingDoesNotShiftThePagesUnderneath() {
        seedMeals(120);
        HistoryPage first = firstPage();

        save(user.getId(), "logged just now", base.plusSeconds(30));

        HistoryPage second = after(first.entries().get(PAGE - 1));

        assertEquals("meal " + PAGE, second.entries().get(0).summary(),
                "page two did not continue where page one stopped");
        Set<Long> firstIds = new HashSet<>(first.entries().stream().map(HistoryEntry::id).toList());
        assertTrue(second.entries().stream().noneMatch(e -> firstIds.contains(e.id())),
                "page two repeated a row from page one");
    }

    /** The mirror case: a deletion must not make the next page skip a meal. */
    @Test
    void aMealDeletedWhileReadingDoesNotSkipTheNextOne() {
        seedMeals(120);
        HistoryPage first = firstPage();

        meals.deleteById(first.entries().get(0).id());

        HistoryPage second = after(first.entries().get(PAGE - 1));

        assertEquals("meal " + PAGE, second.entries().get(0).summary());
    }

    /**
     * The reason the cursor carries an id as well as a timestamp.
     *
     * <p>Two meals sharing an instant straddle the page boundary. With
     * {@code createdAt} alone as the cursor, {@code < before} drops the twin
     * still to come and {@code <= before} repeats the one already shown --
     * either way the list quietly disagrees with the database.
     */
    @Test
    void doesNotLoseAMealThatSharesAnInstantWithTheOneAbove() {
        for (int i = 0; i < PAGE - 1; i++) {
            save(user.getId(), "meal " + i, base.minus(i, ChronoUnit.MINUTES));
        }
        Instant tie = base.minus(PAGE, ChronoUnit.MINUTES);
        save(user.getId(), "twin A", tie);
        save(user.getId(), "twin B", tie);
        save(user.getId(), "meal after the twins", tie.minusSeconds(30));

        List<HistoryEntry> all = walkAll();

        assertEquals(PAGE + 2, all.size(), "a meal sharing an instant with another went missing");
        List<String> summaries = all.stream().map(HistoryEntry::summary).toList();
        assertTrue(summaries.contains("twin A"), summaries.toString());
        assertTrue(summaries.contains("twin B"), summaries.toString());
        assertTrue(summaries.contains("meal after the twins"), summaries.toString());
        assertEquals(all.size(), all.stream().map(HistoryEntry::id).distinct().count(), "a meal was shown twice");
    }

    /** One account's cursor must not walk into another account's meals. */
    @Test
    void pagesOnlyTheSignedInUsersMeals() {
        seedMeals(60);
        UserEntity other = new UserEntity();
        other.setGoogleSub("someone-else");
        other.setCreatedAt(Instant.now());
        other = users.save(other);
        for (int i = 0; i < 60; i++) {
            save(other.getId(), "not yours " + i, base.minus(i, ChronoUnit.MINUTES));
        }

        List<HistoryEntry> all = walkAll();

        assertEquals(60, all.size());
        assertTrue(all.stream().noneMatch(e -> e.summary().startsWith("not yours")),
                "paging crossed into another account's history");
    }
}
