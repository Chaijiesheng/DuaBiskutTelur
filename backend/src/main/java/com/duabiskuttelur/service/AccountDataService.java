package com.duabiskuttelur.service;

import com.duabiskuttelur.model.AccountExport;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.MenuScanEntity;
import com.duabiskuttelur.persistence.MenuScanRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.UserRepository;
import com.duabiskuttelur.persistence.WaterRepository;
import com.duabiskuttelur.persistence.WeightRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The two things a person is entitled to do with their own account: take a copy
 * of it, and have it erased.
 *
 * <p>Erasure has to remove every table keyed to the user explicitly, because
 * none of the {@code user_id} columns carry a foreign key — there is nothing for
 * a cascade to travel along, so a table added later and not listed here would
 * silently outlive the account it belongs to. {@code nutrition_cache} is the one
 * deliberate exception: it is keyed by dish name, shared across all users, and
 * contains nothing about any of them.
 */
@Service
public class AccountDataService {

    private static final Logger log = LoggerFactory.getLogger(AccountDataService.class);

    private final UserRepository userRepository;
    private final MealAnalysisRepository mealRepository;
    private final MenuScanRepository menuScanRepository;
    private final WaterRepository waterRepository;
    private final WeightRepository weightRepository;
    private final WorkoutService workoutService;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final ObjectMapper mapper;

    public AccountDataService(UserRepository userRepository,
                              MealAnalysisRepository mealRepository,
                              MenuScanRepository menuScanRepository,
                              WaterRepository waterRepository,
                              WeightRepository weightRepository,
                              WorkoutService workoutService,
                              FindByIndexNameSessionRepository<? extends Session> sessionRepository,
                              ObjectMapper mapper) {
        this.userRepository = userRepository;
        this.mealRepository = mealRepository;
        this.menuScanRepository = menuScanRepository;
        this.waterRepository = waterRepository;
        this.weightRepository = weightRepository;
        this.workoutService = workoutService;
        this.sessionRepository = sessionRepository;
        this.mapper = mapper;
    }

    public AccountExport export(UserEntity user) {
        Long userId = user.getId();
        return new AccountExport(
                Instant.now(),
                profileOf(user),
                mealRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toMeal).toList(),
                menuScanRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toMenuScan).toList(),
                waterRepository.findByUserIdOrderByDateDesc(userId).stream()
                        .map(w -> new AccountExport.WaterDay(w.getDate(), w.getTotalMl())).toList(),
                weightRepository.findByUserIdOrderByLoggedAtDesc(userId).stream()
                        .map(w -> new AccountExport.WeighIn(w.getLoggedAt(), w.getWeightKg())).toList());
    }

    /**
     * Sessions go first, on purpose. Every other device holding a live session
     * would otherwise keep authenticating during the delete — and
     * {@link UserService#currentUserOrNull()} recreates a missing user row from
     * the session's own OAuth attributes, so one request landing at the wrong
     * moment would quietly resurrect the account that was just erased. Revoking
     * access first also means a failure partway through leaves the account
     * unreachable rather than half-deleted but still usable.
     */
    @Transactional
    public void deleteAccount(UserEntity user) {
        revokeAllSessions(user.getGoogleSub());

        Long userId = user.getId();
        int meals = mealRepository.deleteByUserId(userId);
        int menuScans = menuScanRepository.deleteByUserId(userId);
        int waterDays = waterRepository.deleteByUserId(userId);
        int weighIns = weightRepository.deleteByUserId(userId);
        // Delegated rather than inlined like the four above, because two of the
        // workout tables carry no user_id and can only be reached through this
        // user's sessions — so the order matters and belongs next to the code
        // that knows why. See WorkoutService.deleteAllForUser.
        int workoutRows = workoutService.deleteAllForUser(userId);
        userRepository.delete(user);

        // Counts, never the email or name — this line outlives the account that
        // would have made logging them defensible.
        log.info("Deleted account {}: {} meals, {} menu scans, {} water days, {} weigh-ins, {} workout rows",
                userId, meals, menuScans, waterDays, weighIns, workoutRows);
    }

    /** Signs the account out of every device, not just the one asking. */
    private void revokeAllSessions(String googleSub) {
        Set<String> sessionIds = sessionRepository.findByPrincipalName(googleSub).keySet();
        sessionIds.forEach(sessionRepository::deleteById);
        log.info("Revoked {} session(s) before deleting the account", sessionIds.size());
    }

    private static AccountExport.Profile profileOf(UserEntity u) {
        return new AccountExport.Profile(
                u.getEmail(), u.getName(), u.getPictureUrl(), u.getCreatedAt(),
                u.getAge(), u.getSex(), u.getWeightKg(), u.getHeightCm(), u.getSteps(),
                u.getExerciseFrequency(), u.getGoal(), u.getDailyBudget(), u.getWaterTargetMl());
    }

    private AccountExport.Meal toMeal(MealAnalysisEntity e) {
        return new AccountExport.Meal(e.getId(), e.getCreatedAt(), e.getSource(), e.getScore(),
                e.getGrade(), e.getCalories(), e.getSummary(), e.getThumbnail(), parse(e.getResultJson()));
    }

    private AccountExport.MenuScan toMenuScan(MenuScanEntity e) {
        return new AccountExport.MenuScan(e.getId(), e.getCreatedAt(), e.getDishCount(),
                e.isTruncated(), e.getSummary(), e.getThumbnail(), parse(e.getResultJson()));
    }

    /**
     * Embeds the stored analysis as real JSON rather than an escaped string.
     * A row whose JSON can't be read still exports its columns — a corrupt
     * result is not a reason to withhold the rest of someone's record.
     */
    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.warn("Stored result JSON could not be parsed for export: {}", e.getMessage());
            return null;
        }
    }
}
