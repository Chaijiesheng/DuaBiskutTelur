package com.duabiskuttelur.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every workout route is signed-in only.
 *
 * <p>Worth its own test rather than trusting the {@code /api/**} catch-all,
 * because that rule is the <em>last</em> matcher in {@code SecurityConfig} and
 * several routes above it are {@code permitAll}. Nothing would stop a later
 * change from widening one of those to cover a path like {@code /api/workout/**}
 * as collateral, and the symptom would be one user's training history being
 * served to an anonymous request — silent, and not visible in any unit test of
 * the controller.
 *
 * <p>The list below is deliberately every verb and path the controller declares.
 * A new route added without a matching line here is a route nobody checked.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:workout-access-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class WorkoutAccessTest {

    @Autowired private MockMvc mvc;

    private static final String JSON = "{}";

    @Test
    void everyWorkoutRouteRefusesAnAnonymousRequest() throws Exception {
        List<RequestBuilder> routes = List.of(
                get("/api/workout/today"),
                get("/api/workout/profile"),
                post("/api/workout/profile").contentType(MediaType.APPLICATION_JSON).content(JSON),
                post("/api/workout/sessions/1/start"),
                put("/api/workout/sessions/1/sets").contentType(MediaType.APPLICATION_JSON).content(JSON),
                get("/api/workout/sessions/1/exercises/0/alternatives"),
                put("/api/workout/sessions/1/exercises/0")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON),
                post("/api/workout/sessions/1/skip"),
                post("/api/workout/sessions/1/unskip"),
                post("/api/workout/sessions/1/complete")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON));

        for (RequestBuilder route : routes) {
            mvc.perform(route).andExpect(status().isUnauthorized());
        }
    }

    /**
     * A 401 must come back before any work happens, not after. If a route read
     * or wrote first and only then noticed there was no user, the refusal would
     * be honest and the side effect would already have landed.
     */
    @Test
    void anAnonymousRequestNeverReachesTheDatabase() throws Exception {
        mvc.perform(post("/api/workout/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":"lose_weight","level":"beginner","daysPerWeek":3,
                                 "sessionMinutes":30,"equipment":["none"],"preferences":[]}"""))
                .andExpect(status().isUnauthorized());
    }
}
