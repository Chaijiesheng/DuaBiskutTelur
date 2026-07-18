package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.FeedbackResult;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.HistoryEntry;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.service.ScoringService.ScoreResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final ScoringService scoringService;
    private final FeedbackService feedbackService;
    private final DashboardService dashboardService;
    private final ThumbnailService thumbnailService;
    private final MealAnalysisRepository repository;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public AnalysisService(VisionAnalysisClient visionClient, UsdaClient usdaClient,
                           ScoringService scoringService, FeedbackService feedbackService,
                           DashboardService dashboardService, ThumbnailService thumbnailService,
                           MealAnalysisRepository repository, AppProperties props, ObjectMapper mapper) {
        this.visionClient = visionClient;
        this.usdaClient = usdaClient;
        this.scoringService = scoringService;
        this.feedbackService = feedbackService;
        this.dashboardService = dashboardService;
        this.thumbnailService = thumbnailService;
        this.repository = repository;
        this.props = props;
        this.mapper = mapper;
    }

    public AnalysisResponse analyze(byte[] imageBytes, String mediaType, UserEntity user, String lang) {
        List<FoodItem> foods = props.hasGeminiKey()
                ? identifyAndResolve(imageBytes, mediaType)
                : mockFoods();
        if (foods.isEmpty()) {
            throw new NoFoodDetectedException();
        }

        Totals totals = Totals.of(foods);
        int calorieBudget = (int) Math.round(scoringService.effectiveBudget(user != null ? user.getDailyBudget() : null));
        ScoreResult score = scoringService.score(foods, totals, calorieBudget);
        String goal = user != null ? user.getGoal() : null;
        // Visitors have no meal history to sum, so there's nothing to compute a
        // remaining budget from — feedbackFor() falls back to fixed thresholds.
        FeedbackService.RemainingBudget remaining = user != null ? remainingBudgetFor(user) : null;
        FeedbackResult feedback = feedbackService.feedbackFor(foods, totals, score, lang, goal, remaining, calorieBudget);

        AnalysisResponse response = new AnalysisResponse(
                foods, totals, score.score(), score.grade(),
                feedback.highlights(), feedback.concerns(), feedback.suggestions(),
                feedback.encouragement(), "photo", scoringService.breakdownFor(score), user != null);

        // Only signed-in users get persistent history; visitor analyses are ephemeral.
        if (user != null) {
            persist(response, imageBytes, user.getId());
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

    /** USDA lookup per identified food; fall back to the model's per-100g estimate when it fails. */
    private FoodItem resolveNutrition(IdentifiedFood cf) {
        double grams = cf.grams() > 0 ? cf.grams() : 100;
        double factor = grams / 100.0;

        String searchTerm = cf.usdaSearchTerm() != null && !cf.usdaSearchTerm().isBlank()
                ? cf.usdaSearchTerm() : cf.name();

        return usdaClient.lookup(searchTerm)
                .filter(n -> n.calories() > 0)
                .map(n -> new FoodItem(cf.name(), cf.estimatedPortion(),
                        round1(n.calories() * factor), round1(n.protein() * factor),
                        round1(n.carbs() * factor), round1(n.fat() * factor),
                        round1(n.fiber() * factor), round1(n.sugar() * factor),
                        round1(n.sodium() * factor),
                        clampConfidence(cf.confidence()), "usda", cf.foodGroup(), cf.fried()))
                .orElseGet(() -> {
                    log.info("Using model fallback estimate for '{}'", cf.name());
                    return new FoodItem(cf.name(), cf.estimatedPortion(),
                            round1(cf.fallbackCaloriesPer100g() * factor), round1(cf.fallbackProteinPer100g() * factor),
                            round1(cf.fallbackCarbsPer100g() * factor), round1(cf.fallbackFatPer100g() * factor),
                            round1(cf.fallbackFiberPer100g() * factor), round1(cf.fallbackSugarPer100g() * factor),
                            round1(cf.fallbackSodiumPer100g() * factor),
                            clampConfidence(cf.confidence()), "estimated", cf.foodGroup(), cf.fried());
                });
    }

    private void persist(AnalysisResponse response, byte[] imageBytes, Long userId) {
        persistInternal(response, userId, thumbnailService.thumbnailDataUrl(imageBytes), "photo");
    }

    /** Package-visible so BarcodeLookupService can save its results into the same history. */
    void persistBarcodeEntry(AnalysisResponse response, Long userId) {
        persistInternal(response, userId, null, "barcode");
    }

    private void persistInternal(AnalysisResponse response, Long userId, String thumbnail, String source) {
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
            repository.save(entity);
        } catch (Exception e) {
            // History is best-effort; never fail an analysis because persistence hiccuped
            log.warn("Failed to persist analysis history: {}", e.getMessage());
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    public List<HistoryEntry> history(Long userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new HistoryEntry(e.getId(), e.getCreatedAt(), e.getScore(),
                        e.getGrade(), e.getCalories(), e.getSummary(), e.getThumbnail(), e.getSource()))
                .toList();
    }

    /** Reopens a past analysis. Scoped to the owning user so one user can't view another's history. */
    public AnalysisResponse historyDetail(Long id, Long userId) {
        MealAnalysisEntity entity = repository.findByIdAndUserId(id, userId)
                .orElseThrow(HistoryEntryNotFoundException::new);
        try {
            return mapper.readValue(entity.getResultJson(), AnalysisResponse.class);
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
