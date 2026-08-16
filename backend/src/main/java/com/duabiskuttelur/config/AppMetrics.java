package com.duabiskuttelur.config;

/**
 * Every metric name and tag key the app publishes, in one place.
 *
 * <p>Before this, the only signal from the AI layer was one INFO line —
 * {@code "Using model fallback estimate for '{}'"} — as unaggregated text. That
 * cannot answer any of the questions that actually matter when something is
 * wrong: what fraction of analyses fell back to a model estimate instead of
 * USDA, what the vision p95 is, how often a response arrives truncated, how many
 * 429s a day, how often the bulkhead sheds a caller.
 *
 * <p>Constants rather than string literals at each call site because a typo in a
 * metric name is invisible — no compiler error, no test failure, just a series
 * that silently stops at the moment you need it.
 *
 * <p><b>Tag cardinality.</b> Every tag value here comes from a closed set
 * (configured model names, fixed outcome words). Nothing is tagged with a dish
 * name, a user id, or an API key: each distinct combination is a separate time
 * series held in memory for the life of the process, so an unbounded tag is a
 * slow memory leak that also makes the dashboards useless. Which key hit a 429
 * stays in the logs, where it is already masked.
 */
public final class AppMetrics {

    /** One HTTP call to the provider, timed. Tags: {@link #TAG_MODEL}, {@link #TAG_TYPE}, {@link #TAG_OUTCOME}. */
    public static final String GEMINI_CALL = "gemini.call";

    /**
     * The whole retry chain across models and keys — what the user actually
     * waits for, and the number to alert on. Deliberately separate from
     * {@link #GEMINI_CALL}: one slow chain can be twelve fast failed calls, and
     * a p95 over the individual calls would hide exactly that.
     */
    public static final String GEMINI_CHAIN = "gemini.chain";

    /** Free bulkhead slots. A gauge, not a counter: the useful question is "how close to zero right now". */
    public static final String GEMINI_SLOTS_AVAILABLE = "gemini.slots.available";

    /** One FoodData Central lookup, timed. Tags: {@link #TAG_OUTCOME}. */
    public static final String USDA_LOOKUP = "usda.lookup";

    /**
     * A USDA match that came back and was thrown away as not credible for the
     * dish. Tags: {@link #TAG_RULE}, naming which check caught it.
     *
     * <p>This is the number that governs how much work everything downstream is
     * asked to do: a rejected match falls to the curated dish table, or past it
     * to the model's own estimate. Production was rejecting 10-15 dishes out of
     * 30 on a menu scan with no way to ask which rule was responsible — the
     * reason existed only as log text, which is exactly the situation the rest
     * of this class was written to end.
     *
     * <p>No companion "accepted" counter: accepted matches already increment
     * {@link #NUTRITION_SOURCE} with {@code source=usda}, so the rejection rate
     * is a ratio of the two.
     */
    public static final String USDA_MATCH_REJECTED = "usda.match.rejected";

    /**
     * Curated local-database lookups. Tags: {@link #TAG_OUTCOME} (hit|miss).
     * The hit rate <em>is</em> the coverage of the seeded data, which is the
     * number that says whether curating more dishes is still worth doing.
     */
    public static final String LOCAL_FOOD_LOOKUP = "local.food.lookup";

    /** Nutrition cache outcome. Tags: {@link #TAG_RESULT}. */
    public static final String NUTRITION_CACHE = "nutrition.cache";

    /**
     * Where a dish's numbers came from. Tags: {@link #TAG_SOURCE} (usda|estimated).
     * This is the review's first question — what fraction of analyses fall back
     * to a model estimate — answerable as a ratio of these two.
     */
    public static final String NUTRITION_SOURCE = "nutrition.source";

    /** End-to-end analysis, vision through feedback. Tags: {@link #TAG_OUTCOME}. */
    public static final String ANALYSIS_DURATION = "analysis.duration";

    public static final String TAG_MODEL = "model";
    /** vision | menu | feedback */
    public static final String TAG_TYPE = "type";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_RESULT = "result";
    public static final String TAG_SOURCE = "source";
    /** Which validation rule rejected a USDA match — see NutritionValidator.Rule. */
    public static final String TAG_RULE = "rule";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_RATE_LIMITED = "rate_limited";
    public static final String OUTCOME_SERVER_ERROR = "server_error";
    public static final String OUTCOME_TIMEOUT = "timeout";
    public static final String OUTCOME_TRUNCATED = "truncated";
    public static final String OUTCOME_CLIENT_ERROR = "client_error";
    /** The retry chain gave up: every model and key exhausted. */
    public static final String OUTCOME_BUSY = "busy";
    /** The wall-clock budget ran out mid-chain. */
    public static final String OUTCOME_BUDGET_EXHAUSTED = "budget_exhausted";
    /** Turned away by the bulkhead without reaching the provider at all. */
    public static final String OUTCOME_SHED = "shed";
    public static final String OUTCOME_ERROR = "error";
    /** A lookup that matched a food. */
    public static final String OUTCOME_HIT = "hit";
    /**
     * A USDA lookup answered from the in-process memo rather than the network.
     * Counted separately from {@link #OUTCOME_HIT}, and deliberately not timed:
     * a map hit is sub-microsecond, so folding it into {@link #USDA_LOOKUP}
     * would drag the latency distribution toward zero and leave it describing
     * neither the cache nor the network.
     */
    public static final String OUTCOME_CACHE_HIT = "cache_hit";
    /** A lookup that ran fine and matched nothing - expected for unusual local dishes. */
    public static final String OUTCOME_MISS = "miss";
    public static final String OUTCOME_NO_FOOD = "no_food";

    private AppMetrics() {
    }
}
