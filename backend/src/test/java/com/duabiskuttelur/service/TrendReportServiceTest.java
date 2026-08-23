package com.duabiskuttelur.service;

import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.TrendDay;
import com.duabiskuttelur.model.TrendMealRow;
import com.duabiskuttelur.model.TrendReportResponse;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.WaterEntity;
import com.duabiskuttelur.persistence.WaterRepository;
import com.duabiskuttelur.persistence.WeightEntity;
import com.duabiskuttelur.persistence.WeightRepository;
import com.duabiskuttelur.persistence.WorkoutSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The arithmetic behind the weekly and monthly reports.
 *
 * <p>Most of these guard a judgement rather than a formula. A report is read as
 * a verdict on the user's week, so the ways it can quietly lie -- dividing by
 * days they never opened the app, averaging a column that half their rows
 * predate, comparing against a window with nothing in it -- matter more than
 * the sums, which are trivial.
 */
class TrendReportServiceTest {

    private static final long USER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private MealAnalysisRepository meals;
    private WaterRepository water;
    private WeightRepository weights;
    private WorkoutSessionRepository workouts;
    private TrendReportService service;

    @BeforeEach
    void setUp() {
        meals = mock(MealAnalysisRepository.class);
        water = mock(WaterRepository.class);
        weights = mock(WeightRepository.class);
        workouts = mock(WorkoutSessionRepository.class);
        when(water.findByUserIdAndDateBetween(anyLong(), any(), any())).thenReturn(List.of());
        when(weights.findByUserIdAndLoggedAtBetween(anyLong(), any(), any())).thenReturn(List.of());
        when(workouts.findByUserIdAndSessionDateBetweenOrderBySessionDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(meals.findTrendRows(anyLong(), any(), any())).thenReturn(List.of());
        // A narrator whose client always misses, so these tests exercise the
        // deterministic report and the rule-based paragraph, never the network.
        service = new TrendReportService(meals, water, weights, workouts,
                new ScoringService(new ScoringProperties()), new WaterService(water),
                new TrendNarrator((context, language) -> null));
    }

    private static UserEntity user() {
        UserEntity u = new UserEntity();
        u.setId(USER_ID);
        u.setDailyBudget(2000);
        return u;
    }

    /** A meal on a given day, at midday so it cannot drift across a boundary. */
    private static TrendMealRow meal(LocalDate day, int score, double calories,
                                     Double protein, Integer veg, Boolean fruit) {
        return new TrendMealRow(day.atTime(12, 0).atZone(ZONE).toInstant(),
                score, calories, protein, veg, fruit);
    }

    private void currentWindow(List<TrendMealRow> rows) {
        // The report reads the current window first and the previous one second.
        when(meals.findTrendRows(anyLong(), any(), any())).thenReturn(rows, List.of());
    }

    private TrendReportResponse week() {
        return service.report(user(), TrendReportService.Period.WEEK, TODAY, "en");
    }

    /**
     * The denominator decision, and the one most likely to be "corrected" by
     * someone who has not read why. Four logged days at 2,000 kcal is a 2,000
     * kcal average, not 1,143 -- dividing by seven charges the user for the
     * three days they did not open the app and reports an ordinary week as a
     * collapse.
     */
    @Test
    void averagesDivideByDaysLoggedNotDaysElapsed() {
        List<TrendMealRow> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(meal(TODAY.minusDays(i), 70, 2000, null, null, null));
        }
        currentWindow(rows);

        TrendReportResponse report = week();

        assertEquals(4, report.totals().daysLogged());
        assertEquals(2000, report.totals().avgDailyCalories(),
                "averaged over seven days instead of the four that were logged");
    }

    /** Every day in the window gets a column, logged or not, so the chart cannot shuffle. */
    @Test
    void reportsEveryDayInTheWindowIncludingEmptyOnes() {
        currentWindow(List.of(meal(TODAY, 70, 1500, null, null, null)));

        TrendReportResponse report = week();

        assertEquals(7, report.days().size());
        assertEquals(TODAY.minusDays(6), report.days().get(0).date());
        assertEquals(TODAY, report.days().get(6).date());
        assertTrue(report.days().get(6).logged());
        assertFalse(report.days().get(0).logged());
        assertEquals(0, report.days().get(0).calories());
    }

