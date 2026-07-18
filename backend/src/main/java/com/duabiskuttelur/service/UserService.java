package com.duabiskuttelur.service;

import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Resolves and updates the currently authenticated user. A user row is created
 * on first login (or lazily on first authenticated request) keyed by the Google
 * "sub" claim.
 */
@Service
public class UserService {

    // Matches the account-menu input's min/max — that HTML validation never
    // actually fires since the field isn't inside a <form>, so this was the
    // only thing standing between a user and a 1kcal or 999999kcal budget.
    private static final int MIN_DAILY_BUDGET = 1000;
    private static final int MAX_DAILY_BUDGET = 5000;

    // Mirrors the profile form's HTML min/max (ProfileScreen.jsx) and the enums
    // CalorieBudget understands. Until now those form constraints were the only
    // guard — any direct API call could store age -5 or weight 99999.
    private static final int MIN_AGE = 10;
    private static final int MAX_AGE = 100;
    private static final double MIN_WEIGHT_KG = 30;
    private static final double MAX_WEIGHT_KG = 250;
    private static final double MIN_HEIGHT_CM = 100;
    private static final double MAX_HEIGHT_CM = 230;
    private static final int MAX_STEPS = 60_000;
    private static final Set<String> SEXES = Set.of("male", "female");
    private static final Set<String> EXERCISE_FREQUENCIES = Set.of("not_workout", "normal_workout", "daily_workout");
    private static final Set<String> GOALS = Set.of("weight_loss", "muscle_gain", "maintenance");

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    /** Create or refresh a user from Google's OAuth2 attributes. */
    @Transactional
    public UserEntity upsertFromAttributes(Map<String, Object> attributes) {
        String sub = (String) attributes.get("sub");
        if (sub == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Google account id");
        }
        UserEntity user = repository.findByGoogleSub(sub).orElseGet(UserEntity::new);
        user.setGoogleSub(sub);
        user.setEmail((String) attributes.get("email"));
        user.setName((String) attributes.get("name"));
        user.setPictureUrl((String) attributes.get("picture"));
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(Instant.now());
        }
        return repository.save(user);
    }

    /** The user behind the current request, or null if this is an anonymous visitor. */
    @Transactional
    public UserEntity currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OAuth2User principal) {
            String sub = principal.getAttribute("sub");
            if (sub != null) {
                return repository.findByGoogleSub(sub)
                        .orElseGet(() -> upsertFromAttributes(principal.getAttributes()));
            }
        }
        return null;
    }

    /** The user behind the current request, or 401 if unauthenticated. */
    @Transactional
    public UserEntity currentUser() {
        UserEntity user = currentUserOrNull();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }

    @Transactional
    public UserEntity updateProfile(UserEntity user, Integer age, String sex, Double weightKg,
                                    Double heightCm, Integer steps, String exerciseFrequency, String goal) {
        validateProfile(age, sex, weightKg, heightCm, steps, exerciseFrequency, goal);
        user.setAge(age);
        user.setSex(sex);
        user.setWeightKg(weightKg);
        user.setHeightCm(heightCm);
        user.setSteps(steps);
        user.setExerciseFrequency(exerciseFrequency);
        user.setGoal(goal);
        user.setDailyBudget(CalorieBudget.compute(age, sex, weightKg, heightCm, steps, exerciseFrequency, goal));
        return repository.save(user);
    }

    @Transactional
    public UserEntity updateBudget(UserEntity user, Integer dailyBudget) {
        validateBudget(dailyBudget);
        user.setDailyBudget(dailyBudget);
        return repository.save(user);
    }

    public static void validateBudget(Integer dailyBudget) {
        if (dailyBudget == null || dailyBudget < MIN_DAILY_BUDGET || dailyBudget > MAX_DAILY_BUDGET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Daily budget must be between %d and %dkcal".formatted(MIN_DAILY_BUDGET, MAX_DAILY_BUDGET));
        }
    }

    /** Same 400-on-bad-input contract as {@link #validateBudget}; steps is the only optional field. */
    public static void validateProfile(Integer age, String sex, Double weightKg, Double heightCm,
                                       Integer steps, String exerciseFrequency, String goal) {
        if (age == null || age < MIN_AGE || age > MAX_AGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Age must be between %d and %d".formatted(MIN_AGE, MAX_AGE));
        }
        if (sex == null || !SEXES.contains(sex)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sex must be one of " + SEXES);
        }
        if (weightKg == null || weightKg < MIN_WEIGHT_KG || weightKg > MAX_WEIGHT_KG) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Weight must be between %.0f and %.0fkg".formatted(MIN_WEIGHT_KG, MAX_WEIGHT_KG));
        }
        if (heightCm == null || heightCm < MIN_HEIGHT_CM || heightCm > MAX_HEIGHT_CM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Height must be between %.0f and %.0fcm".formatted(MIN_HEIGHT_CM, MAX_HEIGHT_CM));
        }
        if (steps != null && (steps < 0 || steps > MAX_STEPS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Steps must be between 0 and %d".formatted(MAX_STEPS));
        }
        if (exerciseFrequency == null || !EXERCISE_FREQUENCIES.contains(exerciseFrequency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Exercise frequency must be one of " + EXERCISE_FREQUENCIES);
        }
        if (goal == null || !GOALS.contains(goal)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal must be one of " + GOALS);
        }
    }

    @Transactional
    public UserEntity updateWaterTarget(UserEntity user, Integer waterTargetMl) {
        user.setWaterTargetMl(waterTargetMl);
        return repository.save(user);
    }
}
