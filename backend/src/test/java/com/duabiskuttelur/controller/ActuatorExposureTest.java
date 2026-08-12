package com.duabiskuttelur.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator is a metrics surface, and metrics surfaces have a way of becoming
 * data leaks — {@code /actuator/env} prints configuration, {@code /actuator/
 * heapdump} hands over the process memory including API keys.
 *
 * <p>The deployment keeps all of it off the public internet (the backend port is
 * not published and nginx returns 404 for {@code /actuator}), but neither of
 * those is enforced by anything in this repository's test suite, and both are
 * one careless edit away. These tests pin the layer that is: Spring Security
 * denies everything under {@code /actuator} that is not explicitly named, so
 * widening {@code management.endpoints.web.exposure.include} — which is the
 * change someone actually makes while debugging — cannot on its own put an
 * endpoint on the network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
// @SpringBootTest disables metrics export by default so tests don't push to a
// real backend, which also removes the Prometheus registry and with it the
// scrape endpoint. Without this the endpoint 404s here while working perfectly
// in production — the opposite of a useful test.
@AutoConfigureObservability
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:actuator-test;DB_CLOSE_DELAY=-1",
        // Deliberately wide open: the mistake this test exists to catch.
        "management.endpoints.web.exposure.include=*",
})
class ActuatorExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsReachableBecauseTheContainerHealthcheckNeedsIt() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void healthNeverNamesTheFailingComponent() throws Exception {
        // show-details: never. The default, restated in application.yml and
        // asserted here because the endpoint is permitAll: component details
        // include the driver, the JDBC URL and the exception message.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("components"))));
    }

    @Test
    void prometheusIsReachableForAScraperOnTheComposeNetwork() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    /**
     * The common tag from application.yml, asserted because a mistyped or
     * relocated management property does not fail — it is simply ignored, and
     * the first sign is a Prometheus instance that cannot tell this app's series
     * from anything else sharing its metric names.
     */
    @Test
    void everySeriesCarriesTheApplicationTag() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "application=\"duabiskuttelur\"")));
    }

    /**
     * The app's own meters are registered eagerly enough to be scraped before
     * anything has exercised them. A counter that only appears after its first
     * increment reads as a gap in the graph rather than a zero, which is the
     * difference between "no analyses failed" and "the metric is missing".
     */
    @Test
    void theBulkheadGaugeIsPublishedWithoutWaitingForTraffic() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("gemini_slots_available")));
    }

    /**
     * The whole point. Exposure is wide open in this test's properties, and
     * these still have to be refused — because access control is a second,
     * independent decision from what is published.
     */
    @Test
    void everythingElseIsRefusedEvenWithExposureSetToEverything() throws Exception {
        for (String endpoint : new String[]{"env", "beans", "configprops", "loggers", "mappings", "threaddump"}) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void theActuatorIndexIsRefusedToo() throws Exception {
        // Otherwise it lists every exposed endpoint and its URL — a map of what
        // to try next, handed over for free.
        mockMvc.perform(get("/actuator")).andExpect(status().is4xxClientError());
    }
}
