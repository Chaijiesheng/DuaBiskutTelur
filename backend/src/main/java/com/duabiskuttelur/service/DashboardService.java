package com.duabiskuttelur.service;

import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.DashboardResponse;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Builds the "today at a glance" summary shown right after login. */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private static final int DEFAULT_CALORIE_TARGET = 2000;
    // No stored protein target yet, so it's derived: bodyweight-based when we know
    // it, scaled by goal (higher for weight loss to protect lean mass in a
    // deficit, lowest for maintenance), otherwise a 15%-of-calories estimate.
    // Unknown/null goal defaults to the maintenance ratio.
    private static final double PROTEIN_CALORIE_SHARE = 0.15;

    private final MealAnalysisRepository repository;
    private final ScoringService scoringService;
    private final ObjectMapper mapper;

    public DashboardService(MealAnalysisRepository repository, ScoringService scoringService, ObjectMapper mapper) {
        this.repository = repository;
        this.scoringService = scoringService;
        this.mapper = mapper;
    }

    public DashboardResponse today(UserEntity user) {
        List<MealAnalysisEntity> entries = todaysEntries(user.getId());
        int calorieTarget = calorieTargetFor(user);
        int proteinTarget = proteinTargetFor(user, calorieTarget);

        if (entries.isEmpty()) {
            return new DashboardResponse(false, 0, calorieTarget, 0, proteinTarget, 0, null);
        }

        double totalCalories = 0;
        double totalProtein = 0;
        int totalScore = 0;
        for (MealAnalysisEntity entry : entries) {
            totalCalories += entry.getCalories();
            totalScore += entry.getScore();
            totalProtein += proteinFor(entry);
        }
        int avgScore = (int) Math.round(totalScore / (double) entries.size());

        return new DashboardResponse(true, round1(totalCalories), calorieTarget,
                round1(totalProtein), proteinTarget, entries.size(), scoringService.gradeFor(avgScore));
    }

    /**
     * Calories/protein logged so far today (not including a meal about to be
     * analyzed) plus the day's targets. Used by AnalysisService to compute
     * how much budget is left before generating goal-aware feedback for a
     * new meal — same "today" window as {@link #today}, just without the
     * score/grade aggregation that endpoint also needs.
     */
    public TodaySoFar todaySoFar(UserEntity user) {
        List<MealAnalysisEntity> entries = todaysEntries(user.getId());
        double totalCalories = 0;
        double totalProtein = 0;
        for (MealAnalysisEntity entry : entries) {
            totalCalories += entry.getCalories();
            totalProtein += proteinFor(entry);
        }
        int calorieTarget = calorieTargetFor(user);
        int proteinTarget = proteinTargetFor(user, calorieTarget);
        return new TodaySoFar(totalCalories, totalProtein, calorieTarget, proteinTarget);
    }

    public record TodaySoFar(double caloriesSoFar, double proteinSoFar, int calorieTarget, int proteinTarget) {
    }

    private List<MealAnalysisEntity> todaysEntries(Long userId) {
        ZoneId zone = ZoneId.systemDefault();
        Instant startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant endOfDay = startOfDay.plusSeconds(86400);
        return repository.findByUserIdAndCreatedAtBetween(userId, startOfDay, endOfDay);
    }

    private static int calorieTargetFor(UserEntity user) {
        return user.getDailyBudget() != null ? user.getDailyBudget() : DEFAULT_CALORIE_TARGET;
    }

    private static int proteinTargetFor(UserEntity user, int calorieTarget) {
        return user.getWeightKg() != null
                ? (int) Math.round(user.getWeightKg() * proteinGramsPerKg(user.getGoal()))
                : (int) Math.round(calorieTarget * PROTEIN_CALORIE_SHARE / 4);
    }

    private static double proteinGramsPerKg(String goal) {
        return switch (goal == null ? "maintenance" : goal) {
            case "weight_loss" -> 2.0;
            case "muscle_gain" -> 1.8;
            default -> 1.5;
        };
    }

    private double proteinFor(MealAnalysisEntity entry) {
        // Denormalized at write time (AnalysisService) so the common case never
        // touches resultJson — fall back to parsing it only for rows saved
        // before that column existed.
        if (entry.getProtein() != null) {
            return entry.getProtein();
        }
        try {
            AnalysisResponse response = mapper.readValue(entry.getResultJson(), AnalysisResponse.class);
            return response.totals().protein();
        } catch (Exception e) {
            log.warn("Failed to parse stored result for protein total: {}", e.getMessage());
            return 0;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
