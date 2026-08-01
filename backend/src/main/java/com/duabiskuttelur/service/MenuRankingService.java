package com.duabiskuttelur.service;

import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.MenuDish;
import com.duabiskuttelur.model.MenuHistoryEntry;
import com.duabiskuttelur.model.MenuRankingResponse;
import com.duabiskuttelur.model.MenuRankingResponse.TierGroup;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.persistence.MenuScanEntity;
import com.duabiskuttelur.persistence.MenuScanRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Reads a menu photo, scores every dish on it independently (never combined
 * with the others, unlike a photographed plate), and buckets each dish into
 * one of TierMapping's 5 meme tiers. Deliberately reuses AnalysisService's
 * USDA-lookup-with-fallback and ScoringService's existing grade bands — a menu
 * dish is scored exactly like a single-item barcode scan (see
 * BarcodeLookupService), just relabeled for presentation.
 */
@Service
public class MenuRankingService {

    private static final Logger log = LoggerFactory.getLogger(MenuRankingService.class);
    private static final int MAX_DISHES = 60;
    /** Fewer dishes than this and there's nothing meaningful to spread across 5 tiers. */
    private static final int MIN_DISHES_FOR_RELATIVE = 3;
    /** Concurrent USDA lookups per scan. Enough to hide the latency, low enough to stay a polite client. */
    private static final int NUTRITION_LOOKUP_THREADS = 8;

    public static class NoDishesDetectedException extends RuntimeException {
        public NoDishesDetectedException() {
            super("No dishes detected on the menu");
        }
    }

    public static class HistoryEntryNotFoundException extends RuntimeException {
        public HistoryEntryNotFoundException() {
            super("Menu scan not found");
        }
    }

