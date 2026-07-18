package com.duabiskuttelur.controller;

import com.duabiskuttelur.client.FeedbackClient;
import com.duabiskuttelur.client.ProviderBusyException;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.FeedbackResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end happy path through the real service pipeline (vision provider
 * stubbed): upload photo -> foods identified -> nutrition resolved -> scored
 * and graded -> feedback attached -> history recorded. Also verifies the
 * rate-limited provider maps to a friendly 503.
 */
@SpringBootTest(properties = {
        "app.gemini-api-keys[0]=test-key",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:analyze-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AnalyzeEndpointTest {

    /** Authenticate requests as a fixed Google user so history is attributed. */
    private static RequestPostProcessor googleUser() {
        return oauth2Login().attributes(attrs -> {
            attrs.put("sub", "test-sub-123");
            attrs.put("email", "tester@example.com");
            attrs.put("name", "Test User");
        });
    }

    @TestConfiguration
    static class StubProviders {
        @Bean
        @Primary
        VisionAnalysisClient stubVision() {
            return Mockito.mock(VisionAnalysisClient.class);
        }

        @Bean
        @Primary
        FeedbackClient stubFeedback() {
            return Mockito.mock(FeedbackClient.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VisionAnalysisClient visionClient;

    @Autowired
    private FeedbackClient feedbackClient;

    private static final byte[] FAKE_JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    @BeforeEach
    void resetMocks() {
        Mockito.reset(visionClient, feedbackClient);
        Mockito.when(feedbackClient.generateFeedback(anyString())).thenReturn(new FeedbackResult(
                List.of("Good protein content"),
                List.of("High sodium"),
                List.of("Add ulam next time"),
                "Solid meal!"));
    }

    private static IdentifiedFood identifiedFood(String name, String group, boolean fried, double grams) {
        return new IdentifiedFood(name, "1 serving / ~" + (int) grams + "g", grams,
                name, 150, 10, 15, 6, 1.5, 2, 300, group, fried, 0.9);
    }

    @Test
    void analyzeEndpointReturnsScoredMeal() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of(
                identifiedFood("Grilled chicken", "protein", false, 150),
                identifiedFood("Rice", "grain", false, 200),
                identifiedFood("Kangkung belacan", "vegetable", false, 100)));

        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        mockMvc.perform(multipart("/api/analyze").file(image).with(googleUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foods.length()").value(3))
                .andExpect(jsonPath("$.foods[0].source").value("estimated")) // no USDA key -> fallback macros
                .andExpect(jsonPath("$.totals.calories").value(greaterThan(0.0)))
                .andExpect(jsonPath("$.score").value(greaterThan(0)))
                .andExpect(jsonPath("$.grade").value(not("")))
                .andExpect(jsonPath("$.highlights[0]").value("Good protein content"))
                .andExpect(jsonPath("$.encouragement").value("Solid meal!"))
                .andExpect(jsonPath("$.persisted").value(true));

        mockMvc.perform(get("/api/history").with(googleUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grade").value(not("")));
    }

    @Test
    void emptyIdentificationReturnsNoFoodDetected() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of());

        MockMultipartFile image = new MockMultipartFile("image", "blur.jpg", "image/jpeg", FAKE_JPEG);

        mockMvc.perform(multipart("/api/analyze").file(image).with(googleUser()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_FOOD_DETECTED"));
    }

    @Test
    void unauthenticatedApiCallReturns401() throws Exception {
        mockMvc.perform(get("/api/history"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousVisitorCanAnalyzeButItIsNotPersisted() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of(
                identifiedFood("Grilled chicken", "protein", false, 150)));
        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        // No auth -> still analyzes and returns a graded result, and the response
        // says so (persisted false) so the client can warn an expired session.
        mockMvc.perform(multipart("/api/analyze").file(image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grade").value(not("")))
                .andExpect(jsonPath("$.persisted").value(false));

        // ...but nothing was saved: a brand-new signed-in user (who never logged
        // a meal) has an empty history, proving the anonymous analyze wasn't stored.
        RequestPostProcessor freshUser = oauth2Login().attributes(attrs -> {
            attrs.put("sub", "fresh-sub-" + System.nanoTime());
            attrs.put("email", "fresh@example.com");
            attrs.put("name", "Fresh User");
        });
        mockMvc.perform(get("/api/history").with(freshUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void meReturnsProfileForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/me").with(googleUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("tester@example.com"))
                .andExpect(jsonPath("$.hasProfile").value(false));
    }

    @Test
    void historyIsScopedPerUser() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of(
                identifiedFood("Grilled chicken", "protein", false, 150)));
        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        // User A logs a meal
        mockMvc.perform(multipart("/api/analyze").file(image).with(googleUser()))
                .andExpect(status().isOk());

        // A different user sees an empty history, not User A's meal
        RequestPostProcessor otherUser = oauth2Login().attributes(attrs -> {
            attrs.put("sub", "other-sub-456");
            attrs.put("email", "other@example.com");
            attrs.put("name", "Other User");
        });
        mockMvc.perform(get("/api/history").with(otherUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void dashboardHasNoDataForFreshUser() throws Exception {
        RequestPostProcessor freshUser = oauth2Login().attributes(attrs -> {
            attrs.put("sub", "dash-empty-" + System.nanoTime());
            attrs.put("email", "dash-empty@example.com");
            attrs.put("name", "Dash Empty");
        });

        mockMvc.perform(get("/api/dashboard/today").with(freshUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasData").value(false))
                .andExpect(jsonPath("$.mealCount").value(0))
                .andExpect(jsonPath("$.averageGrade").doesNotExist());
    }

    @Test
    void dashboardAggregatesTodaysMeals() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of(
                identifiedFood("Grilled chicken", "protein", false, 150)));
        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        String dashSub = "dash-user-" + System.nanoTime();
        RequestPostProcessor dashUser = oauth2Login().attributes(attrs -> {
            attrs.put("sub", dashSub);
            attrs.put("email", "dash-user@example.com");
            attrs.put("name", "Dash User");
        });

        mockMvc.perform(multipart("/api/analyze").file(image).with(dashUser)).andExpect(status().isOk());
        mockMvc.perform(multipart("/api/analyze").file(image).with(dashUser)).andExpect(status().isOk());

        mockMvc.perform(get("/api/dashboard/today").with(dashUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasData").value(true))
                .andExpect(jsonPath("$.mealCount").value(2))
                .andExpect(jsonPath("$.totalCalories").value(greaterThan(0.0)))
                .andExpect(jsonPath("$.totalProtein").value(greaterThan(0.0)))
                .andExpect(jsonPath("$.averageGrade").value(not("")));
    }

    @Test
    void achievementsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/achievements"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void achievementsUnlockFirstMealAndTrackStreak() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of(
                identifiedFood("Grilled chicken", "protein", false, 150)));
        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        String freshSub = "badge-first-" + System.nanoTime();
        RequestPostProcessor freshUser = oauth2Login().attributes(attrs -> {
            attrs.put("sub", freshSub);
            attrs.put("email", "badge-first@example.com");
            attrs.put("name", "Badge First");
        });

        String before = mockMvc.perform(get("/api/achievements").with(freshUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMealsLogged").value(0))
                .andExpect(jsonPath("$.currentStreakDays").value(0))
                .andReturn().getResponse().getContentAsString();
        assertBadgeUnlocked(before, "it_begins", false);

        mockMvc.perform(multipart("/api/analyze").file(image).with(freshUser)).andExpect(status().isOk());

        String after = mockMvc.perform(get("/api/achievements").with(freshUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMealsLogged").value(1))
                .andExpect(jsonPath("$.currentStreakDays").value(1))
                .andReturn().getResponse().getContentAsString();
        assertBadgeUnlocked(after, "it_begins", true);
        assertBadgeUnlocked(after, "food_paparazzi", false);
        assertBadgeUnlocked(after, "barely_trying", false);
    }

    /** Reads a single badge's unlocked flag out of an /api/achievements JSON body. */
    @SuppressWarnings("unchecked")
    private static void assertBadgeUnlocked(String json, String badgeId, boolean expected) {
        java.util.List<java.util.Map<String, Object>> badges =
                com.jayway.jsonpath.JsonPath.parse(json).read("$.badges");
        boolean unlocked = badges.stream()
                .filter(b -> badgeId.equals(b.get("id")))
                .findFirst()
                .map(b -> (Boolean) b.get("unlocked"))
                .orElseThrow(() -> new AssertionError("badge not found: " + badgeId));
        org.junit.jupiter.api.Assertions.assertEquals(expected, unlocked, "badge " + badgeId);
    }

    @Test
    void achievementsUnlockMealCountMilestone() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of(
                identifiedFood("Grilled chicken", "protein", false, 150)));
        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        String tenSub = "badge-ten-" + System.nanoTime();
        RequestPostProcessor tenMealsUser = oauth2Login().attributes(attrs -> {
            attrs.put("sub", tenSub);
            attrs.put("email", "badge-ten@example.com");
            attrs.put("name", "Badge Ten");
        });

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(multipart("/api/analyze").file(image).with(tenMealsUser)).andExpect(status().isOk());
        }

        String json = mockMvc.perform(get("/api/achievements").with(tenMealsUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMealsLogged").value(10))
                .andReturn().getResponse().getContentAsString();
        assertBadgeUnlocked(json, "food_paparazzi", true);
        assertBadgeUnlocked(json, "certified_snack_detective", false);
    }

    @Test
    void pdfExportRequiresAuthAndIsScopedToOwner() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of(
                identifiedFood("Grilled chicken", "protein", false, 150)));
        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        String ownerSub = "pdf-owner-" + System.nanoTime();
        RequestPostProcessor owner = oauth2Login().attributes(attrs -> {
            attrs.put("sub", ownerSub);
            attrs.put("email", "pdf-owner@example.com");
            attrs.put("name", "PDF Owner");
        });

        mockMvc.perform(multipart("/api/analyze").file(image).with(owner))
                .andExpect(status().isOk());

        String historyJson = mockMvc.perform(get("/api/history").with(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long entryId = com.jayway.jsonpath.JsonPath.parse(historyJson).read("$[0].id", Long.class);

        // Visitors/unauthenticated callers can't export.
        mockMvc.perform(get("/api/history/" + entryId + "/pdf"))
                .andExpect(status().isUnauthorized());

        // A different signed-in user can't export someone else's report.
        RequestPostProcessor otherUser = oauth2Login().attributes(attrs -> {
            attrs.put("sub", "pdf-other-" + System.nanoTime());
            attrs.put("email", "pdf-other@example.com");
            attrs.put("name", "PDF Other");
        });
        mockMvc.perform(get("/api/history/" + entryId + "/pdf").with(otherUser))
                .andExpect(status().isNotFound());

        // The owner gets a real PDF back.
        mockMvc.perform(get("/api/history/" + entryId + "/pdf").with(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void deleteHistoryEntryRequiresAuthAndIsScopedToOwner() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString())).thenReturn(List.of(
                identifiedFood("Grilled chicken", "protein", false, 150)));
        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        String ownerSub = "del-owner-" + System.nanoTime();
        RequestPostProcessor owner = oauth2Login().attributes(attrs -> {
            attrs.put("sub", ownerSub);
            attrs.put("email", "del-owner@example.com");
            attrs.put("name", "Delete Owner");
        });

        mockMvc.perform(multipart("/api/analyze").file(image).with(owner))
                .andExpect(status().isOk());

        String historyJson = mockMvc.perform(get("/api/history").with(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long entryId = com.jayway.jsonpath.JsonPath.parse(historyJson).read("$[0].id", Long.class);

        // Unauthenticated callers can't delete.
        mockMvc.perform(delete("/api/history/" + entryId))
                .andExpect(status().isUnauthorized());

        // A different signed-in user can't delete someone else's entry.
        RequestPostProcessor otherUser = oauth2Login().attributes(attrs -> {
            attrs.put("sub", "del-other-" + System.nanoTime());
            attrs.put("email", "del-other@example.com");
            attrs.put("name", "Delete Other");
        });
        mockMvc.perform(delete("/api/history/" + entryId).with(otherUser))
                .andExpect(status().isNotFound());

        // Still there for the owner.
        mockMvc.perform(get("/api/history").with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // The owner can delete it, and it's gone afterward.
        mockMvc.perform(delete("/api/history/" + entryId).with(owner))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/history").with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Deleting it again 404s — it's already gone.
        mockMvc.perform(delete("/api/history/" + entryId).with(owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void rateLimitedProviderReturnsFriendly503() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString()))
                .thenThrow(new ProviderBusyException("quota"));

        MockMultipartFile image = new MockMultipartFile("image", "meal.jpg", "image/jpeg", FAKE_JPEG);

        mockMvc.perform(multipart("/api/analyze").file(image).with(googleUser()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ANALYZER_BUSY"));
    }

    @Test
    void missingUploadIsABadRequestNotAServerError() throws Exception {
        // No image part at all, and not even multipart/form-data — the exact
        // shape of `curl -X POST .../api/analyze`, which used to 500.
        mockMvc.perform(post("/api/analyze").with(googleUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void emptyImagePartIsABadRequest() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("image", "meal.jpg", "image/jpeg", new byte[0]);

        mockMvc.perform(multipart("/api/analyze").file(empty).with(googleUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
