package com.duabiskuttelur.client;

import com.duabiskuttelur.config.AppMetrics;
import com.duabiskuttelur.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the USDA FoodData Central search API. Returns nutrients per 100g
 * for the closest match to a search term. Retries transient failures.
 */
@Component
public class UsdaClient {

    private static final Logger log = LoggerFactory.getLogger(UsdaClient.class);

    /** Nutrients per 100g of the matched food. Sodium in mg, everything else in g/kcal. */
    public record NutrientsPer100g(
            String matchedDescription,
            double calories,
            double protein,
            double carbs,
            double fat,
            double fiber,
            double sugar,
            double sodium
    ) {
    }

    /**
     * Access-ordered LRU, capped so a long-running instance can't accumulate
     * unbounded entries from novel search terms. Wrapped for thread safety
     * because menu scans resolve their dishes concurrently.
     */
    private static final int CACHE_MAX_ENTRIES = 500;

    private final Map<String, Optional<NutrientsPer100g>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<NutrientsPer100g>> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    private final AppProperties props;
    private final RestClient restClient;

    private final MeterRegistry meters;

    public UsdaClient(AppProperties props, MeterRegistry meters) {
        this.props = props;
        this.meters = meters;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getUsdaReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(props.getUsdaBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Search FoodData Central for the term and return per-100g nutrients of the
     * top match. Empty when nothing matched or all retries failed.
     *
     * <p>Results are memoized per search term. Menus repeat heavily — both
     * within one scan and across scans of similar restaurants — and a dish's
     * reference nutrition doesn't change, so the second lookup of "teh tarik"
     * costs nothing. Genuine "no match" answers are cached too (they cost a
     * full round trip to discover, and a term USDA doesn't know today it won't
     * know in five minutes either); transient failures are not.
     */
    public Optional<NutrientsPer100g> lookup(String searchTerm) {
        if (!props.hasUsdaKey()) {
            return Optional.empty();
        }
        String cacheKey = searchTerm == null ? "" : searchTerm.trim().toLowerCase(Locale.ROOT);
        Optional<NutrientsPer100g> cached = cache.get(cacheKey);
        if (cached != null) {
            // Counted, never timed — see AppMetrics.OUTCOME_CACHE_HIT.
            meters.counter(AppMetrics.USDA_LOOKUP + ".cache",
                    AppMetrics.TAG_OUTCOME, AppMetrics.OUTCOME_CACHE_HIT).increment();
            return cached;
        }
        Timer.Sample sample = Timer.start(meters);
        Outcome outcome = search(searchTerm);
        sample.stop(Timer.builder(AppMetrics.USDA_LOOKUP)
                .description("One FoodData Central lookup")
                .tag(AppMetrics.TAG_OUTCOME, outcome.label())
                .register(meters));
        if (outcome.cacheable()) {
            cache.put(cacheKey, outcome.value());
        }        return outcome.value();
    }

    /**
     * The lookup itself, reporting <em>why</em> it came back empty as well as
     * that it did.
     *
     * <p>The three empty cases are worth separating because they mean completely
     * different things and all three end as "estimated" nutrition: a genuine
     * miss is expected for an unusual local dish, an {@code error} means USDA was
     * unreachable, and a {@code client_error} means the key or the query shape is
     * wrong — which silently downgrades <em>every</em> food to a model estimate
     * and, without this tag, looks identical to the app simply not knowing much
     * about Malaysian food.
     */
    private record Outcome(Optional<NutrientsPer100g> value, String label) {
        static Outcome of(NutrientsPer100g nutrients) {
            return new Outcome(Optional.of(nutrients), AppMetrics.OUTCOME_HIT);
        }

        static Outcome empty(String label) {
            return new Outcome(Optional.empty(), label);
        }

        /**
         * Whether this result may be memoised. Derived from the label rather
         * than stored beside it, because they are the same fact: a hit and a
         * genuine miss are answers and stay true, while an error is a statement
         * about the network at one moment. Caching the latter would let one
         * blip pin a food to "estimated" for the life of the process.
         */
        boolean cacheable() {
            return !AppMetrics.OUTCOME_ERROR.equals(label)
                    && !AppMetrics.OUTCOME_CLIENT_ERROR.equals(label);
        }
    }

    private Outcome search(String searchTerm) {        int attempts = props.getUsdaRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                JsonNode response = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/fdc/v1/foods/search")
                                .queryParam("api_key", props.getUsdaApiKey())
                                .queryParam("query", searchTerm)
                                .queryParam("pageSize", 1)
                                // Emitted as three separate dataType params, which is
                                // what FDC wants — verified against the live API:
                                // repeated params return 841 hits for "chocolate milk",
                                // all Survey (FNDDS)/SR Legacy, while dropping the
                                // filter returns 150k hits that are entirely Branded.
                                // The docs describe a comma-separated list, and that
                                // form works too — but only when the spaces inside
                                // "Survey (FNDDS)" and "SR Legacy" are percent-encoded;
                                // comma-joined with '+' for spaces is a 400. Since a
                                // 400 here is swallowed below and silently downgrades
                                // every food to a model estimate, this stays as it is.
                                // UsdaClientTest pins the emitted shape.
                                .queryParam("dataType", "Survey (FNDDS)", "SR Legacy", "Foundation")
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    return Outcome.empty(AppMetrics.OUTCOME_MISS);
                }
                JsonNode foods = response.path("foods");
                if (!foods.isArray() || foods.isEmpty()) {
                    return Outcome.empty(AppMetrics.OUTCOME_MISS);
                }
                return Outcome.of(parseFood(foods.get(0)));
            } catch (HttpClientErrorException e) {
                // A rejected request (bad key, malformed query) fails the same
                // way every time — retrying just spends the caller's latency
                // budget to be told no again.
                log.warn("USDA rejected the lookup for '{}': {}", searchTerm, e.getStatusCode());
                return Outcome.empty(AppMetrics.OUTCOME_CLIENT_ERROR);            } catch (Exception e) {
                log.warn("USDA lookup failed for '{}' (attempt {}/{}): {}", searchTerm, attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    sleepBriefly(attempt);
                }
            }
        }
        return Outcome.empty(AppMetrics.OUTCOME_ERROR);    }

    private NutrientsPer100g parseFood(JsonNode food) {
        double calories = 0, protein = 0, carbs = 0, fat = 0, fiber = 0, sugar = 0, sodium = 0;
        for (JsonNode nutrient : food.path("foodNutrients")) {
            int id = nutrient.path("nutrientId").asInt(nutrient.path("nutrient").path("id").asInt());
            double value = nutrient.path("value").asDouble(nutrient.path("amount").asDouble(0));
            switch (id) {
                case 1008 -> calories = value;   // Energy (kcal)
                case 1003 -> protein = value;    // Protein (g)
                case 1005 -> carbs = value;      // Carbohydrate (g)
                case 1004 -> fat = value;        // Total fat (g)
                case 1079 -> fiber = value;      // Fiber (g)
                case 2000 -> sugar = value;      // Total sugars (g)
                case 1093 -> sodium = value;     // Sodium (mg)
                default -> { /* not tracked */ }
            }
        }
        return new NutrientsPer100g(
                food.path("description").asText(""),
                calories, protein, carbs, fat, fiber, sugar, sodium);
    }

    /** Immediate re-fire helps nothing if the cause was load; a short pause costs little and might. */
    private static void sleepBriefly(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
