package com.duabiskuttelur.service;

import com.duabiskuttelur.client.TrendNarrativeClient;
import com.duabiskuttelur.model.TrendReportResponse;
import com.duabiskuttelur.model.TrendTotals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The one paragraph in a trend report that a model writes.
 *
 * <p>Everything it is given has already been computed. The model's job is to
 * say which of the figures matters and to say it in the user's language; it
 * never produces a number, and there is no path by which it could change one --
 * the response it returns is prose and nothing else is read from it.
 *
 * <h2>Caching</h2>
 * A period's summary depends only on that period's figures, so the cache key is
 * a fingerprint of the figures themselves. Re-opening the Analysis tab five
 * times in a minute costs one call, and the entry becomes stale exactly when a
 * meal inside the window is added, edited or deleted -- because that is when
 * the fingerprint changes. No invalidation hooks to forget.
 *
 * <p>The cache is bounded and in memory. Bounded because an unbounded map keyed
 * by user is a slow leak; in memory because a narrative is derived prose rather
 * than data -- losing it on restart costs one lite-model call and nothing else,
 * which is not true of anything worth a table.
 */
@Service
public class TrendNarrator {

    private static final Logger log = LoggerFactory.getLogger(TrendNarrator.class);

    /**
     * Roughly a thousand active reports. Small enough to be invisible against
     * the container's 1 GB, large enough that a normal day never evicts an
     * entry that is about to be asked for again.
     */
    private static final int MAX_CACHED = 1_000;

