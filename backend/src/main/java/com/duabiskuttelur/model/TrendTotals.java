package com.duabiskuttelur.model;

/**
 * The headline figures for one window, and the shape a period-over-period
 * comparison is made of.
 *
 * <p>Nulls here always mean "not enough to say", never zero. A user with one
 * weigh-in has no weight change; a user whose meals predate the protein column
 * has no protein average. Sending null lets the UI hide the tile instead of
 * printing a number that reads as a measurement but is an artefact.
 *
 * <p>Averages are per <em>day logged</em>, not per day elapsed. Dividing by
 * seven charges a user for the days they never opened the app, which turns an
 * ordinary week into an apparent collapse; {@code daysLogged} travels alongside
 * so the screen can state the denominator rather than hide it.
 */
public record TrendTotals(
        int daysLogged,
        int mealCount,
        Integer avgDailyCalories,
        Integer avgScore,
        String avgGrade,
        Integer avgDailyProtein,
        Integer vegetableServings,
        Integer fruitDays,
        Integer avgDailyWaterMl,
        Integer waterDaysOnTarget,
        Integer workoutsDone,
        Integer workoutMinutes,
        Double weightChangeKg,
        Double latestWeightKg
) {
}
