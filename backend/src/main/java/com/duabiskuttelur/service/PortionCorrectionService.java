package com.duabiskuttelur.service;

import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.service.ScoringService.ScoreResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets a user say "that was half that much" and re-grades the meal from it.
 *
 * <p>Portion is the single largest error source in the pipeline: the model
 * guesses grams from a 2D photo with no depth, no reference object and no
 * plate-size calibration. Until now the guess was final — a meal logged at
 * double its real size stayed in the day's totals, the calorie budget and the
 * achievement counts at double, and the only available correction was deleting
 * the entry and starting over.
 *
 * <p>Three properties this deliberately has:
 *
 * <ul>
 *   <li><b>The server never accepts nutrition from the client.</b> The request
 *       carries multipliers and nothing else; the numbers come from the stored
 *       row. Taking client-supplied foods would be simpler and would let anyone
 *       post a fabricated A+ meal into their own history, which feeds streaks
 *       and achievements.</li>
 *   <li><b>Multipliers are absolute, not cumulative.</b> Each item keeps its
 *       {@code portionMultiplier}, and a correction scales by
 *       {@code new / old} — so 0.5 then 2.0 lands exactly back on the model's
 *       original figures instead of drifting, and the slider can be dragged
 *       around without accumulating error.</li>
 *   <li><b>No model call.</b> Re-scoring is the same deterministic Java the
 *       original grade came from, and the feedback is regenerated from the
 *       rule-based path. A correction is therefore instant and free, which is
 *       what makes it reasonable to offer as a slider rather than a form.</li>
 * </ul>
 *
 * <p>What it deliberately does <em>not</em> do: write back to
 * {@code nutrition_cache}. That table is shared by every user and is what menu
 * scans replay as a typical restaurant serving — one person correcting their own
 * plate is not evidence about that, and letting it through would mean any user
 * could move every other user's menu numbers.
 */
@Service
public class PortionCorrectionService {

    /**
     * Bounds on a single correction. Wide enough for any real "I ate half of
     * that" or "that was a double portion", narrow enough that a multiplier
     * cannot be used to write an arbitrary meal into history by way of a
     * plausible-looking one.
     */
    public static final double MIN_MULTIPLIER = 0.25;
    public static final double MAX_MULTIPLIER = 4.0;

    private final MealAnalysisRepository repository;
    private final ScoringService scoringService;
    private final FeedbackService feedbackService;
    private final AnalysisService analysisService;
    private final ObjectMapper mapper;

    public PortionCorrectionService(MealAnalysisRepository repository, ScoringService scoringService,
                                     FeedbackService feedbackService, AnalysisService analysisService,
                                     ObjectMapper mapper) {
        this.repository = repository;
        this.scoringService = scoringService;
        this.feedbackService = feedbackService;
        this.analysisService = analysisService;
        this.mapper = mapper;
    }

    /**
     * Applies one multiplier per food, in the order the foods were returned, and
     * re-saves the corrected meal.
     *
     * @throws IllegalArgumentException when the multipliers do not line up with
     *         the stored meal — a mismatch means the client is working from a
     *         different version of the entry, and silently applying what does
     *         line up would corrupt the row
     */
    @Transactional
    public AnalysisResponse correct(Long entryId, UserEntity user, List<Double> multipliers, String lang) {
        MealAnalysisEntity entity = repository.findByIdAndUserId(entryId, user.getId())
                .orElseThrow(AnalysisService.HistoryEntryNotFoundException::new);

        AnalysisResponse stored = parse(entity);
        List<FoodItem> foods = stored.foods();
        if (multipliers == null || multipliers.size() != foods.size()) {
            throw new IllegalArgumentException("Expected " + foods.size()
                    + " portion multipliers, got " + (multipliers == null ? 0 : multipliers.size()));
        }

        List<FoodItem> corrected = new ArrayList<>(foods.size());
        for (int i = 0; i < foods.size(); i++) {
            corrected.add(rescale(foods.get(i), clamp(multipliers.get(i))));
        }

        AnalysisResponse response = regrade(corrected, stored, user, lang);
        save(entity, response);
        return response.withEntryId(entity.getId());
    }

    /**
     * Thrown when removing an item would empty the meal. Distinct from a bad
     * index so the client can say something useful: the user asking to remove
     * the only item is not making a mistake, they are telling us the whole entry
     * is wrong — which is what deleting it is for.
     */
    public static class LastFoodException extends RuntimeException {
        public LastFoodException() {
            super("A meal has to have at least one food. Delete the whole entry instead.");
        }
    }

