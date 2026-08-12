package com.duabiskuttelur.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.persistence.NutritionCacheEntity;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import com.duabiskuttelur.service.NutritionCacheService.Resolved;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache is what makes nutrition resolution deterministic, so these tests
 * pin down both halves of that: which names count as the same dish
 * (canonicalize) and the guarantee that a dish resolves exactly once.
 *
 * <p>The repository is mocked — Mockito returns Optional.empty() for
 * findByCanonicalName by default, which is exactly a cold cache.
 */
class NutritionCacheServiceTest {

    private final NutritionCacheRepository repository = Mockito.mock(NutritionCacheRepository.class);

    private NutritionCacheService service() {
        return new NutritionCacheService(repository, new AppProperties(), new SimpleMeterRegistry());
    }

    private static Resolved resolved(double caloriesPer100g) {
        return new Resolved(caloriesPer100g, 6, 22, 7, 2, 3, 620,
                350, 300, 400, "1 plate / ~350g", "estimated", "grain", "deep-fried", 0.9);
    }

    @Test
    void treatsCaseAccentAndPunctuationVariantsAsTheSameDish() {
        assertEquals("char kway teow", NutritionCacheService.canonicalize("Char Kway Teow"));
        assertEquals("char kway teow", NutritionCacheService.canonicalize("  char-kway-teow!  "));
        assertEquals("char kway teow", NutritionCacheService.canonicalize("CHAR   KWAY\tTEOW"));
        assertEquals("cafe latte", NutritionCacheService.canonicalize("Café Latte"));
    }

    @Test
    void keepsNonLatinDishNamesInsteadOfCollapsingThemToAnEmptyKey() {
        // zh/ms are first-class in this app; an ASCII-only filter would map every
        // Chinese dish name to "" and defeat caching for all of them at once.
        assertEquals("炒粿条", NutritionCacheService.canonicalize("炒粿条"));
        assertEquals("nasi lemak", NutritionCacheService.canonicalize("Nasi Lemak"));
        assertNotEquals(NutritionCacheService.canonicalize("炒粿条"),
                NutritionCacheService.canonicalize("咖喱面"));
    }

    @Test
    void resolvesADishOnceAndReplaysItForEveryLaterScan() {
        AtomicInteger calls = new AtomicInteger();
        NutritionCacheService cache = service();

        Resolved first = cache.resolve("Char kway teow",
                () -> { calls.incrementAndGet(); return resolved(176); });
        Resolved second = cache.resolve("char-kway-teow!",
                () -> { calls.incrementAndGet(); return resolved(90); });

        assertEquals(1, calls.get(), "the second scan must not re-run the lookup");
        assertSame(first, second);
        assertEquals(176, second.caloriesPer100g());
    }

    @Test
    void writesTheResolvedValuesUnderTheCanonicalKey() {
        service().resolve("Char Kway Teow!", () -> resolved(176));

        ArgumentCaptor<NutritionCacheEntity> saved = ArgumentCaptor.forClass(NutritionCacheEntity.class);
        Mockito.verify(repository).save(saved.capture());
        assertEquals("char kway teow", saved.getValue().getCanonicalName());
        assertEquals("Char Kway Teow!", saved.getValue().getDisplayName());
        assertEquals(176, saved.getValue().getCaloriesPer100g());
        assertEquals("estimated", saved.getValue().getSource());
        assertTrue(saved.getValue().isFried());
    }

    @Test
    void replaysAStoredResolutionAfterARestart() {
        // Fresh service, warm table: determinism has to survive a redeploy, which
        // is the whole reason this is a table and not just a map.
        Mockito.when(repository.findByCanonicalName("char kway teow"))
                .thenReturn(Optional.of(storedEntity()));
        AtomicInteger calls = new AtomicInteger();

        Resolved fromDb = service().resolve("Char kway teow",
                () -> { calls.incrementAndGet(); return resolved(90); });

        assertEquals(0, calls.get());
        assertEquals(176, fromDb.caloriesPer100g());
        assertEquals("usda", fromDb.source());
    }

    @Test
    void resolvesAfreshWhenTheCacheIsDisabled() {
        AppProperties props = new AppProperties();
        props.setNutritionCacheEnabled(false);
        NutritionCacheService cache = new NutritionCacheService(repository, props, new SimpleMeterRegistry());
        AtomicInteger calls = new AtomicInteger();

        cache.resolve("Char kway teow", () -> { calls.incrementAndGet(); return resolved(176); });
        Resolved second = cache.resolve("Char kway teow", () -> { calls.incrementAndGet(); return resolved(90); });

        assertEquals(2, calls.get());
        assertEquals(90, second.caloriesPer100g());
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void skipsCachingNamesWithNoStableKey() {
        AtomicInteger calls = new AtomicInteger();
        NutritionCacheService cache = service();

        cache.resolve("   ", () -> { calls.incrementAndGet(); return resolved(176); });
        cache.resolve(null, () -> { calls.incrementAndGet(); return resolved(176); });
        cache.resolve("!!!", () -> { calls.incrementAndGet(); return resolved(176); });

        assertEquals(3, calls.get());
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }

    private static NutritionCacheEntity storedEntity() {
        NutritionCacheEntity entity = new NutritionCacheEntity();
        entity.setCanonicalName("char kway teow");
        entity.setDisplayName("Char kway teow");
        entity.setResolvedAt(Instant.now());
        entity.setSource("usda");
        entity.setFoodGroup("grain");
        entity.setFried(true);
        entity.setConfidence(0.85);
        entity.setGrams(350);
        entity.setPortion("1 plate / ~350g");
        entity.setCaloriesPer100g(176);
        entity.setProteinPer100g(6);
        entity.setCarbsPer100g(22);
        entity.setFatPer100g(7);
        entity.setFiberPer100g(2);
        entity.setSugarPer100g(3);
        entity.setSodiumPer100g(620);
        return entity;
    }
}
