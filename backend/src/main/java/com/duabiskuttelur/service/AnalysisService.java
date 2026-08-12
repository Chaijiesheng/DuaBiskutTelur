package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.FeedbackResult;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.HistoryEntry;
import com.duabiskuttelur.model.RecentMealPoint;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.service.ScoringService.ScoreResult;
import com.duabiskuttelur.config.AppMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Orchestrates one analysis: AI vision -> USDA lookups -> scoring -> feedback,
 * then persists a history row. When no GEMINI_API_KEY is configured the
 * service returns a realistic mocked analysis so the frontend can be developed
 * and demoed without keys (spec build-order step 1).
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    public static class NoFoodDetectedException extends RuntimeException {
        public NoFoodDetectedException() {
            super("No food detected in the photo");
        }
    }

    private final VisionAnalysisClient visionClient;
    private final UsdaClient usdaClient;
    private final NutritionCacheService nutritionCache;
    private final ScoringService scoringService;
    private final FeedbackService feedbackService;
    private final DashboardService dashboardService;
    private final ThumbnailService thumbnailService;
    private final MealAnalysisRepository repository;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final LocalFoodService localFoodService;
    private final MeterRegistry meters;

    public AnalysisService(VisionAnalysisClient visionClient, UsdaClient usdaClient,
                           NutritionCacheService nutritionCache, LocalFoodService localFoodService,
                           ScoringService scoringService, FeedbackService feedbackService,
                           DashboardService dashboardService, ThumbnailService thumbnailService,
                           MealAnalysisRepository repository, AppProperties props, ObjectMapper mapper,
                           MeterRegistry meters) {
        this.visionClient = visionClient;
        this.usdaClient = usdaClient;
        this.nutritionCache = nutritionCache;
        this.localFoodService = localFoodService;
        this.scoringService = scoringService;
        this.feedbackService = feedbackService;
        this.dashboardService = dashboardService;
        this.thumbnailService = thumbnailService;
        this.repository = repository;
        this.props = props;
        this.mapper = mapper;
        this.meters = meters;
    }

    public AnalysisResponse analyze(byte[] imageBytes, String mediaType, UserEntity user, String lang) {
        // Times the whole pipeline - vision, every USDA lookup, scoring and
        // feedback - because that is the number the user experiences and the
        // one an alert should fire on. The per-stage timers explain it; this
        // one is what "the app is slow" means.
        Timer.Sample sample = Timer.start(meters);
        String outcome = AppMetrics.OUTCOME_ERROR;
        try {
            AnalysisResponse response = runAnalysis(imageBytes, mediaType, user, lang);
            outcome = AppMetrics.OUTCOME_SUCCESS;
            return response;
        } catch (NoFoodDetectedException e) {
            // Not a failure of the app - the photo had no food in it. Counting
            // it as an error would put a permanent floor under the error rate.
            outcome = AppMetrics.OUTCOME_NO_FOOD;
            throw e;
        } finally {
            sample.stop(Timer.builder(AppMetrics.ANALYSIS_DURATION)
                    .description("End-to-end meal analysis, vision through feedback")
                    .tag(AppMetrics.TAG_OUTCOME, outcome)
                    .register(meters));
        }
    }

    private AnalysisResponse runAnalysis(byte[] imageBytes, String mediaType, UserEntity user, String lang) {
        List<FoodItem> foods = props.hasGeminiKey()
                ? identifyAndResolve(imageBytes, mediaType)
                : mockFoods();
        if (foods.isEmpty()) {
            throw new NoFoodDetectedException();
        }

        Totals totals = Totals.of(foods);
        int calorieBudget = (int) Math.round(scoringService.effectiveBudget(user != null ? user.getDailyBudget() : null));
        String goal = user != null ? user.getGoal() : null;
        // The macro split graded against is the one the frontend already shows
        // this user as their target - see MacroTargets.
        ScoreResult score = scoringService.score(foods, totals, calorieBudget, goal);
        // Visitors have no meal history to sum, so there's nothing to compute a
        // remaining budget from — feedbackFor() falls back to fixed thresholds.
        FeedbackService.RemainingBudget remaining = user != null ? remainingBudgetFor(user) : null;
        FeedbackResult feedback = feedbackService.feedbackFor(foods, totals, score, lang, goal, remaining, calorieBudget);

        AnalysisResponse response = new AnalysisResponse(
                foods, totals, score.score(), score.grade(),
                feedback.highlights(), feedback.concerns(), feedback.suggestions(),
                feedback.encouragement(), "photo", scoringService.breakdownFor(score), user != null);

        // Only signed-in users get persistent history; visitor analyses are
        // ephemeral. The saved id rides back on the response so the results
        // screen can offer a portion correction right where the user is
        // looking at the wrong number, rather than only from history later.
        if (user != null) {
            return response.withEntryId(persist(response, imageBytes, user.getId()));
        }
        return response;
    }

    /** Package-visible so BarcodeLookupService can reuse the same remaining-budget calculation. */
    FeedbackService.RemainingBudget remainingBudgetFor(UserEntity user) {
        DashboardService.TodaySoFar soFar = dashboardService.todaySoFar(user);
        return new FeedbackService.RemainingBudget(
                soFar.calorieTarget() - soFar.caloriesSoFar(),
                soFar.proteinTarget() - soFar.proteinSoFar());
    }

    private List<FoodItem> identifyAndResolve(byte[] imageBytes, String mediaType) {
        List<IdentifiedFood> identified = visionClient.identifyFoods(imageBytes, mediaType);
        List<FoodItem> foods = new ArrayList<>();
        for (IdentifiedFood cf : identified) {
            foods.add(resolveNutrition(cf));
        }
        return foods;
    }

    /**
     * Cached USDA lookup per identified food, falling back to the model's per-100g
     * estimate when it fails, scaled to this scan's own portion. Package-visible
     * so MenuRankingService can reuse the same resolution logic per dish.
     */
    FoodItem resolveNutrition(IdentifiedFood cf) {
        return resolveNutrition(cf, false);
    }

    /**
     * Resolves one dish against the per-dish nutrition cache, so a dish already
     * seen resolves to exactly the numbers it resolved to the first time
     * (NutritionCacheService explains why that lookup is otherwise a lottery).
     *
     * @param pinPortion also replay the cached portion instead of the model's.
     *                   A photo shows how much food is actually on the plate, so
     *                   that flow keeps its own observed portion; a menu shows
     *                   only a dish name, and its "portion" is a model guess that
     *                   would otherwise be re-rolled per scan.
     */
    FoodItem resolveNutrition(IdentifiedFood cf, boolean pinPortion) {
        NutritionCacheService.Resolved resolved = nutritionCache.resolve(cf.name(), () -> lookUpPer100g(cf));

        double grams = pinPortion ? resolved.grams() : gramsOf(cf);
        String portion = pinPortion ? resolved.portion() : cf.estimatedPortion();
        double factor = grams / 100.0;
        // The bracket scales by the same per-100g figures as the point estimate,
        // so the range is purely portion uncertainty — it never widens because
        // the USDA match was shaky, which is what `confidence` is for.
        //
        // What gets pinned is the bracket's *shape*, not its size. A menu replays
        // the pinned grams and so replays the pinned bracket outright; a photo
        // supplies its own grams, and takes the pinned ratio around them. Taking
        // this scan's raw gramsLow/gramsHigh instead would let the displayed
        // range move between two photos of the identical portion, since the model
        // re-rolls the bracket on every call — the same variance the cache exists
        // to remove, just expressed in the range rather than in the calories.
        double lowGrams = pinPortion ? resolved.gramsLow() : grams * lowRatio(resolved);
        double highGrams = pinPortion ? resolved.gramsHigh() : grams * highRatio(resolved);

        return new FoodItem(cf.name(), portion,
                round1(resolved.caloriesPer100g() * factor), round1(resolved.proteinPer100g() * factor),
                round1(resolved.carbsPer100g() * factor), round1(resolved.fatPer100g() * factor),
                round1(resolved.fiberPer100g() * factor), round1(resolved.sugarPer100g() * factor),
                round1(resolved.sodiumPer100g() * factor),
                resolved.confidence(), resolved.source(), resolved.foodGroup(), resolved.fried(),
                resolved.cookingMethod(),
                round1(resolved.caloriesPer100g() * lowGrams / 100.0),
                round1(resolved.caloriesPer100g() * highGrams / 100.0));
    }

    /**
     * The actual (non-deterministic) resolution, run once per dish and then
     * pinned by the cache. foodGroup/fried/confidence ride along with the
     * nutrients because ScoringService grades on them too — pinning calories but
     * re-rolling "is it fried" would still let the same dish change grade.
     */
    private NutritionCacheService.Resolved lookUpPer100g(IdentifiedFood cf) {
        String searchTerm = cf.usdaSearchTerm() != null && !cf.usdaSearchTerm().isBlank()
                ? cf.usdaSearchTerm() : cf.name();

        // Local first, and by the dish's own name rather than by usdaSearchTerm:
        // that field exists to translate a local dish into the nearest generic
        // USDA has ("coconut rice" for nasi lemak), which is precisely the
        // approximation this table replaces. Searching the curated database for
        // the generic would throw away its whole reason for existing.
        Optional<NutritionCacheService.Resolved> local = localFoodService.lookup(cf.name());
        if (local.isPresent()) {
            countSource(LocalFoodService.SOURCE);
            return withModelFallbacks(local.get(), cf);
        }

        return usdaClient.lookup(searchTerm)
                .filter(n -> n.calories() > 0)
                .map(n -> { countSource("usda"); return new NutritionCacheService.Resolved(
                        n.calories(), n.protein(), n.carbs(), n.fat(), n.fiber(), n.sugar(), n.sodium(),
                        gramsOf(cf), cf.lowGrams(), cf.highGrams(), cf.estimatedPortion(), "usda",
                        cf.foodGroup(), cf.cookingMethod(), clampConfidence(cf.confidence())); })
                .orElseGet(() -> {
                    // The review's first question - what fraction of analyses fall
                    // back to a model estimate - is the ratio of these two counters.
                    // This line used to be the only signal the AI layer produced.
                    countSource("estimated");
                    log.info("Using model fallback estimate for '{}'", cf.name());
                    return new NutritionCacheService.Resolved(
                            cf.fallbackCaloriesPer100g(), cf.fallbackProteinPer100g(), cf.fallbackCarbsPer100g(),
                            cf.fallbackFatPer100g(), cf.fallbackFiberPer100g(), cf.fallbackSugarPer100g(),
                            cf.fallbackSodiumPer100g(),
                            gramsOf(cf), cf.lowGrams(), cf.highGrams(), cf.estimatedPortion(), "estimated",
                            cf.foodGroup(), cf.cookingMethod(), clampConfidence(cf.confidence()));
                });
    }

    private void countSource(String source) {
        meters.counter(AppMetrics.NUTRITION_SOURCE, AppMetrics.TAG_SOURCE, source).increment();
    }

    /**
     * Fills a curated row's gaps from the model, without letting the model
     * overwrite anything the table actually states.
     *
     * <p>The curated portion is kept, deliberately. This value is the one the
     * cache pins, and the only flow that replays a pinned portion is a menu scan
     * — which has no plate to measure, so a published typical serving is exactly
     * what it wants. The photo flow overrides it with what the model saw anyway
     * ({@code resolveNutrition}), so substituting the scan's grams here would
     * throw the curated serving away and change nothing else.
     *
     * <p>What is borrowed from the scan is the <em>relative</em> width of the
     * portion bracket. The table is certain about composition, not about how much
     * of it is on this particular plate, and collapsing the band to zero would
     * present a photo-derived portion as if it had been measured.
     */
    private static NutritionCacheService.Resolved withModelFallbacks(
            NutritionCacheService.Resolved local, IdentifiedFood cf) {
        double spreadLow = cf.grams() > 0 ? cf.lowGrams() / cf.grams() : 1.0;
        double spreadHigh = cf.grams() > 0 ? cf.highGrams() / cf.grams() : 1.0;
        return new NutritionCacheService.Resolved(
                local.caloriesPer100g(), local.proteinPer100g(), local.carbsPer100g(), local.fatPer100g(),
                local.fiberPer100g(), local.sugarPer100g(), local.sodiumPer100g(),
                local.grams(), local.grams() * spreadLow, local.grams() * spreadHigh, local.portion(),
                local.source(),
                // Only where the curated row left a gap, so a partially-filled
                // row still improves on nothing rather than erasing what the
                // model could tell.
                local.foodGroup() != null ? local.foodGroup() : cf.foodGroup(),
                local.cookingMethod() != null ? local.cookingMethod() : cf.cookingMethod(),
                local.confidence());
    }

    private static double gramsOf(IdentifiedFood cf) {
        return cf.grams() > 0 ? cf.grams() : 100;
    }

    /**
     * The pinned bracket as a multiple of the pinned portion — e.g. 0.8 for a
     * dish first resolved as 200g with a 160g floor. Falls back to 1.0 (no
     * range) rather than dividing by a zero portion.
     */
    private static double lowRatio(NutritionCacheService.Resolved resolved) {
        return resolved.grams() > 0 ? resolved.gramsLow() / resolved.grams() : 1.0;
    }

    /** See {@link #lowRatio}. */
    private static double highRatio(NutritionCacheService.Resolved resolved) {
        return resolved.grams() > 0 ? resolved.gramsHigh() / resolved.grams() : 1.0;
    }

    private Long persist(AnalysisResponse response, byte[] imageBytes, Long userId) {
        return persistInternal(response, userId, thumbnailService.thumbnailDataUrl(imageBytes), "photo");
    }

    /** Package-visible so BarcodeLookupService can save its results into the same history. */
    Long persistBarcodeEntry(AnalysisResponse response, Long userId) {
        return persistInternal(response, userId, null, "barcode");
    }

    /** @return the saved row id, or null when persistence failed (history is best-effort). */
    private Long persistInternal(AnalysisResponse response, Long userId, String thumbnail, String source) {
        try {
            MealAnalysisEntity entity = new MealAnalysisEntity();
            entity.setUserId(userId);
            entity.setCreatedAt(Instant.now());
            entity.setScore(response.score());
            entity.setGrade(response.grade());
            entity.setCalories(response.totals().calories());
            entity.setSummary(response.foods().stream()
                    .map(FoodItem::name)
                    .collect(Collectors.joining(", ")));
            entity.setThumbnail(thumbnail);
            entity.setSource(source);
            entity.setResultJson(mapper.writeValueAsString(response));
            // Denormalized so achievements/dashboard don't need to re-parse
            // resultJson on every read — cheap here since `response` is already
            // fully in memory. Mirrors the per-item logic AchievementsService
            // used to run against parsed JSON on every request.
            List<FoodItem> foods = response.foods();
            entity.setProtein(response.totals().protein());
            entity.setVegetableCount((int) foods.stream()
                    .filter(f -> "vegetable".equalsIgnoreCase(f.foodGroup()))
                    .count());
            entity.setHasFruit(foods.stream().anyMatch(f -> "fruit".equalsIgnoreCase(f.foodGroup())));
            entity.setBeverageOnly(!foods.isEmpty() && foods.stream()
                    .allMatch(f -> FoodKeywords.matchesAny(lower(f.name()), FoodKeywords.BEVERAGE)));
            entity.setCoffeeOnly(!foods.isEmpty() && foods.stream()
                    .allMatch(f -> FoodKeywords.matchesAny(lower(f.name()), FoodKeywords.COFFEE)));
            return repository.save(entity).getId();
        } catch (Exception e) {
            // History is best-effort; never fail an analysis because persistence hiccuped
            log.warn("Failed to persist analysis history: {}", e.getMessage());
            // No id means the client simply gets no correction affordance,
            // which is honest - there is no row to correct.
            return null;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /**
     * The cap that used to live in the {@code findTop50By…} method name. Moved
     * here because JPQL has no LIMIT, so a constructor-expression query has to
     * take a {@code Pageable} — see {@code RecentMealPoint} for why this list
     * being capped is a page size and not an aggregation window.
     */
    public static final int HISTORY_PAGE_SIZE = 50;

    public List<HistoryEntry> history(Long userId) {
        return repository.findHistoryEntries(userId, PageRequest.of(0, HISTORY_PAGE_SIZE));
    }

    /**
     * Complete set of meals in the trailing window, for the weekly trend.
     * Deliberately not the {@link #history} list: that one is capped at fifty
     * rows, which quietly truncated the weekly totals for anyone logging often.
     */
    public List<RecentMealPoint> recentPoints(Long userId, int days) {
        Instant from = LocalDate.now(ZoneId.systemDefault())
                .minusDays(days - 1L)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();
        return repository.findPointsSince(userId, from);
    }

    /** Reopens a past analysis. Scoped to the owning user so one user can't view another's history. */
    public AnalysisResponse historyDetail(Long id, Long userId) {
        MealAnalysisEntity entity = repository.findByIdAndUserId(id, userId)
                .orElseThrow(HistoryEntryNotFoundException::new);
        try {
            // Stamped from the row rather than read out of the JSON, which was
            // written before the row had an id.
            return mapper.readValue(entity.getResultJson(), AnalysisResponse.class)
                    .withEntryId(entity.getId());
        } catch (Exception e) {
            throw new HistoryEntryNotFoundException();
        }
    }

    /** Same ownership scoping as {@link #historyDetail}, but returns the raw entity for PDF rendering. */
    public MealAnalysisEntity historyEntity(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(HistoryEntryNotFoundException::new);
    }

    /** Deletes a past analysis. Scoped to the owning user, same as {@link #historyDetail}. */
    public void deleteEntry(Long id, Long userId) {
        MealAnalysisEntity entity = repository.findByIdAndUserId(id, userId)
                .orElseThrow(HistoryEntryNotFoundException::new);
        repository.delete(entity);
    }

    public static class HistoryEntryNotFoundException extends RuntimeException {
        public HistoryEntryNotFoundException() {
            super("History entry not found");
        }
    }

    /** Realistic sample meal used when no Gemini key is configured. */
    private List<FoodItem> mockFoods() {
        return List.of(
                new FoodItem("Nasi lemak (coconut rice)", "1 cup / ~200g",
                        398, 7.2, 52.1, 18.3, 1.9, 2.1, 520, 0.92, "estimated", "grain", false),
                new FoodItem("Ayam goreng (fried chicken)", "1 thigh / ~120g",
                        290, 21.5, 8.4, 19.2, 0.4, 0.5, 480, 0.88, "estimated", "protein", true),
                new FoodItem("Sambal + cucumber slices", "2 tbsp + 5 slices / ~60g",
                        75, 1.4, 9.8, 3.6, 1.8, 6.2, 380, 0.75, "estimated", "vegetable", false),
                new FoodItem("Telur rebus (boiled egg)", "1 egg / ~50g",
                        68, 5.6, 0.6, 4.7, 0.0, 0.3, 62, 0.95, "estimated", "protein", false));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double clampConfidence(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
