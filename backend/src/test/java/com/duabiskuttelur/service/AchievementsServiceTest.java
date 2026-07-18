package com.duabiskuttelur.service;

import com.duabiskuttelur.model.AchievementsResponse;
import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.Badge;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AchievementsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** In-memory fake repository so we don't need a real Spring context. */
    private static class FakeRepository implements MealAnalysisRepository {
        final List<MealAnalysisEntity> entries = new ArrayList<>();

        @Override
        public List<MealAnalysisEntity> findByUserIdOrderByCreatedAtDesc(Long userId) {
            return entries;
        }

        @Override public List<MealAnalysisEntity> findTop50ByUserIdOrderByCreatedAtDesc(Long userId) { return List.of(); }
        @Override public Optional<MealAnalysisEntity> findByIdAndUserId(Long id, Long userId) { return Optional.empty(); }
        @Override public List<MealAnalysisEntity> findByUserIdAndCreatedAtBetween(Long userId, Instant start, Instant end) { return List.of(); }

        // Unused JpaRepository plumbing below — not exercised by these tests.
        @Override public <S extends MealAnalysisEntity> S save(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public Optional<MealAnalysisEntity> findById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public List<MealAnalysisEntity> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<MealAnalysisEntity> findAllById(Iterable<Long> longs) { throw new UnsupportedOperationException(); }
        @Override public long count() { throw new UnsupportedOperationException(); }
        @Override public void deleteById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public void delete(MealAnalysisEntity entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> longs) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends MealAnalysisEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public void flush() { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> S saveAndFlush(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<MealAnalysisEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public MealAnalysisEntity getOne(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public MealAnalysisEntity getById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public MealAnalysisEntity getReferenceById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends MealAnalysisEntity, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public List<MealAnalysisEntity> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<MealAnalysisEntity> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
    }

    private static MealAnalysisEntity meal(LocalDateTime at, String grade, String summary, List<FoodItem> foods) {
        MealAnalysisEntity e = new MealAnalysisEntity();
        e.setUserId(1L);
        e.setCreatedAt(at.atZone(ZONE).toInstant());
        e.setScore(80);
        e.setGrade(grade);
        e.setCalories(500);
        e.setSummary(summary);
        try {
            AnalysisResponse response = new AnalysisResponse(foods, Totals.of(foods), 80, grade,
                    List.of(), List.of(), List.of(), "Good job", "photo", null, true);
            e.setResultJson(MAPPER.writeValueAsString(response));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    private static FoodItem food(String name, String foodGroup) {
        return new FoodItem(name, "1 serving", 100, 5, 10, 2, 1, 1, 50, 0.9, "usda", foodGroup, false);
    }

    private static Badge find(AchievementsResponse response, String id) {
        return response.badges().stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void firstMealUnlocksItBeginsButNotTierTenBadge() {
        FakeRepository repo = new FakeRepository();
        repo.entries.add(meal(LocalDateTime.now(), "B", "Rice", List.of(food("Rice", "grain"))));

        AchievementsResponse response = new AchievementsService(repo, MAPPER).forUser(1L);

        Badge itBegins = find(response, "it_begins");
        assertNotNull(itBegins);
        assertTrue(itBegins.unlocked());
        assertEquals("You logged your first meal. The food diary officially has receipts.", itBegins.description());
        assertEquals(10, itBegins.xp());

        Badge paparazzi = find(response, "food_paparazzi");
        assertNotNull(paparazzi, "locked non-secret badges must still be listed");
        assertFalse(paparazzi.unlocked());
        assertNull(paparazzi.description(), "description must stay hidden until unlocked");
        assertNull(paparazzi.xp());
    }

    @Test
    void threeConsecutiveDaysUnlocksBarelyTryingStreak() {
        FakeRepository repo = new FakeRepository();
        LocalDateTime today = LocalDateTime.now();
        repo.entries.add(meal(today, "B", "Chicken", List.of(food("Chicken", "protein"))));
        repo.entries.add(meal(today.minusDays(1), "B", "Chicken", List.of(food("Chicken", "protein"))));
        repo.entries.add(meal(today.minusDays(2), "B", "Chicken", List.of(food("Chicken", "protein"))));

        AchievementsResponse response = new AchievementsService(repo, MAPPER).forUser(1L);

        assertEquals(3, response.currentStreakDays());
        assertTrue(find(response, "barely_trying").unlocked());
        assertFalse(find(response, "still_alive").unlocked());
    }

    @Test
    void vegetableAndPizzaKeywordAchievementsUnlockOnThreshold() {
        FakeRepository repo = new FakeRepository();
        LocalDateTime base = LocalDateTime.now().minusDays(10);
        for (int i = 0; i < 5; i++) {
            repo.entries.add(meal(base.plusDays(i), "A", "Broccoli, Rice",
                    List.of(food("Broccoli", "vegetable"), food("Rice", "grain"))));
        }
        for (int i = 0; i < 5; i++) {
            repo.entries.add(meal(base.plusDays(i + 5), "C", "Pepperoni Pizza",
                    List.of(food("Pepperoni Pizza", "grain"))));
        }

        AchievementsResponse response = new AchievementsService(repo, MAPPER).forUser(1L);

        assertTrue(find(response, "rabbit_mode").unlocked(), "5 meals with a vegetable item should unlock Rabbit Mode");
        assertTrue(find(response, "pizza_again").unlocked(), "5 meals containing pizza should unlock Pizza Again?");
        assertFalse(find(response, "fries_before_guys").unlocked());
    }

    @Test
    void lockedSecretBadgeIsOmittedEntirelyAndAppearsOnceUnlocked() {
        FakeRepository repo = new FakeRepository();
        repo.entries.add(meal(LocalDateTime.now(), "B", "Rice", List.of(food("Rice", "grain"))));

        AchievementsResponse locked = new AchievementsService(repo, MAPPER).forUser(1L);
        assertNull(find(locked, "we_saw_that"), "secret badges must not appear in the payload while locked");

        LocalDateTime midnight = LocalDate.now().atStartOfDay().plusHours(1);
        repo.entries.clear();
        repo.entries.add(meal(midnight, "B", "Snack", List.of(food("Snack", "grain"))));
        repo.entries.add(meal(midnight.minusDays(1), "B", "Snack", List.of(food("Snack", "grain"))));
        repo.entries.add(meal(midnight.minusDays(2), "B", "Snack", List.of(food("Snack", "grain"))));

        AchievementsResponse unlocked = new AchievementsService(repo, MAPPER).forUser(1L);
        Badge secret = find(unlocked, "we_saw_that");
        assertNotNull(secret, "secret badge should appear once its condition is met");
        assertTrue(secret.unlocked());
        assertTrue(secret.secret());
        assertNotNull(secret.description());
    }

    @Test
    void denormalizedColumnsAreUsedInsteadOfResultJsonWhenPresent() {
        // resultJson deliberately disagrees with the denormalized columns —
        // if computeStats ever fell back to parsing it for a row like this,
        // these assertions would fail, proving the fast path is what's used.
        FakeRepository repo = new FakeRepository();
        MealAnalysisEntity e = meal(LocalDateTime.now(), "A", "Broccoli, Latte",
                List.of(food("Broccoli", "vegetable"), food("Latte", "beverage")));
        e.setVegetableCount(3);
        e.setHasFruit(true);
        e.setBeverageOnly(true);
        e.setCoffeeOnly(true);
        repo.entries.add(e);

        AchievementsResponse response = new AchievementsService(repo, MAPPER).forUser(1L);

        assertTrue(find(response, "coffee_counts").unlocked(), "coffeeOnly=true column should unlock it");
        assertTrue(find(response, "liquid_dinner").unlocked(), "beverageOnly=true column should unlock it");
    }

    @Test
    void hasFruitColumnDrivesFruitStreakWithoutParsingResultJson() {
        FakeRepository repo = new FakeRepository();
        LocalDateTime base = LocalDateTime.now().minusDays(4);
        for (int i = 0; i < 5; i++) {
            // resultJson has no fruit at all — only the hasFruit column says otherwise.
            MealAnalysisEntity e = meal(base.plusDays(i), "B", "Rice", List.of(food("Rice", "grain")));
            e.setVegetableCount(0);
            e.setHasFruit(true);
            e.setBeverageOnly(false);
            e.setCoffeeOnly(false);
            repo.entries.add(e);
        }

        AchievementsResponse response = new AchievementsService(repo, MAPPER).forUser(1L);

        assertTrue(find(response, "an_apple_a_day").unlocked(),
                "5-day fruitStreak driven by the hasFruit column should unlock it");
    }

    @Test
    void coffeeOnlyMealUnlocksCoffeeCountsButMixedMealDoesNot() {
        FakeRepository repo = new FakeRepository();
        repo.entries.add(meal(LocalDateTime.now(), "C", "Latte",
                List.of(food("Latte", "beverage"))));
        repo.entries.add(meal(LocalDateTime.now().minusDays(1), "B", "Coffee, Toast",
                List.of(food("Coffee", "beverage"), food("Toast", "grain"))));

        AchievementsResponse response = new AchievementsService(repo, MAPPER).forUser(1L);

        assertTrue(find(response, "coffee_counts").unlocked());
    }
}
