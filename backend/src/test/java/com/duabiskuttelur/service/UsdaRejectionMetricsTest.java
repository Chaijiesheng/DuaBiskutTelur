package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.UsdaClient.NutrientsPer100g;
import com.duabiskuttelur.config.AppMetrics;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * How often USDA matches are thrown away, and by which rule.
 *
 * <p>This number governs the whole resolution path: every rejection falls to the
 * curated dish table, or past it to the model's own estimate, so the rejection
 * rate decides how much of a menu is answered by a database and how much by a
 * guess. Production was rejecting 10-15 dishes out of 30 on a single menu scan
 * and the only record was an INFO line per dish — enough to see that it happened
 * once, useless for asking which rule was responsible or whether the rate was
 * climbing.
 *
 * <p>The failure mode of instrumentation is silence: a counter that never fires
 * looks exactly like a system that never has the problem. So these tests assert
 * the counter's tag, not merely that resolution fell through.
 */
class UsdaRejectionMetricsTest {

    private final UsdaClient usdaClient = Mockito.mock(UsdaClient.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();

    /** No curated rows and no cache: every rejection falls straight to the model. */
    private AnalysisService service() {
        AppProperties props = new AppProperties();
        props.setNutritionCacheEnabled(false);
        props.setLocalDishTableEnabled(false);
        NutritionCacheService cache = new NutritionCacheService(
                Mockito.mock(NutritionCacheRepository.class), props, new SimpleMeterRegistry());
        return new AnalysisService(null, usdaClient, cache,
                Mockito.mock(LocalFoodService.class), new LocalDishTable(List.of()),
                null, null, null, null, null, props, new ObjectMapper(), meters);
    }

    private static IdentifiedFood nasiLemak() {
        return new IdentifiedFood("Nasi lemak", "1 plate", 300, 240, 360, "coconut rice",
                150, 3, 25, 5, 1, 1, 250, "grain", "steamed", 0.9, null);
    }

    private double rejections(NutritionValidator.Rule rule) {
        return meters.find(AppMetrics.USDA_MATCH_REJECTED)
                .tag(AppMetrics.TAG_RULE, rule.tag())
                .counter() == null ? 0
                : meters.find(AppMetrics.USDA_MATCH_REJECTED)
                        .tag(AppMetrics.TAG_RULE, rule.tag()).counter().count();
    }

    /**
     * Every rule's series summed. Since the counters are registered at startup,
     * "nothing was rejected" is a total of zero — not an absent series, which is
     * what it used to be and is no longer a safe thing to assert.
     */
    private double totalRejections() {
        return meters.find(AppMetrics.USDA_MATCH_REJECTED).counters()
                .stream().mapToDouble(c -> c.count()).sum();
    }

    private double accepted() {
        var counter = meters.find(AppMetrics.NUTRITION_SOURCE).tag(AppMetrics.TAG_SOURCE, "usda").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aRejectedMatchIsCountedUnderTheRuleThatCaughtIt() {
        // Canned coconut milk for nasi lemak: internally consistent, wrong food,
        // and 3g of carbohydrate for a dish named as a rice.
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.of(
                new NutrientsPer100g("Coconut milk, canned", 230, 2.3, 3.0, 24.0, 0.0, 3.0, 15)));

        service().resolveNutrition(nasiLemak());

        assertEquals(1, rejections(NutritionValidator.Rule.STARCH_WITHOUT_CARBS));
    }

    /**
     * Different faults have to land on different series, or the metric answers
     * "something was rejected" — which is what the log line already said.
     */
    @Test
    void differentRulesAreCountedSeparately() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.of(
                new NutrientsPer100g("Protein isolate", 380, 78, 5, 4, 1, 1, 100)));
        service().resolveNutrition(nasiLemak());

        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.of(
                new NutrientsPer100g("Something broken", 1500, 10, 10, 10, 1, 1, 100)));
        service().resolveNutrition(nasiLemak());

        assertEquals(1, rejections(NutritionValidator.Rule.PROTEIN_DENSITY));
        assertEquals(1, rejections(NutritionValidator.Rule.ENERGY_DENSITY));
        assertEquals(0, rejections(NutritionValidator.Rule.INCOMPLETE_ROW));
    }

    /**
     * The rate is rejections over rejections-plus-accepted, which is why there is
     * no separate "accepted" counter — {@code nutrition.source{source=usda}}
     * already is one. That only holds if an accepted match increments exactly one
     * of them, so both halves are asserted together.
     */
    @Test
    void anAcceptedMatchCountsAsASourceAndNotAsARejection() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.of(
                new NutrientsPer100g("Rice, coconut", 160, 3, 26, 5, 1, 1, 200)));

        service().resolveNutrition(nasiLemak());

        assertEquals(1, accepted());
        assertEquals(0, totalRejections(), "an accepted match must not count as a rejection");
    }

    /**
     * A miss is not a rejection. USDA returning nothing is the ordinary case for
     * a local dish and says nothing about match quality; counting it here would
     * inflate the rate with the one outcome that has no faulty row behind it.
     */
    @Test
    void aLookupThatMatchedNothingIsNotCountedAsARejection() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());

        service().resolveNutrition(nasiLemak());

        assertEquals(0, totalRejections(), "a miss is not a rejected match");
    }

    /**
     * Every rule has a series from startup, reading zero, before a single dish
     * has been resolved.
     *
     * <p>Micrometer registers a counter when it is first incremented, so without
     * this an unfired rule is simply absent from the scrape — and "no rule has
     * rejected anything" then looks exactly like "the counter was never wired".
     * That ambiguity is the whole reason this metric exists, so the metric must
     * not reproduce it. Deployed lazily once: {@code usda_match_rejected_total}
     * was missing from {@code /actuator/prometheus} entirely until the first
     * rejection, while {@code nutrition_cache_total} sat there at 0.0 because
     * NutritionCacheService registers eagerly.
     */
    @Test
    void everyRuleHasASeriesFromStartupReadingZero() {
        service();

        for (NutritionValidator.Rule rule : NutritionValidator.Rule.values()) {
            var counter = meters.find(AppMetrics.USDA_MATCH_REJECTED)
                    .tag(AppMetrics.TAG_RULE, rule.tag()).counter();
            assertTrue(counter != null, "no series registered for " + rule);
            assertEquals(0, counter.count(), rule + " should start at zero");
        }
        assertEquals(NutritionValidator.Rule.values().length,
                meters.find(AppMetrics.USDA_MATCH_REJECTED).counters().size());
    }

    /**
     * The other half of the ratio. Rejection rate is rejections over
     * rejections-plus-{@code source=usda}, so registering only the rules would
     * leave the denominator missing until the first lookup succeeded, and the
     * rate undefined exactly when someone is trying to read it.
     */
    @Test
    void everyNutritionSourceAlsoHasASeriesFromStartup() {
        service();

        for (String source : List.of("usda", "local", "estimated")) {
            var counter = meters.find(AppMetrics.NUTRITION_SOURCE)
                    .tag(AppMetrics.TAG_SOURCE, source).counter();
            assertTrue(counter != null, "no series registered for source=" + source);
            assertEquals(0, counter.count(), source + " should start at zero");
        }
    }

    /**
     * Every tag value comes from the enum, so the series can never be tagged with
     * a dish name or an interpolated number — each distinct tag is a time series
     * held for the life of the process, and an unbounded one is a slow leak that
     * also makes the dashboard unreadable.
     */
    @Test
    void everyRuleTagIsLowercaseAndBounded() {
        for (NutritionValidator.Rule rule : NutritionValidator.Rule.values()) {
            assertEquals(rule.tag(), rule.tag().toLowerCase(java.util.Locale.ROOT));
            assertTrue(rule.tag().matches("[a-z_]+"), rule + " -> " + rule.tag());
        }
        assertEquals(15, NutritionValidator.Rule.values().length,
                "a new rule needs a tag and a test; see NutritionValidatorTest");
    }
}
