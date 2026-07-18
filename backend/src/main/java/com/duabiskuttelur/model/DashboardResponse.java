package com.duabiskuttelur.model;

/** Today's at-a-glance summary, shown right after login. hasData=false when no meals were logged yet today. */
public record DashboardResponse(
        boolean hasData,
        double totalCalories,
        int calorieTarget,
        double totalProtein,
        int proteinTarget,
        int mealCount,
        String averageGrade
) {
}