    private static final Set<String> SUPPORTED_LANGS = Set.of("en", "zh", "ms");
    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "en", "English",
            "zh", "Simplified Chinese",
            "ms", "Malay (Bahasa Melayu)");

    public enum Source {
        AI, RULES;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Narrative(String text, Source source) {
    }

    private final TrendNarrativeClient client;
    private final Map<String, Narrative> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Narrative> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    public TrendNarrator(TrendNarrativeClient client) {
        this.client = client;
    }

    /**
     * A paragraph for this report, from the model when it answers and from the
     * rules when it does not.
     *
     * <p>A report too thin to have a trend gets no paragraph at all rather than
     * a hedged one: there is nothing true to say about three days, and saying it
     * anyway is what makes a report feel like filler.
     */
    public Narrative narrate(TrendReportResponse report, String lang) {
        if (!report.enoughData()) {
            return new Narrative("", Source.RULES);
        }
        String normalized = SUPPORTED_LANGS.contains(lang) ? lang : "en";
        String context = buildContext(report);
        String key = normalized + "|" + report.period() + "|" + context.hashCode();

        Narrative cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        Narrative result = generate(context, normalized, report);
        cache.put(key, result);
        return result;
    }

    private Narrative generate(String context, String lang, TrendReportResponse report) {
        try {
            String text = client.narrate(context, LANGUAGE_NAMES.get(lang));
            if (text != null && !text.isBlank()) {
                return new Narrative(text.trim(), Source.AI);
            }
            log.info("Trend narrative came back empty; using the rule-based paragraph");
        } catch (Exception e) {
            // Same posture as FeedbackService and WorkoutCoach: the report is
            // the product, the paragraph is a garnish, and a provider having a
            // bad day must not cost the user their numbers.
            log.warn("Trend narrative unavailable ({}); using the rule-based paragraph", e.toString());
        }
        return new Narrative(ruleBased(report, lang), Source.RULES);
    }

    /**
     * The figures, as labelled lines.
     *
     * <p>Plain text rather than JSON because the model is writing prose, not
     * filling a schema, and because every value here is a number this app
     * computed. Nothing user-authored and nothing model-authored reaches this
     * string, so unlike the meal-feedback prompt there is no untrusted span to
     * fence -- the fingerprint that keys the cache is taken from it for the same
     * reason it is safe to send.
     */
    static String buildContext(TrendReportResponse report) {
        TrendTotals t = report.totals();
        TrendTotals p = report.previous();
        StringJoiner lines = new StringJoiner("\n");
        lines.add("period: " + report.period());
        lines.add("days logged: " + t.daysLogged() + " of " + report.daysInWindow());
        lines.add("meals: " + t.mealCount());
        add(lines, "average daily calories", t.avgDailyCalories(), p == null ? null : p.avgDailyCalories());
        if (report.calorieBudget() > 0) {
            lines.add("daily calorie budget: " + report.calorieBudget());
            lines.add("days over budget: " + report.days().stream().filter(d -> d.overBudget()).count());
        }
        if (t.avgGrade() != null) {
            lines.add("average grade: " + t.avgGrade()
                    + (p != null && p.avgGrade() != null ? " (previously " + p.avgGrade() + ")" : ""));
        }
        add(lines, "average daily protein grams", t.avgDailyProtein(), p == null ? null : p.avgDailyProtein());
        add(lines, "vegetable servings", t.vegetableServings(), p == null ? null : p.vegetableServings());
        add(lines, "days with fruit", t.fruitDays(), null);
        add(lines, "average daily water ml", t.avgDailyWaterMl(), null);
        add(lines, "workouts completed", t.workoutsDone(), null);
        if (t.weightChangeKg() != null) {
            lines.add("weight change kg: " + t.weightChangeKg());
        }
        return lines.toString();
    }

    private static void add(StringJoiner lines, String label, Integer now, Integer before) {
        if (now == null) {
            return;
        }
        lines.add(label + ": " + now + (before == null ? "" : " (previously " + before + ")"));
    }

    /**
     * The paragraph when the model is unavailable.
     *
     * <p>Assembled from the same figures, so it is never wrong -- only plainer.
     * It leads with consistency because that is the behaviour the report is
     * trying to reinforce, and names at most one gap, since a list of everything
     * the user did badly is not something anyone opens twice.
     */
    static String ruleBased(TrendReportResponse report, String lang) {
        TrendTotals t = report.totals();
        Strings s = STRINGS.getOrDefault(lang, STRINGS.get("en"));
        StringJoiner out = new StringJoiner(" ");
        out.add(s.logged(t.daysLogged(), report.daysInWindow()));

        if (t.avgDailyCalories() != null && report.calorieBudget() > 0) {
            long under = report.days().stream().filter(d -> d.logged() && !d.overBudget()).count();
            out.add(s.budget(under, t.daysLogged()));
        }
        if (t.avgGrade() != null) {
            out.add(s.grade(t.avgGrade()));
        }
        String gap = gapFor(t, s);
        if (gap != null) {
            out.add(gap);
        }
        return out.toString();
    }

    /** The single most useful thing to mention, or nothing when there is none. */
    private static String gapFor(TrendTotals t, Strings s) {
        if (t.vegetableServings() != null && t.daysLogged() > 0
                && (double) t.vegetableServings() / t.daysLogged() < 2.0) {
            return s.vegetables();
        }
        if (t.waterDaysOnTarget() != null && t.daysLogged() > 0
                && t.waterDaysOnTarget() * 2 < t.daysLogged()) {
            return s.water();
        }
        if (t.workoutsDone() == null || t.workoutsDone() == 0) {
            return s.workouts();
        }
        return null;
    }

    private record Strings(java.util.function.BiFunction<Integer, Integer, String> loggedFn,
                           java.util.function.BiFunction<Long, Integer, String> budgetFn,
                           java.util.function.Function<String, String> gradeFn,
                           String vegetables, String water, String workouts) {

        String logged(int days, int window) {
            return loggedFn.apply(days, window);
        }

        String budget(long under, int days) {
            return budgetFn.apply(under, days);
        }

        String grade(String g) {
            return gradeFn.apply(g);
        }
    }

    private static final Map<String, Strings> STRINGS = Map.of(
            "en", new Strings(
                    (d, w) -> "You logged " + d + " of " + w + " days.",
                    (under, days) -> "You stayed within your calorie budget on " + under + " of them.",
                    g -> "Your meals averaged a " + g + ".",
                    "Vegetables are the easiest thing to add next.",
                    "Water is the gap worth closing next.",
                    "A short workout would round this out."),
            "ms", new Strings(
                    (d, w) -> "Anda merekod " + d + " daripada " + w + " hari.",
                    (under, days) -> "Anda kekal dalam bajet kalori pada " + under + " daripadanya.",
                    g -> "Purata gred hidangan anda ialah " + g + ".",
                    "Sayur ialah tambahan paling mudah selepas ini.",
                    "Pengambilan air ialah jurang yang patut ditutup.",
                    "Senaman ringkas akan melengkapkannya."),
            "zh", new Strings(
                    (d, w) -> "你在 " + w + " 天中记录了 " + d + " 天。",
                    (under, days) -> "其中 " + under + " 天保持在热量预算内。",
                    g -> "你的餐食平均等级为 " + g + "。",
                    "接下来最容易改善的是多吃蔬菜。",
                    "饮水量是下一个值得补上的缺口。",
                    "加上一次短时间运动就更完整了。"));
}