    /**
     * "That isn't in the photo." Drops one misidentified item and re-grades what
     * is left.
     *
     * <p>A portion multiplier cannot express this. Its floor is 0.25×, and even
     * an unbounded one would only shrink a hallucinated dish toward zero while
     * still counting it toward variety and the food-group mix — a phantom
     * vegetable would keep earning its bonus at any multiplier. Removal is a
     * different claim about the photo, so it is a different operation.
     *
     * @param index position in the stored food list, as returned to the client
     */
    @Transactional
    public AnalysisResponse removeFood(Long entryId, UserEntity user, int index, String lang) {
        MealAnalysisEntity entity = repository.findByIdAndUserId(entryId, user.getId())
                .orElseThrow(AnalysisService.HistoryEntryNotFoundException::new);

        AnalysisResponse stored = parse(entity);
        List<FoodItem> foods = stored.foods();
        if (index < 0 || index >= foods.size()) {
            throw new IllegalArgumentException(
                    "No food at position " + index + "; this meal has " + foods.size());
        }
        if (foods.size() == 1) {
            throw new LastFoodException();
        }

        List<FoodItem> remaining = new ArrayList<>(foods);
        remaining.remove(index);

        AnalysisResponse response = regrade(remaining, stored, user, lang);
        save(entity, response);
        return response.withEntryId(entity.getId());
    }

    private AnalysisResponse regrade(List<FoodItem> foods, AnalysisResponse stored, UserEntity user, String lang) {
        Totals totals = Totals.of(foods);
        int budget = (int) Math.round(scoringService.effectiveBudget(user.getDailyBudget()));
        ScoreResult score = scoringService.score(foods, totals, budget, user.getGoal());

        // Rule-based rather than another Gemini call. The prose has to match the
        // numbers - feedback about a 900 kcal meal under a corrected 450 kcal
        // total is worse than plainer wording - and a correction that cost an
        // API round trip could not be a slider.
        var feedback = feedbackService.ruleBasedFeedbackOnly(foods, totals, score, lang,
                user.getGoal(), analysisService.remainingBudgetFor(user));

        return new AnalysisResponse(foods, totals, score.score(), score.grade(),
                feedback.highlights(), feedback.concerns(), feedback.suggestions(),
                feedback.encouragement(), stored.source(), scoringService.breakdownFor(score),
                true);
    }

    /**
     * Scales by the change since the last correction, not by the requested
     * multiplier — the stored numbers already carry whatever correction came
     * before, so applying the request directly would compound it.
     */
    private static FoodItem rescale(FoodItem food, double multiplier) {
        double factor = multiplier / food.portionMultiplier();
        return new FoodItem(
                food.name(), food.estimatedPortion(),
                round1(food.calories() * factor), round1(food.protein() * factor),
                round1(food.carbs() * factor), round1(food.fat() * factor),
                round1(food.fiber() * factor), round1(food.sugar() * factor),
                round1(food.sodium() * factor),
                // Identification confidence is about *what* the dish is, which a
                // portion correction says nothing about. The portion bracket does
                // scale, since it is a claim about size.
                food.confidence(), food.source(), food.foodGroup(), food.fried(), food.cookingMethod(),
                round1(food.caloriesLow() * factor), round1(food.caloriesHigh() * factor),
                multiplier);
    }

    private static double clamp(Double multiplier) {
        if (multiplier == null || multiplier.isNaN()) {
            throw new IllegalArgumentException("Portion multiplier must be a number");
        }
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, multiplier));
    }

    private AnalysisResponse parse(MealAnalysisEntity entity) {
        try {
            return mapper.readValue(entity.getResultJson(), AnalysisResponse.class);
        } catch (Exception e) {
            throw new AnalysisService.HistoryEntryNotFoundException();
        }
    }

    /**
     * Rewrites the row, including the denormalized achievement columns. Those are
     * derived from the foods, so leaving them at their pre-correction values
     * would let the dashboard and the achievements disagree with the meal the
     * user is looking at.
     */
    private void save(MealAnalysisEntity entity, AnalysisResponse response) {
        try {
            entity.setResultJson(mapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize the corrected analysis", e);
        }
        entity.setScore(response.score());
        entity.setGrade(response.grade());
        entity.setCalories(response.totals().calories());
        entity.setProtein(response.totals().protein());
        // Vegetable count, fruit and the beverage/coffee flags are all derived
        // from names and food groups, which a portion correction cannot change -
        // they are left alone deliberately rather than by omission.
        repository.save(entity);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
