package com.duabiskuttelur.model;

import java.util.List;

public record AchievementsResponse(int totalMealsLogged, int currentStreakDays, List<Badge> badges) {
}
