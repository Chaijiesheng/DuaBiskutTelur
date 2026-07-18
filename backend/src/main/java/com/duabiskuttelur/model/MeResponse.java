package com.duabiskuttelur.model;

import com.duabiskuttelur.persistence.UserEntity;

/** Current-user payload returned to the frontend by /api/me. */
public record MeResponse(
        String email,
        String name,
        String picture,
        boolean hasProfile,
        Integer age,
        String sex,
        Double weightKg,
        Double heightCm,
        Integer steps,
        String exerciseFrequency,
        String goal,
        Integer dailyBudget
) {
    public static MeResponse from(UserEntity u) {
        return new MeResponse(
                u.getEmail(), u.getName(), u.getPictureUrl(), u.hasProfile(),
                u.getAge(), u.getSex(), u.getWeightKg(), u.getHeightCm(),
                u.getSteps(), u.getExerciseFrequency(), u.getGoal(), u.getDailyBudget());
    }
}
