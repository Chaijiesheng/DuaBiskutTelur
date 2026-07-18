package com.duabiskuttelur.model;

public record ProfileRequest(
        Integer age,
        String sex,
        Double weightKg,
        Double heightCm,
        Integer steps,
        String exerciseFrequency,
        String goal
) {
}
