package com.duabiskuttelur.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Covers the daily-budget validation guarding PUT /api/budget. */
class UserServiceTest {

    @Test
    void validateBudgetRejectsOutOfRangeValues() {
        assertThrows(ResponseStatusException.class, () -> UserService.validateBudget(500));
        assertThrows(ResponseStatusException.class, () -> UserService.validateBudget(10_000));
    }

    @Test
    void validateBudgetRejectsNull() {
        assertThrows(ResponseStatusException.class, () -> UserService.validateBudget(null));
    }

    @Test
    void validateBudgetAcceptsValuesWithinRange() {
        UserService.validateBudget(1000);
        UserService.validateBudget(5000);
        UserService.validateBudget(2000);
    }

    @Test
    void validateProfileAcceptsTheFormBoundaries() {
        UserService.validateProfile(10, "male", 30.0, 100.0, 0, "not_workout", "weight_loss");
        UserService.validateProfile(100, "female", 250.0, 230.0, 60_000, "daily_workout", "maintenance");
        // steps is the one optional field
        UserService.validateProfile(30, "male", 70.0, 170.0, null, "normal_workout", "muscle_gain");
    }

    @Test
    void validateProfileRejectsOutOfRangeNumbers() {
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(-5, "male", 70.0, 170.0, null, "normal_workout", "maintenance"));
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(30, "male", 99_999.0, 170.0, null, "normal_workout", "maintenance"));
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(30, "male", 70.0, 20.0, null, "normal_workout", "maintenance"));
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(30, "male", 70.0, 170.0, 999_999, "normal_workout", "maintenance"));
    }

    @Test
    void validateProfileRejectsUnknownEnumsAndNulls() {
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(30, "hacker", 70.0, 170.0, null, "normal_workout", "maintenance"));
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(30, "male", 70.0, 170.0, null, "sometimes", "maintenance"));
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(30, "male", 70.0, 170.0, null, "normal_workout", "get_swole"));
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(null, "male", 70.0, 170.0, null, "normal_workout", "maintenance"));
        assertThrows(ResponseStatusException.class,
                () -> UserService.validateProfile(30, "male", null, 170.0, null, "normal_workout", "maintenance"));
    }
}