    private final VisionAnalysisClient visionClient;
    private final ScoringService scoringService;
    private final AnalysisService analysisService;
    private final ThumbnailService thumbnailService;
    private final MenuScanRepository repository;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final ExecutorService nutritionPool = Executors.newFixedThreadPool(
            NUTRITION_LOOKUP_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "menu-nutrition");
                // Daemon so a stuck lookup can never hold up JVM shutdown.
                thread.setDaemon(true);
                return thread;
            });

    @PreDestroy
    void shutdownNutritionPool() {
        nutritionPool.shutdownNow();
    }

    public MenuRankingService(VisionAnalysisClient visionClient, ScoringService scoringService,
                               AnalysisService analysisService, ThumbnailService thumbnailService,
                               MenuScanRepository repository, AppProperties props, ObjectMapper mapper) {
        this.visionClient = visionClient;
        this.scoringService = scoringService;
        this.analysisService = analysisService;
        this.thumbnailService = thumbnailService;
        this.repository = repository;
        this.props = props;
        this.mapper = mapper;
    }

    public MenuRankingResponse rank(byte[] imageBytes, String mediaType, UserEntity user, String lang) {
        long startedAt = System.nanoTime();
        Result identified = props.hasGeminiKey()
                ? identifyAndResolveMenu(imageBytes, mediaType)
                : new Result(mockMenuFoods(), mockMenuAddOns(), false, 0, 0);
        // A menu of nothing but drinks and condiments has no dishes to rank, so
        // it's the same dead end for the user as reading nothing at all.
        if (identified.mains().isEmpty()) {
            throw new NoDishesDetectedException();
        }

        int calorieBudget = (int) Math.round(
                scoringService.effectiveBudget(user != null ? user.getDailyBudget() : null));

        List<Scored> mains = scoreAll(identified.mains(), calorieBudget);
        List<Scored> sides = scoreAll(identified.sides(), calorieBudget);

        boolean relative = useRelativeTiers(mains);
        List<String> spread = relative ? TierMapping.evenlySpreadTiers(mains.size()) : List.of();

        List<MenuDish> dishes = new ArrayList<>();
        for (int i = 0; i < mains.size(); i++) {
            Scored s = mains.get(i);
            String tier = relative ? spread.get(i) : TierMapping.tierFor(s.grade());
            dishes.add(new MenuDish(s.item().name(), s.item().estimatedPortion(),
                    s.score(), s.grade(), tier, i + 1, s.item()));
        }
        // Add-ons carry no tier: they're listed for reference, not ranked against
        // the mains. A spoon of sambal isn't an answer to "what should I order".
        List<MenuDish> addOns = new ArrayList<>();
        for (int i = 0; i < sides.size(); i++) {
            Scored s = sides.get(i);
            addOns.add(new MenuDish(s.item().name(), s.item().estimatedPortion(),
                    s.score(), s.grade(), null, i + 1, s.item()));
        }

        MenuRankingResponse response = new MenuRankingResponse(
                groupIntoTiers(dishes), addOns, dishes.size(), identified.truncated(), relative, user != null);

        if (user != null) {
            persist(response, imageBytes, user.getId());
        }
        // Menu scans are the slowest thing this app does, and the split between
        // "the model was thinking" and "we were waiting on USDA" is the only way
        // to know which one is worth optimizing next.
        log.info("Menu scan finished in {}ms (vision {}ms, nutrition {}ms, {} dishes + {} add-ons, relative={})",
                elapsedMs(startedAt), identified.visionMs(), identified.nutritionMs(),
                dishes.size(), addOns.size(), relative);
        return response;
    }

    private record Result(List<FoodItem> mains, List<FoodItem> sides, boolean truncated,
                          long visionMs, long nutritionMs) {
    }

    /** A dish with its own score, before it's been ranked and assigned a tier. */
    private record Scored(FoodItem item, int score, String grade) {
    }

    /** Scores each dish on its own and returns them healthiest first, name breaking ties so a re-scan orders identically. */
    private List<Scored> scoreAll(List<FoodItem> items, int calorieBudget) {
        List<Scored> scored = new ArrayList<>();
        for (FoodItem item : items) {
            ScoringService.ScoreResult score = scoringService.scoreMenuDish(item, calorieBudget);
            scored.add(new Scored(item, score.score(), score.grade()));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.item().name()));
        return scored;
    }

    /**
     * Absolute grade bands stop saying anything useful when a menu has no
     * genuinely healthy option — a fried-chicken shop where every dish is a C
     * or D leaves the top three tiers empty and dumps everything in 拉完了 — or
     * when every dish happens to land in the same band. Ranking the dishes
     * against each other is more informative there, so the tier list still
     * answers "which of these is the better order?".
     *
     * <p>Below {@link #MIN_DISHES_FOR_RELATIVE} dishes there's nothing to
     * spread out, so the honest absolute grades are kept instead.
     */
    private boolean useRelativeTiers(List<Scored> scored) {
        if (scored.size() < MIN_DISHES_FOR_RELATIVE) {
            return false;
        }
        boolean noHealthyOption = scored.stream()
                .allMatch(s -> s.score() < scoringService.healthyScoreFloor());
        boolean allInOneTier = scored.stream()
                .map(s -> TierMapping.tierFor(s.grade()))
                .distinct()
                .count() == 1;
        return noHealthyOption || allInOneTier;
    }

    private Result identifyAndResolveMenu(byte[] imageBytes, String mediaType) {
        long visionStart = System.nanoTime();
        List<IdentifiedFood> identified = visionClient.identifyMenuDishes(imageBytes, mediaType);
        long visionMs = elapsedMs(visionStart);

        boolean truncated = identified.size() > MAX_DISHES;
        if (truncated) {
            identified = identified.subList(0, MAX_DISHES);
        }

        // Split before resolving so the two lists stay aligned with what the
        // model said each item was, rather than trying to infer it back from
        // the resolved FoodItem (which no longer carries the menu section).
        List<IdentifiedFood> mainDishes = identified.stream().filter(cf -> !cf.isSideOrDrink()).toList();
        List<IdentifiedFood> sideDishes = identified.stream().filter(IdentifiedFood::isSideOrDrink).toList();

        long nutritionStart = System.nanoTime();
        List<FoodItem> mains = resolveInParallel(mainDishes);
        List<FoodItem> sides = resolveInParallel(sideDishes);
        return new Result(mains, sides, truncated, visionMs, elapsedMs(nutritionStart));
    }

    /**
     * One USDA round trip per dish, run concurrently instead of end to end. A
     * menu is dozens of independent lookups, so serially this was the second
     * biggest chunk of a scan's wall clock; the pool is bounded so a 60-dish
     * menu can't open 60 sockets to USDA at once. Input order is preserved —
     * callers rank afterwards, but keeping it deterministic keeps tie-breaks
     * and logs reproducible.
     */
    private List<FoodItem> resolveInParallel(List<IdentifiedFood> identified) {
        List<Future<FoodItem>> pending = new ArrayList<>(identified.size());
        for (IdentifiedFood cf : identified) {
            pending.add(nutritionPool.submit(() -> analysisService.resolveNutrition(cf)));
        }
        List<FoodItem> foods = new ArrayList<>(pending.size());
        for (Future<FoodItem> future : pending) {
            try {
                foods.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.forEach(f -> f.cancel(true));
                throw new IllegalStateException("Interrupted while resolving menu nutrition", e);
            } catch (ExecutionException e) {
                // resolveNutrition already falls back internally when USDA is
                // unreachable, so this is a genuine bug rather than a flaky
                // network. Drop the one dish instead of failing the whole scan.
                log.warn("Dropping a menu dish whose nutrition lookup failed: {}", e.getCause().toString());
            }
        }
        return foods;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private List<TierGroup> groupIntoTiers(List<MenuDish> dishes) {
        List<TierGroup> tiers = new ArrayList<>();
        for (TierMapping.Tier tier : TierMapping.orderedTiers()) {
            List<MenuDish> inTier = dishes.stream()
                    .filter(d -> tier.code().equals(d.tier()))
                    .toList();
            tiers.add(new TierGroup(tier.code(), tier.label(), inTier));
        }
        return tiers;
    }

    private void persist(MenuRankingResponse response, byte[] imageBytes, Long userId) {
        try {
            MenuScanEntity entity = new MenuScanEntity();
            entity.setUserId(userId);
            entity.setCreatedAt(Instant.now());
            entity.setDishCount(response.dishCount());
            entity.setTruncated(response.truncated());
            entity.setSummary(response.tiers().stream()
                    .flatMap(t -> t.dishes().stream())
                    .map(MenuDish::name)
                    .collect(Collectors.joining(", ")));
            entity.setThumbnail(thumbnailService.thumbnailDataUrl(imageBytes));
            entity.setResultJson(mapper.writeValueAsString(response));
            repository.save(entity);
        } catch (Exception e) {
            // History is best-effort; never fail a scan because persistence hiccuped.
            log.warn("Failed to persist menu scan history: {}", e.getMessage());
        }
    }

    public List<MenuHistoryEntry> history(Long userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new MenuHistoryEntry(e.getId(), e.getCreatedAt(), e.getDishCount(),
                        e.isTruncated(), e.getSummary(), e.getThumbnail()))
                .toList();
    }

    /** Reopens a past menu scan. Scoped to the owning user so one user can't view another's history. */
    public MenuRankingResponse historyDetail(Long id, Long userId) {
        MenuScanEntity entity = repository.findByIdAndUserId(id, userId)
                .orElseThrow(HistoryEntryNotFoundException::new);
        try {
            return mapper.readValue(entity.getResultJson(), MenuRankingResponse.class);
        } catch (Exception e) {
            throw new HistoryEntryNotFoundException();
        }
    }

    /** Same ownership scoping as {@link #historyDetail}. */
    public void deleteEntry(Long id, Long userId) {
        MenuScanEntity entity = repository.findByIdAndUserId(id, userId)
                .orElseThrow(HistoryEntryNotFoundException::new);
        repository.delete(entity);
    }

    /**
     * Realistic sample menu used when no Gemini key is configured (spec build-order
     * step 1, same reasoning as AnalysisService.mockFoods) — spans a range of grade
     * bands so the 5-tier UI can be exercised without API keys. Already-resolved
     * FoodItems, exactly like mockFoods(), so this never touches USDA either.
     */
    private List<FoodItem> mockMenuFoods() {
        return List.of(
                new FoodItem("Steamed fish with ginger", "1 fillet / ~200g",
                        220, 44, 2, 4, 0.6, 1, 440, 0.9, "estimated", "protein", false),
                new FoodItem("Stir-fried mixed vegetables", "1 plate / ~180g",
                        126, 5.4, 16, 5.4, 6.3, 7.2, 576, 0.9, "estimated", "vegetable", false),
                new FoodItem("Plain steamed rice", "1 bowl / ~200g",
                        260, 4.8, 56, 0.6, 0.8, 0.2, 2, 0.95, "estimated", "grain", false),
                new FoodItem("Chicken chop with black pepper sauce", "1 plate / ~300g",
                        630, 72, 30, 27, 2.4, 9, 1440, 0.85, "estimated", "protein", false),
                new FoodItem("Char kway teow", "1 plate / ~350g",
                        616, 21, 77, 24.5, 4.2, 7, 2170, 0.85, "estimated", "grain", true),
                new FoodItem("Sweet and sour pork", "1 plate / ~250g",
                        550, 30, 50, 27.5, 2.5, 35, 1400, 0.85, "estimated", "protein", true),
                new FoodItem("Deep-fried spring rolls", "4 pieces / ~150g",
                        420, 7.5, 39, 25.5, 2.3, 4.5, 780, 0.85, "estimated", "fat", true),
                new FoodItem("Deep-fried chicken wings", "3 pieces / ~180g",
                        522, 36, 11, 36, 0.5, 1, 1098, 0.85, "estimated", "protein", true));
    }

    /** The add-on/drink half of the mock menu, so mock mode exercises that section too. */
    private List<FoodItem> mockMenuAddOns() {
        return List.of(
                new FoodItem("Iced sweetened milk tea", "1 cup / ~350ml",
                        228, 3.5, 45.5, 4.2, 0, 42, 140, 0.9, "estimated", "beverage", false),
                new FoodItem("Sambal", "2 tbsp / ~30g",
                        75, 1.4, 9.8, 3.6, 1.8, 6.2, 380, 0.9, "estimated", "vegetable", false),
                new FoodItem("Telur goreng (fried egg)", "1 egg / ~55g",
                        120, 7, 1, 10, 0, 0.5, 200, 0.9, "estimated", "protein", true));
    }
}
