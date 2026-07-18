package com.duabiskuttelur.service;

import com.duabiskuttelur.client.OpenFoodFactsClient;
import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.FeedbackResult;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.service.ScoringService.ScoreResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves a scanned barcode straight to a graded result, bypassing the vision
 * model entirely — the label already has exact nutrition, so there's nothing
 * for Gemini to estimate. Reuses the same scoring/history pipeline as a photo
 * analysis so barcode and photo meals behave identically everywhere except
 * how the numbers were sourced.
 */
@Service
public class BarcodeLookupService {

    public static class BarcodeNotFoundException extends RuntimeException {
        public BarcodeNotFoundException() {
            super("Product not found");
        }
    }

    private final OpenFoodFactsClient client;
    private final ScoringService scoringService;
    private final FeedbackService feedbackService;
    private final AnalysisService analysisService;

    public BarcodeLookupService(OpenFoodFactsClient client, ScoringService scoringService,
                                 FeedbackService feedbackService, AnalysisService analysisService) {
        this.client = client;
        this.scoringService = scoringService;
        this.feedbackService = feedbackService;
        this.analysisService = analysisService;
    }

    /** Name + unit basis only, no scoring/persistence — lets the UI show what it found before the user commits to a serving count. */
    public record ProductInfo(String name, String unitLabel, boolean perServing) {
    }

    public ProductInfo lookupProduct(String barcode) {
        OpenFoodFactsClient.Product product = client.lookup(barcode)
                .orElseThrow(BarcodeNotFoundException::new);
        return new ProductInfo(product.name(), product.unitLabel(), product.perServing());
    }

    public AnalysisResponse lookup(String barcode, double servings, UserEntity user, String lang) {
        OpenFoodFactsClient.Product product = client.lookup(barcode)
                .orElseThrow(BarcodeNotFoundException::new);

        FoodItem item = toFoodItem(product, servings);
        List<FoodItem> foods = List.of(item);
        Totals totals = Totals.of(foods);
        int calorieBudget = (int) Math.round(scoringService.effectiveBudget(user != null ? user.getDailyBudget() : null));
        ScoreResult score = scoringService.score(foods, totals, calorieBudget);

        String goal = user != null ? user.getGoal() : null;
        FeedbackService.RemainingBudget remaining = user != null ? analysisService.remainingBudgetFor(user) : null;
        FeedbackResult feedback = feedbackService.ruleBasedFeedbackOnly(foods, totals, score, lang, goal, remaining);

        AnalysisResponse response = new AnalysisResponse(
                foods, totals, score.score(), score.grade(),
                feedback.highlights(), feedback.concerns(), feedback.suggestions(),
                feedback.encouragement(), "barcode", scoringService.breakdownFor(score), user != null);

        if (user != null) {
            analysisService.persistBarcodeEntry(response, user.getId());
        }
        return response;
    }

    private FoodItem toFoodItem(OpenFoodFactsClient.Product product, double servings) {
        String portion = servings == 1
                ? product.unitLabel()
                : "%.1f x %s".formatted(servings, product.unitLabel());
        return new FoodItem(
                product.name(), portion,
                round1(product.calories() * servings), round1(product.protein() * servings),
                round1(product.carbs() * servings), round1(product.fat() * servings),
                round1(product.fiber() * servings), round1(product.sugar() * servings),
                round1(product.sodium() * servings),
                1.0, "barcode", inferFoodGroup(product.categoryTags()), false);
    }

    /**
     * Best-effort mapping from Open Food Facts category tags to the app's
     * eight food groups, so scanned products still count meaningfully toward
     * the variety/balance scoring signals used for photo-derived meals.
     */
    private String inferFoodGroup(List<String> categoryTags) {
        String joined = String.join(" ", categoryTags).toLowerCase();
        if (containsAny(joined, "beverage", "drink", "water", "juice", "soda", "coffee", "tea")) return "beverage";
        if (containsAny(joined, "dairy", "milk", "yogurt", "yoghurt", "cheese")) return "dairy";
        if (containsAny(joined, "chocolate", "candy", "biscuit", "cake", "dessert", "sweet", "cookie", "ice-cream")) return "sweet";
        if (containsAny(joined, "chip", "crisp", "snack")) return "fat";
        if (containsAny(joined, "bread", "cereal", "pasta", "rice", "grain", "noodle")) return "grain";
        if (containsAny(joined, "meat", "fish", "poultry", "egg", "nut", "legume", "tofu")) return "protein";
        if (containsAny(joined, "vegetable")) return "vegetable";
        if (containsAny(joined, "fruit")) return "fruit";
        // Unrecognized category: "fat" (mixed/processed) is a more honest
        // catch-all than "sweet" — an unclassifiable savory product shouldn't
        // default into the dessert bucket.
        return "fat";
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
