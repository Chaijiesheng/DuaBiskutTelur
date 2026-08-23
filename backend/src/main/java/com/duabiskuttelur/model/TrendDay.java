package com.duabiskuttelur.model;

import java.time.LocalDate;

/**
 * One calendar day in the reporting window, present even when nothing was
 * logged.
 *
 * <p>Every day appears so the chart has a column for it: a week with a gap on
 * Saturday has to render Saturday as an empty column, not shuffle Sunday left.
 * {@code logged} is what separates "ate nothing that was tracked" from "zero
 * calories", which the averages then depend on.
 */
public record TrendDay(
        LocalDate date,
        boolean logged,
        int mealCount,
        double calories,
        boolean overBudget
) {
}
