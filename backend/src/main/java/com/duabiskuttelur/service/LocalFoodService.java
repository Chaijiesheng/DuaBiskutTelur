package com.duabiskuttelur.service;

import com.duabiskuttelur.config.AppMetrics;
import com.duabiskuttelur.persistence.LocalFoodEntity;
import com.duabiskuttelur.persistence.LocalFoodRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Curated Malaysian dish compositions, consulted before USDA.
 *
 * <p>USDA FoodData Central does not contain nasi lemak, char kway teow, roti
 * canai, cendol or teh tarik. Each of those currently resolves through
 * {@code usdaSearchTerm} to the closest generic it can find — "coconut rice" for
 * the nasi lemak base — or falls through to the model's own estimate. For a
 * Malaysia-first product that is a permanent accuracy ceiling that no amount of
 * prompt work moves, because the data is not in the source being queried.
 *
 * <p>A hit here is reported as {@code source="local"} and shown as the highest
 * trust badge, above a USDA match: it is the dish itself rather than the nearest
 * generic equivalent, and it carries a citation.
 *
 * <p>The table ships empty. Rows come from {@code R__local_food_seed.sql}, a
 * repeatable migration, so curating is a data change rather than a schema one.
 * Until it holds rows this service is a fast no-op — one indexed miss — and the
 * behaviour is exactly what it was before. That is the honest state of it: the
 * mechanism is done, the accuracy win arrives with the data.
 */
@Service
public class LocalFoodService {

    private static final Logger log = LoggerFactory.getLogger(LocalFoodService.class);

    private final LocalFoodRepository repository;
    private final Counter hits;
    private final Counter misses;

    public LocalFoodService(LocalFoodRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.hits = counter(meters, AppMetrics.OUTCOME_HIT);
        this.misses = counter(meters, AppMetrics.OUTCOME_MISS);
    }

    private static Counter counter(MeterRegistry meters, String outcome) {
        return Counter.builder(AppMetrics.LOCAL_FOOD_LOOKUP)
                .description("Curated local-database lookups; the hit rate is the coverage of the seeded data")
                .tag(AppMetrics.TAG_OUTCOME, outcome)
                .register(meters);
    }

    /**
     * Looks the dish up by its canonical name, then by any alias.
     *
     * <p>Canonicalized through {@link NutritionCacheService#canonicalize} so this
     * table and the nutrition cache agree on what counts as the same dish —
     * otherwise "Char Kway Teow" could hit here while "char-kway-teow" pinned a
     * separate cache row, and the two would drift.
     */
    public Optional<NutritionCacheService.Resolved> lookup(String dishName) {
        String key = NutritionCacheService.canonicalize(dishName);
        if (key.isEmpty()) {
            misses.increment();
            return Optional.empty();
        }
        Optional<LocalFoodEntity> found = repository.findByCanonicalName(key);
        if (found.isEmpty()) {
            found = repository.findByAlias(key);
        }
        if (found.isEmpty()) {
            misses.increment();
            return Optional.empty();
        }
        hits.increment();
        log.debug("Resolved '{}' from the local food database ({})", key, found.get().getSource());
        return found.map(LocalFoodService::toResolved);
    }

    private static NutritionCacheService.Resolved toResolved(LocalFoodEntity food) {
        return new NutritionCacheService.Resolved(
                food.getCaloriesPer100g(), food.getProteinPer100g(), food.getCarbsPer100g(),
                food.getFatPer100g(), food.getFiberPer100g(), food.getSugarPer100g(), food.getSodiumPer100g(),
                food.getTypicalGrams(),
                // No portion bracket: this is a published typical serving, not a
                // guess off a photo. The photo path replaces the grams with what
                // it can see anyway; only menu scans replay this one.
                food.getTypicalGrams(), food.getTypicalGrams(),
                "1 serving / ~%.0fg".formatted(food.getTypicalGrams()),
                SOURCE,
                food.getFoodGroup(), food.getCookingMethod(),
                // A transcribed composition figure is not a guess, so the
                // identification confidence the UI shows should say so.
                1.0);
    }

    /** The value that reaches FoodItem.source and the UI badge. */
    public static final String SOURCE = "local";
}
