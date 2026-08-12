package com.duabiskuttelur.service;

import com.duabiskuttelur.config.AppMetrics;
import com.duabiskuttelur.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.duabiskuttelur.persistence.NutritionCacheEntity;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Makes nutrition resolution deterministic per dish.
 *
 * <p>Resolving a dish is a lottery: the USDA search can return a different top
 * match, time out, or be rate-limited, and the model's fallback per-100g
 * estimate is re-generated on every call. Two scans of the same dish therefore
 * produced different calories — and, since {@code fried}/{@code foodGroup} feed
 * ScoringService, sometimes a different grade and menu tier.
 *
 * <p>This memoizes the first successful resolution against a canonicalized dish
 * name and replays it forever after, so the same dish always yields the same
 * numbers. Values are held per 100g, letting each scan scale them to its own
 * portion. Backed by the {@code nutrition_cache} table so determinism survives
 * a restart or redeploy, with an in-memory map in front (a 60-dish menu scan
 * would otherwise be 60 DB reads).
 *
 * <p>Trade-off: a dish first resolved while USDA was down is pinned to the
 * model's estimate rather than being upgraded on a later scan — that upgrade is
 * exactly the variance being removed here. Delete the row (or set
 * {@code app.nutrition-cache-enabled=false}) to re-resolve.
 */
@Service
public class NutritionCacheService {

    private static final Logger log = LoggerFactory.getLogger(NutritionCacheService.class);

    /** Matches the nutrition_cache column widths; dish names never come close in practice. */
    private static final int MAX_TEXT_LENGTH = 255;

    /** Everything a scan needs about a dish except its portion scaling — all per 100g. */
    public record Resolved(
            double caloriesPer100g,
            double proteinPer100g,
            double carbsPer100g,
            double fatPer100g,
            double fiberPer100g,
            double sugarPer100g,
            double sodiumPer100g,
            double grams,
            double gramsLow,
            double gramsHigh,
            String portion,
            String source,
            String foodGroup,
            String cookingMethod,
            double confidence
    ) {
        /** Same reason grams is pinned: an un-pinned bracket would re-roll per scan. */
        public boolean fried() {
            return com.duabiskuttelur.model.FoodTaxonomy.isFried(cookingMethod);
        }
    }

    private final NutritionCacheRepository repository;
    private final AppProperties props;
    private final ConcurrentHashMap<String, Resolved> memo = new ConcurrentHashMap<>();
    private final Counter memoHits;
    private final Counter storeHits;
    private final Counter misses;

    public NutritionCacheService(NutritionCacheRepository repository, AppProperties props,
                                 MeterRegistry meters) {
        this.repository = repository;
        this.props = props;
        // Three counters rather than a hit/miss pair: a memo hit is free, a
        // stored hit is a database round trip, and a miss is an outbound USDA
        // call. Collapsing the first two would hide the case worth knowing
        // about, which is a warm process that still reads the table every time.
        this.memoHits = cacheCounter(meters, "memo_hit");
        this.storeHits = cacheCounter(meters, "store_hit");
        this.misses = cacheCounter(meters, "miss");
    }

    private static Counter cacheCounter(MeterRegistry meters, String result) {
        return Counter.builder(AppMetrics.NUTRITION_CACHE)
                .description("Nutrition cache lookups by where the answer came from")
                .tag(AppMetrics.TAG_RESULT, result)
                .register(meters);
    }

    /**
     * Returns the nutrition profile already resolved for this dish name, or runs
     * {@code resolver} once and pins its result for every later scan.
     */
    public Resolved resolve(String dishName, Supplier<Resolved> resolver) {
        String key = canonicalize(dishName);
        // A name that canonicalizes to nothing (blank, emoji-only) has no stable
        // identity to key on, so there's nothing to memoize against.
        if (!props.isNutritionCacheEnabled() || key.isEmpty()) {
            return resolver.get();
        }

        Resolved cached = memo.get(key);
        if (cached != null) {
            memoHits.increment();
            return cached;
        }
        Resolved stored = readStored(key);
        if (stored != null) {
            storeHits.increment();
            Resolved raced = memo.putIfAbsent(key, stored);
            return raced != null ? raced : stored;
        }
        misses.increment();

        // Resolved outside the map so a slow USDA call never holds a map bin lock.
        // Two concurrent scans of the same new dish can both resolve; the first to
        // land wins and both callers return its numbers.
        Resolved fresh = resolver.get();
        Resolved winner = memo.putIfAbsent(key, fresh);
        if (winner != null) {
            return winner;
        }
        store(key, dishName, fresh);
        return fresh;
    }

