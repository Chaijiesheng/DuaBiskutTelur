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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        Result identified = props.hasGeminiKey()
                ? identifyAndResolveMenu(imageBytes, mediaType)
                : new Result(mockMenuFoods(), false);
        if (identified.foods().isEmpty()) {
            throw new NoDishesDetectedException();
        }

        int calorieBudget = (int) Math.round(
                scoringService.effectiveBudget(user != null ? user.getDailyBudget() : null));

        List<MenuDish> dishes = new ArrayList<>();
        for (FoodItem item : identified.foods()) {
            List<FoodItem> single = List.of(item);
            ScoringService.ScoreResult score = scoringService.score(single, Totals.of(single), calorieBudget);
            dishes.add(new MenuDish(item.name(), item.estimatedPortion(),
                    score.score(), score.grade(), TierMapping.tierFor(score.grade()), item));
        }

        MenuRankingResponse response = new MenuRankingResponse(
                groupIntoTiers(dishes), dishes.size(), identified.truncated(), user != null);

        if (user != null) {
            persist(response, imageBytes, user.getId());
        }
        return response;
    }

    private record Result(List<FoodItem> foods, boolean truncated) {
    }

    private Result identifyAndResolveMenu(byte[] imageBytes, String mediaType) {
        List<IdentifiedFood> identified = visionClient.identifyMenuDishes(imageBytes, mediaType);
        boolean truncated = identified.size() > MAX_DISHES;
        if (truncated) {
            identified = identified.subList(0, MAX_DISHES);
        }
        List<FoodItem> foods = new ArrayList<>();
        for (IdentifiedFood cf : identified) {
            foods.add(analysisService.resolveNutrition(cf));
        }
        return new Result(foods, truncated);
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
                new FoodItem("Iced sweetened milk tea", "1 cup / ~350ml",
                        228, 3.5, 45.5, 4.2, 0, 42, 140, 0.9, "estimated", "beverage", false),
                new FoodItem("Deep-fried chicken wings", "3 pieces / ~180g",
                        522, 36, 11, 36, 0.5, 1, 1098, 0.85, "estimated", "protein", true));
    }
}