    @Test
    void flagsOnlyTheDaysThatWentOverBudget() {
        currentWindow(List.of(
                meal(TODAY.minusDays(1), 70, 1800, null, null, null),
                meal(TODAY, 70, 2400, null, null, null)));

        List<TrendDay> days = week().days();

        assertFalse(days.get(5).overBudget(), "1800 against a 2000 budget is not over");
        assertTrue(days.get(6).overBudget(), "2400 against a 2000 budget is over");
    }

    /**
     * Three days is not a trend. Showing headline figures for it teaches the
     * user that the numbers on this screen are noise.
     */
    @Test
    void withholdsTheReportUntilThereAreEnoughDays() {
        List<TrendMealRow> three = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            three.add(meal(TODAY.minusDays(i), 70, 1800, null, null, null));
        }
        currentWindow(three);
        assertFalse(week().enoughData(), "three logged days was presented as a trend");

        three.add(meal(TODAY.minusDays(3), 70, 1800, null, null, null));
        currentWindow(three);
        assertTrue(week().enoughData());
    }

    /**
     * A delta against a nearly-empty previous week measures how much the user
     * logged, not what changed. The report omits it rather than printing it.
     */
    @Test
    void omitsTheComparisonWhenThePreviousWindowIsTooThin() {
        List<TrendMealRow> current = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            current.add(meal(TODAY.minusDays(i), 70, 1800, null, null, null));
        }
        when(meals.findTrendRows(anyLong(), any(), any())).thenReturn(
                current,
                List.of(meal(TODAY.minusDays(8), 70, 3000, null, null, null)));

        assertNull(week().previous(), "compared against a previous week holding one day");
    }

    @Test
    void includesTheComparisonWhenThePreviousWindowIsSubstantial() {
        List<TrendMealRow> current = new ArrayList<>();
        List<TrendMealRow> earlier = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            current.add(meal(TODAY.minusDays(i), 70, 1800, null, null, null));
            earlier.add(meal(TODAY.minusDays(7 + i), 60, 2200, null, null, null));
        }
        when(meals.findTrendRows(anyLong(), any(), any())).thenReturn(current, earlier);

        TrendReportResponse report = week();

        assertNotNull(report.previous());
        assertEquals(2200, report.previous().avgDailyCalories());
        assertEquals(1800, report.totals().avgDailyCalories());
    }

    /**
     * protein, vegetable_count and has_fruit were all added after launch, so
     * older rows genuinely have none. Averaging those in reports a decline that
     * is really the shape of the migration history.
     */
    @Test
    void declinesToAverageAColumnMostOfTheWindowPredates() {
        List<TrendMealRow> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(meal(TODAY.minusDays(i), 70, 1800, null, null, null));
        }
        rows.add(meal(TODAY, 70, 600, 40.0, 2, true));
        currentWindow(rows);

        var totals = week().totals();

        assertNull(totals.avgDailyProtein(), "averaged protein over rows that predate the column");
        assertNull(totals.vegetableServings());
        assertNull(totals.fruitDays());
    }

    @Test
    void reportsHabitColumnsWhenTheWindowActuallyCarriesThem() {
        List<TrendMealRow> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(meal(TODAY.minusDays(i), 70, 1000, 40.0, 2, i < 2));
        }
        currentWindow(rows);

        var totals = week().totals();

        assertEquals(40, totals.avgDailyProtein(), "160g over 4 logged days");
        assertEquals(8, totals.vegetableServings());
        assertEquals(2, totals.fruitDays(), "fruit on two distinct days");
    }

    /** An average grade over one meal is a description of that meal, not of a week. */
    @Test
    void withholdsAnAverageGradeUntilThereAreEnoughMeals() {
        currentWindow(List.of(meal(TODAY, 95, 700, null, null, null)));
        assertNull(week().totals().avgGrade());

        currentWindow(List.of(
                meal(TODAY.minusDays(2), 95, 700, null, null, null),
                meal(TODAY.minusDays(1), 95, 700, null, null, null),
                meal(TODAY, 95, 700, null, null, null)));
        assertNotNull(week().totals().avgGrade());
    }

    /**
     * All five grades always, best to worst. Dropping the empty ones would
     * rescale the axis as the data moved, so a user with no D meals would see
     * their C bar sitting where D used to be and read it as a decline.
     */
    @Test
    void alwaysReportsAllFiveGradesInOrder() {
        currentWindow(List.of(
                meal(TODAY, 95, 700, null, null, null),
                meal(TODAY, 95, 700, null, null, null)));

        var mix = week().gradeMix();

        assertEquals(List.of("A+", "A", "B", "C", "D"), List.copyOf(mix.keySet()));
        assertEquals(0, mix.get("D"), "an unused grade must still be present, at zero");
        assertEquals(2, mix.values().stream().mapToInt(Integer::intValue).sum());
    }

    /** One weigh-in is a weight, not a change. */
    @Test
    void needsTwoWeighInsBeforeReportingAChange() {
        currentWindow(List.of(meal(TODAY, 70, 1800, null, null, null)));
        when(weights.findByUserIdAndLoggedAtBetween(anyLong(), any(), any()))
                .thenReturn(List.of(weight(72.0, TODAY)));
        assertNull(week().totals().weightChangeKg());
        assertEquals(72.0, week().totals().latestWeightKg());

        when(weights.findByUserIdAndLoggedAtBetween(anyLong(), any(), any()))
                .thenReturn(List.of(weight(72.0, TODAY.minusDays(5)), weight(71.4, TODAY)));
        assertEquals(-0.6, week().totals().weightChangeKg());
    }

    @Test
    void countsOnlyWaterDaysThatReachedTheTarget() {
        currentWindow(List.of(meal(TODAY, 70, 1800, null, null, null)));
        when(water.findByUserIdAndDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(waterDay(2600), waterDay(1200), waterDay(2000)));

        var totals = week().totals();

        assertEquals(1933, totals.avgDailyWaterMl());
        assertEquals(2, totals.waterDaysOnTarget(), "default target is 2000ml");
    }

    /** The month window is thirty days ending today, not a calendar month. */
    @Test
    void theMonthWindowIsThirtyRollingDays() {
        currentWindow(List.of(meal(TODAY, 70, 1800, null, null, null)));

        TrendReportResponse report = service.report(user(), TrendReportService.Period.MONTH, TODAY, "en");

        assertEquals(30, report.days().size());
        assertEquals(TODAY.minusDays(29), report.from());
        assertEquals(TODAY, report.to());
        assertEquals("month", report.period());
    }

    /**
     * The two halves meet here: the report is computed, then handed to the
     * narrator, and what comes back is carried on the response with its source
     * labelled. Without this the wiring could regress to an empty string and
     * every other test in this class would still pass.
     */
    @Test
    void carriesTheNarrativeAndItsSourceOnTheResponse() {
        List<TrendMealRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(meal(TODAY.minusDays(i), 70, 1800, null, null, null));
        }
        currentWindow(rows);

        TrendReportResponse report = week();

        assertEquals("rules", report.narrativeSource(),
                "the stub narrator always misses, so this must be the rule-based path");
        assertFalse(report.narrative().isBlank(), "the report shipped with no paragraph");
    }

    @Test
    void parsesThePeriodAndRejectsAnythingElse() {
        assertEquals(TrendReportService.Period.WEEK, TrendReportService.Period.parse(null));
        assertEquals(TrendReportService.Period.WEEK, TrendReportService.Period.parse("week"));
        assertEquals(TrendReportService.Period.MONTH, TrendReportService.Period.parse("MONTH"));
        assertThrows(IllegalArgumentException.class, () -> TrendReportService.Period.parse("decade"));
    }

    private static WeightEntity weight(double kg, LocalDate day) {
        WeightEntity w = new WeightEntity();
        w.setUserId(USER_ID);
        w.setWeightKg(kg);
        w.setLoggedAt(day.atTime(9, 0).atZone(ZONE).toInstant());
        return w;
    }

    private static WaterEntity waterDay(int ml) {
        WaterEntity w = new WaterEntity();
        w.setUserId(USER_ID);
        w.setDate(TODAY);
        w.setTotalMl(ml);
        return w;
    }
}