    /**
     * Cache key: case-, accent- and punctuation-insensitive, so "Char Kway Teow",
     * "char kway teow" and "char-kway-teow" are one dish. Letters and digits are
     * kept via {@link Character#isLetterOrDigit} rather than an ASCII range, so
     * Chinese and Malay dish names survive canonicalization instead of collapsing
     * to an empty key.
     */
    public static String canonicalize(String dishName) {
        if (dishName == null) {
            return "";
        }
        String normalized = Normalizer.normalize(dishName, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue; // accent stripped by NFKD decomposition
            }
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(c);
            } else {
                pendingSpace = true;
            }
        }
        return truncate(out.toString());
    }

    private Resolved readStored(String key) {
        try {
            return repository.findByCanonicalName(key).map(NutritionCacheService::toResolved).orElse(null);
        } catch (Exception e) {
            // A cache read failure only costs a re-resolution; never fail the scan.
            log.warn("Nutrition cache read failed for '{}': {}", key, e.getMessage());
            return null;
        }
    }

    private void store(String key, String dishName, Resolved resolved) {
        try {
            NutritionCacheEntity entity = new NutritionCacheEntity();
            entity.setCanonicalName(key);
            entity.setDisplayName(truncate(dishName));
            entity.setResolvedAt(Instant.now());
            entity.setSource(resolved.source());
            entity.setFoodGroup(resolved.foodGroup());
            entity.setFried(resolved.fried());
            entity.setCookingMethod(resolved.cookingMethod());
            entity.setConfidence(resolved.confidence());
            entity.setGrams(resolved.grams());
            entity.setGramsLow(resolved.gramsLow());
            entity.setGramsHigh(resolved.gramsHigh());
            entity.setPortion(truncate(resolved.portion()));
            entity.setCaloriesPer100g(resolved.caloriesPer100g());
            entity.setProteinPer100g(resolved.proteinPer100g());
            entity.setCarbsPer100g(resolved.carbsPer100g());
            entity.setFatPer100g(resolved.fatPer100g());
            entity.setFiberPer100g(resolved.fiberPer100g());
            entity.setSugarPer100g(resolved.sugarPer100g());
            entity.setSodiumPer100g(resolved.sodiumPer100g());
            repository.save(entity);
            log.info("Pinned nutrition for '{}' (source={})", key, resolved.source());
        } catch (Exception e) {
            // Best-effort, same as history persistence: the in-memory memo still
            // keeps this JVM consistent, it just won't survive a restart.
            log.warn("Nutrition cache write failed for '{}': {}", key, e.getMessage());
        }
    }

    private static Resolved toResolved(NutritionCacheEntity e) {
        return new Resolved(
                e.getCaloriesPer100g(), e.getProteinPer100g(), e.getCarbsPer100g(), e.getFatPer100g(),
                e.getFiberPer100g(), e.getSugarPer100g(), e.getSodiumPer100g(),
                e.getGrams(), e.getGramsLow(), e.getGramsHigh(), e.getPortion(), e.getSource(), e.getFoodGroup(),
                // Rows pinned before cooking methods existed carry only the old
                // boolean; "deep-fried" is the closest honest reading of it.
                e.getCookingMethod() != null ? e.getCookingMethod() : (e.isFried() ? "deep-fried" : null),
                e.getConfidence());
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_TEXT_LENGTH ? s : s.substring(0, MAX_TEXT_LENGTH);
    }
}
