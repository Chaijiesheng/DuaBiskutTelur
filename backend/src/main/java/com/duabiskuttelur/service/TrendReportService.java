package com.duabiskuttelur.service;

import com.duabiskuttelur.model.TrendDay;
import com.duabiskuttelur.model.TrendMealRow;
import com.duabiskuttelur.model.TrendReportResponse;
import com.duabiskuttelur.model.TrendTotals;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.WaterEntity;
import com.duabiskuttelur.persistence.WaterRepository;
import com.duabiskuttelur.persistence.WeightEntity;
import com.duabiskuttelur.persistence.WeightRepository;
import com.duabiskuttelur.persistence.WorkoutSessionEntity;
import com.duabiskuttelur.persistence.WorkoutSessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the weekly and monthly trend reports.
 *
 * <p>Everything here is arithmetic over stored columns. No figure in a report
 * comes from a model, for the same reason the 1-100 grade does not: a number a
 * user will compare against last week has to be reproducible, and a provider
 * having a bad day must not be able to move it. The written summary is added
 * separately by the caller and is the only part a model touches.
 *
 * <h2>Averages divide by days logged</h2>
 * Not by days elapsed. Seven is the wrong denominator for someone who tracked
 * four days: it reports a normal week as a collapse, and the user's response is
 * to distrust the report rather than to change what they eat. Dividing by days
 * logged answers what was actually asked -- on the days I tracked, how did I do
 * -- and {@link TrendTotals#daysLogged()} travels with the number so the screen
 * can print the denominator instead of hiding it.
 *
 * <h2>Null means "not enough to say"</h2>
 * Never zero. A column added by a later migration is genuinely absent on older
 * rows, and averaging those in would report a real decline that only reflects
 * the schema's history. Each metric is averaged over the rows that actually
 * carry it, and returns null when too few do.
 */
@Service
public class TrendReportService {

    /**
     * Below this the report shows its thin-data state instead of headline
     * figures. Three days is not a trend, and presenting it as one teaches the
     * user that the numbers here are noise.
     */
    static final int MIN_DAYS_FOR_REPORT = 4;

    /**
     * A comparison against a nearly-empty previous window is worse than no
     * comparison: the delta is dominated by how much the user logged, not by
     * what changed. Below this, the report simply omits every delta.
     */
    static final int MIN_DAYS_FOR_COMPARISON = 3;

    /** Fewer meals than this and an average grade says more about sampling than about eating. */
    static final int MIN_MEALS_FOR_GRADE = 3;

    /**
     * A metric backed by a column added mid-life is only reported when most of
     * the window's meals actually carry it. Half the rows missing protein
     * produces an average that looks like a shortfall and is really an artefact
     * of the V2 migration.
     */
    private static final double MIN_COVERAGE = 0.5;

    private final MealAnalysisRepository meals;
    private final WaterRepository water;
    private final WeightRepository weights;
    private final WorkoutSessionRepository workouts;
    private final ScoringService scoring;
    private final WaterService waterService;
    private final TrendNarrator narrator;

    public TrendReportService(MealAnalysisRepository meals, WaterRepository water,
                              WeightRepository weights, WorkoutSessionRepository workouts,
                              ScoringService scoring, WaterService waterService,
                              TrendNarrator narrator) {
        this.meals = meals;
        this.water = water;
        this.weights = weights;
        this.workouts = workouts;
        this.scoring = scoring;
        this.waterService = waterService;
        this.narrator = narrator;
    }

    /** The two windows a report can cover. */
    public enum Period {
        WEEK, MONTH;

        public static Period parse(String raw) {
            if (raw == null) {
                return WEEK;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "month" -> MONTH;
                case "week", "" -> WEEK;
                default -> throw new IllegalArgumentException("Unknown period: " + raw);
            };
        }

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public TrendReportResponse report(UserEntity user, Period period, LocalDate today, String lang) {
        LocalDate from = startOf(period, today);
        // The window ends today, not at the end of the calendar period: a
        // report on the 8th covering the whole month would divide by days that
        // have not happened yet and show every average as a shortfall.
        LocalDate to = today;
        LocalDate previousFrom = startOf(period, from.minusDays(1));
        LocalDate previousTo = from.minusDays(1);

        Window current = load(user, from, to);
        Window earlier = load(user, previousFrom, previousTo);

        TrendTotals totals = totalsFor(user, current);
        TrendTotals previous = earlier.daysLogged() >= MIN_DAYS_FOR_COMPARISON
                ? totalsFor(user, earlier)
                : null;

        int budget = budgetFor(user);
        List<TrendDay> days = daysFor(current, from, to, budget);

        // Built without the paragraph first, because the narrator is handed the
        // finished report: it describes figures that are already computed, and
        // giving it anything less would let it describe something the user is
        // not looking at.
        TrendReportResponse withoutNarrative = new TrendReportResponse(
                period.tag(), from, to, (int) (to.toEpochDay() - from.toEpochDay() + 1),
                budget,
                totals.daysLogged() >= MIN_DAYS_FOR_REPORT,
                days, totals, previous,
                gradeMix(current.rows()),
                current.bestGrade(scoring), current.bestDate(zone()),
                "", "");

        TrendNarrator.Narrative narrative = narrator.narrate(withoutNarrative, lang);
        return new TrendReportResponse(
                withoutNarrative.period(), withoutNarrative.from(), withoutNarrative.to(),
                withoutNarrative.daysInWindow(), withoutNarrative.calorieBudget(),
                withoutNarrative.enoughData(), withoutNarrative.days(), withoutNarrative.totals(),
                withoutNarrative.previous(), withoutNarrative.gradeMix(),
                withoutNarrative.bestDayGrade(), withoutNarrative.bestDayDate(),
                narrative.text(), narrative.source().tag());
    }

    private static LocalDate startOf(Period period, LocalDate today) {
        // Rolling windows rather than calendar ones: "the last 7 days" is what
        // a user checking on a Wednesday means, and a calendar week would show
        // them a two-day report every Monday morning.
        return period == Period.WEEK ? today.minusDays(6) : today.minusDays(29);
    }

    private static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    private int budgetFor(UserEntity user) {
        return user.getDailyBudget() != null ? user.getDailyBudget() : 0;
    }

    /** Everything one window needs, read once. */
    private record Window(List<TrendMealRow> rows, Map<LocalDate, DayBucket> byDay,
                          List<WaterEntity> water, List<WeightEntity> weights,
                          List<WorkoutSessionEntity> workouts) {

        int daysLogged() {
            return byDay.size();
        }

        String bestGrade(ScoringService scoring) {
            return rows.stream().mapToInt(TrendMealRow::score).max()
                    .stream().mapToObj(scoring::gradeFor).findFirst().orElse(null);
        }

        LocalDate bestDate(ZoneId zone) {
            return rows.stream().max(java.util.Comparator.comparingInt(TrendMealRow::score))
                    .map(r -> LocalDate.ofInstant(r.createdAt(), zone)).orElse(null);
        }
    }

    /** One day's running totals while the rows are folded together. */
    private static final class DayBucket {
        int meals;
        double calories;
    }

    private Window load(UserEntity user, LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay(zone()).toInstant();
        Instant untilInstant = to.plusDays(1).atStartOfDay(zone()).toInstant();

        List<TrendMealRow> rows = meals.findTrendRows(user.getId(), fromInstant, untilInstant);
        Map<LocalDate, DayBucket> byDay = new LinkedHashMap<>();
        for (TrendMealRow row : rows) {
            DayBucket bucket = byDay.computeIfAbsent(
                    LocalDate.ofInstant(row.createdAt(), zone()), d -> new DayBucket());
            bucket.meals++;
            bucket.calories += row.calories();
        }
        return new Window(rows, byDay,
                water.findByUserIdAndDateBetween(user.getId(), from, to),
                weights.findByUserIdAndLoggedAtBetween(user.getId(), fromInstant, untilInstant),
                workouts.findByUserIdAndSessionDateBetweenOrderBySessionDateAsc(user.getId(), from, to));
    }

    private List<TrendDay> daysFor(Window window, LocalDate from, LocalDate to, int budget) {
        List<TrendDay> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayBucket bucket = window.byDay().get(d);
            double calories = bucket == null ? 0 : bucket.calories;
            days.add(new TrendDay(d, bucket != null, bucket == null ? 0 : bucket.meals,
                    Math.round(calories), budget > 0 && calories > budget));
        }
        return days;
    }

    private TrendTotals totalsFor(UserEntity user, Window window) {
        int daysLogged = window.daysLogged();
        List<TrendMealRow> rows = window.rows();

        double totalCalories = rows.stream().mapToDouble(TrendMealRow::calories).sum();
        Integer avgCalories = daysLogged == 0 ? null : (int) Math.round(totalCalories / daysLogged);

        Integer avgScore = rows.size() < MIN_MEALS_FOR_GRADE ? null
                : (int) Math.round(rows.stream().mapToInt(TrendMealRow::score).average().orElse(0));
        String avgGrade = avgScore == null ? null : scoring.gradeFor(avgScore);

        Integer avgProtein = averageOverDaysWhenCovered(
                rows.stream().filter(r -> r.protein() != null).mapToDouble(TrendMealRow::protein).sum(),
                rows.stream().filter(r -> r.protein() != null).count(), rows.size(), daysLogged);

        long vegRows = rows.stream().filter(r -> r.vegetableCount() != null).count();
        Integer vegetables = covered(vegRows, rows.size())
                ? rows.stream().filter(r -> r.vegetableCount() != null)
                        .mapToInt(TrendMealRow::vegetableCount).sum()
                : null;

        Integer fruitDays = covered(rows.stream().filter(r -> r.hasFruit() != null).count(), rows.size())
                ? (int) rows.stream().filter(r -> Boolean.TRUE.equals(r.hasFruit()))
                        .map(r -> LocalDate.ofInstant(r.createdAt(), zone())).distinct().count()
                : null;

        int waterTarget = waterService.targetFor(user);
        Integer avgWater = window.water().isEmpty() ? null
                : (int) Math.round(window.water().stream().mapToInt(WaterEntity::getTotalMl).average().orElse(0));
        Integer waterOnTarget = window.water().isEmpty() ? null
                : (int) window.water().stream().filter(w -> w.getTotalMl() >= waterTarget).count();

        List<WorkoutSessionEntity> done = window.workouts().stream()
                .filter(s -> "completed".equals(s.getStatus())).toList();
        Integer workoutMinutes = done.isEmpty() ? null
                : done.stream().mapToInt(s -> s.getActualMinutes() != null ? s.getActualMinutes() : s.getMinutes()).sum();

        Double weightChange = null;
        Double latestWeight = null;
        List<WeightEntity> weighIns = window.weights();
        if (!weighIns.isEmpty()) {
            latestWeight = weighIns.get(weighIns.size() - 1).getWeightKg();
            if (weighIns.size() >= 2) {
                weightChange = round1(latestWeight - weighIns.get(0).getWeightKg());
            }
        }

        return new TrendTotals(daysLogged, rows.size(), avgCalories, avgScore, avgGrade,
                avgProtein, vegetables, fruitDays, avgWater, waterOnTarget,
                done.isEmpty() ? null : done.size(), workoutMinutes, weightChange, latestWeight);
    }

    private static boolean covered(long present, int total) {
        return total > 0 && (double) present / total >= MIN_COVERAGE;
    }

    private static Integer averageOverDaysWhenCovered(double sum, long present, int total, int daysLogged) {
        if (daysLogged == 0 || !covered(present, total)) {
            return null;
        }
        return (int) Math.round(sum / daysLogged);
    }

    /** Best to worst, matching {@link ScoringService#gradeFor}. */
    private static final List<String> GRADE_ORDER = List.of("A+", "A", "B", "C", "D");

    /**
     * How many meals landed on each grade.
     *
     * <p>Ordered best-to-worst and always five entries, including the zeroes.
     * Sorting by count instead would redraw the axis every time the data moved,
     * and dropping empty grades would quietly rescale the chart -- a user with
     * no D meals would see their C bar sitting where D used to be and read it
     * as a decline.
     *
     * <p>Derived from the score rather than the stored {@code grade} column so
     * the mix always reflects the current band configuration; the two can
     * disagree for rows written before a threshold was retuned.
     */
    private Map<String, Integer> gradeMix(List<TrendMealRow> rows) {
        Map<String, Integer> mix = new LinkedHashMap<>();
        for (String grade : GRADE_ORDER) {
            mix.put(grade, 0);
        }
        for (TrendMealRow row : rows) {
            mix.merge(scoring.gradeFor(row.score()), 1, Integer::sum);
        }
        return mix;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
