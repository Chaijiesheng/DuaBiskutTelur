package com.duabiskuttelur.controller;

import com.duabiskuttelur.client.FeedbackClient;
import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.model.FeedbackResult;
import com.duabiskuttelur.model.IdentifiedFood;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Portion is the largest error source in the pipeline and, until this endpoint,
 * the only thing a user could do about a meal logged at double its real size was
 * delete it and start over — while it sat in their daily total, their calorie
 * budget and their achievement counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:portion-correction-test;DB_CLOSE_DELAY=-1",
        "app.gemini-api-keys[0]=test-key",
        "app.nutrition-cache-enabled=false",
})
class PortionCorrectionEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private VisionAnalysisClient visionClient;

    @MockitoBean
    private UsdaClient usdaClient;

    @MockitoBean
    private FeedbackClient feedbackClient;

    @BeforeEach
    void stubProviders() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());
        Mockito.when(feedbackClient.generateFeedback(anyString(), anyString()))
                .thenReturn(new FeedbackResult(List.of("Good protein"), List.of("High sodium"),
                        List.of("Add ulam"), "Solid meal!"));
    }

    private static RequestPostProcessor owner() {
        return oauth2Login().attributes(a -> {
            a.put("sub", "portion-owner");
            a.put("email", "owner@example.com");
            a.put("name", "Owner");
        });
    }

    private static RequestPostProcessor otherUser() {
        return oauth2Login().attributes(a -> {
            a.put("sub", "portion-stranger");
            a.put("email", "stranger@example.com");
            a.put("name", "Stranger");
        });
    }

    private static IdentifiedFood food(String name, double grams, String group) {
        return new IdentifiedFood(name, "1 plate / ~" + (int) grams + "g", grams, grams * 0.8, grams * 1.2,
                name, 200, 10, 25, 8, 2, 3, 400, group, "steamed", 0.9, null);
    }

    /** Analyzes a two-item meal and returns the saved entry id. */
    private JsonNode analyzeTwoItemMeal() throws Exception {
        Mockito.when(visionClient.identifyFoods(any(), anyString()))
                .thenReturn(List.of(food("Nasi lemak", 300, "grain"), food("Ayam goreng", 120, "protein")));

        String body = mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("image", "meal.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3}))
                        .param("lang", "en")
                        .with(owner()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private String multipliers(double... values) throws Exception {
        StringBuilder json = new StringBuilder("{\"multipliers\":[");
        for (int i = 0; i < values.length; i++) {
            json.append(i > 0 ? "," : "").append(values[i]);
        }
        return json.append("]}").toString();
    }

    /**
     * Without an id on the analyze response there is nothing for the results
     * screen to correct — the user would have to leave, find the meal in
     * history, and come back.
     */
    @Test
    void analyzeReturnsTheIdOfTheRowItSaved() throws Exception {
        JsonNode result = analyzeTwoItemMeal();
        assertTrue(result.path("entryId").isNumber(), "no entryId on the analyze response: " + result);
    }

    @Test
    void halvingAPortionHalvesThatItemAndRegradesTheMeal() throws Exception {
        JsonNode before = analyzeTwoItemMeal();
        long id = before.path("entryId").asLong();
        double firstItemCalories = before.path("foods").get(0).path("calories").asDouble();
        double secondItemCalories = before.path("foods").get(1).path("calories").asDouble();

        String body = mockMvc.perform(put("/api/history/" + id + "/portions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multipliers(0.5, 1.0))
                        .with(owner()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode after = mapper.readTree(body);

        assertEquals(firstItemCalories / 2, after.path("foods").get(0).path("calories").asDouble(), 0.15);
        assertEquals(secondItemCalories, after.path("foods").get(1).path("calories").asDouble(), 0.001);
        assertEquals(0.5, after.path("foods").get(0).path("portionMultiplier").asDouble(), 0.001);
        assertEquals(after.path("foods").get(0).path("calories").asDouble()
                        + after.path("foods").get(1).path("calories").asDouble(),
                after.path("totals").path("calories").asDouble(), 0.15);
    }

    /**
     * The whole reason multipliers are absolute rather than cumulative. A slider
     * gets dragged; if each move scaled the already-scaled numbers, the meal
     * would drift away from the truth with every adjustment and never come back.
     */
    @Test
    void draggingTheSliderAroundReturnsExactlyToTheOriginalNumbers() throws Exception {
        JsonNode before = analyzeTwoItemMeal();
        long id = before.path("entryId").asLong();
        double original = before.path("totals").path("calories").asDouble();

        for (String step : List.of("0.5,1.0", "2.0,1.0", "0.25,1.0", "1.0,1.0")) {
            String[] parts = step.split(",");
            mockMvc.perform(put("/api/history/" + id + "/portions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(multipliers(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])))
                            .with(owner()))
                    .andExpect(status().isOk());
        }

        String body = mockMvc.perform(get("/api/history/" + id).with(owner()))
                .andReturn().getResponse().getContentAsString();
        assertEquals(original, mapper.readTree(body).path("totals").path("calories").asDouble(), 0.3);
    }

    /** The corrected meal has to be what the dashboard and history see, not just what the screen showed. */
    @Test
    void theCorrectionIsPersistedAndSurvivesAReload() throws Exception {
        JsonNode before = analyzeTwoItemMeal();
        long id = before.path("entryId").asLong();

        mockMvc.perform(put("/api/history/" + id + "/portions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multipliers(0.5, 0.5))
                        .with(owner()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/history").with(owner()))
                .andExpect(jsonPath("$[0].calories").value(
                        org.hamcrest.Matchers.closeTo(before.path("totals").path("calories").asDouble() / 2, 0.3)));
    }

    /**
     * A mismatch means the client is working from a different version of the
     * entry. Applying the multipliers that happen to line up would corrupt the
     * row silently.
     */
    @Test
    void refusesAListThatDoesNotLineUpWithTheStoredMeal() throws Exception {
        long id = analyzeTwoItemMeal().path("entryId").asLong();

        mockMvc.perform(put("/api/history/" + id + "/portions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multipliers(0.5))
                        .with(owner()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMultipliersOutsideTheAllowedRange() throws Exception {
        long id = analyzeTwoItemMeal().path("entryId").asLong();

        for (String out : List.of("{\"multipliers\":[100,1.0]}", "{\"multipliers\":[0,1.0]}",
                "{\"multipliers\":[-1,1.0]}", "{\"multipliers\":[]}")) {
            mockMvc.perform(put("/api/history/" + id + "/portions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(out)
                            .with(owner()))
                    .andExpect(status().isBadRequest());
        }
    }

    /** History is per-user everywhere else; a write path is the worst place to forget that. */
    @Test
    void anotherUserCannotCorrectSomeoneElsesMeal() throws Exception {
        long id = analyzeTwoItemMeal().path("entryId").asLong();

        mockMvc.perform(put("/api/history/" + id + "/portions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multipliers(0.5, 0.5))
                        .with(otherUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void visitorsCannotCorrectAnything() throws Exception {
        mockMvc.perform(put("/api/history/1/portions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multipliers(0.5)))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- removal (U2)

    /**
     * The case a multiplier cannot express. Its floor is 0.25x, and even an
     * unbounded one would only shrink a hallucinated dish toward zero while it
     * still counted toward variety and the food-group mix — a phantom vegetable
     * would keep earning its bonus at any size.
     */
    @Test
    void removingAnItemDropsItAndRegradesWhatIsLeft() throws Exception {
        JsonNode before = analyzeTwoItemMeal();
        long id = before.path("entryId").asLong();
        double secondItemCalories = before.path("foods").get(1).path("calories").asDouble();

        String body = mockMvc.perform(delete("/api/history/" + id + "/foods/0").with(owner()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode after = mapper.readTree(body);

        assertEquals(1, after.path("foods").size(), "the removed item is still there: " + after.path("foods"));
        assertEquals("Ayam goreng", after.path("foods").get(0).path("name").asText(),
                "the wrong item was removed");
        assertEquals(secondItemCalories, after.path("totals").path("calories").asDouble(), 0.15,
                "the totals still include the removed item");
    }

    @Test
    void aRemovalIsPersistedAndSurvivesAReload() throws Exception {
        long id = analyzeTwoItemMeal().path("entryId").asLong();

        mockMvc.perform(delete("/api/history/" + id + "/foods/0").with(owner()))
                .andExpect(status().isOk());

        String reloaded = mockMvc.perform(get("/api/history/" + id).with(owner()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, mapper.readTree(reloaded).path("foods").size(),
                "the removal was not written back to the stored row");
    }

    /**
     * 409, not 400: the request is well-formed and the user is not confused.
     * They are telling us the whole entry is wrong, which is what deleting it is
     * for — so the code has to be distinguishable enough for the client to offer
     * that instead.
     */
    @Test
    void refusesToEmptyAMealAndSaysWhy() throws Exception {
        long id = analyzeTwoItemMeal().path("entryId").asLong();
        mockMvc.perform(delete("/api/history/" + id + "/foods/0").with(owner()))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/history/" + id + "/foods/0").with(owner()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LAST_FOOD"));
    }

    @Test
    void rejectsAnIndexThatIsNotInTheStoredMeal() throws Exception {
        long id = analyzeTwoItemMeal().path("entryId").asLong();

        mockMvc.perform(delete("/api/history/" + id + "/foods/7").with(owner()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/history/" + id + "/foods/-1").with(owner()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherUserCannotRemoveAFoodFromSomeoneElsesMeal() throws Exception {
        long id = analyzeTwoItemMeal().path("entryId").asLong();

        mockMvc.perform(delete("/api/history/" + id + "/foods/0").with(otherUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void visitorsCannotRemoveAnything() throws Exception {
        mockMvc.perform(delete("/api/history/1/foods/0"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Removal shifts every later index by one. A correction sent straight after
     * has to line up with the shortened list, not the original — the server
     * rejecting a stale length is what stops one food's correction landing on
     * another.
     */
    @Test
    void aCorrectionAfterARemovalMustMatchTheShortenedList() throws Exception {
        long id = analyzeTwoItemMeal().path("entryId").asLong();
        mockMvc.perform(delete("/api/history/" + id + "/foods/0").with(owner()))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/history/" + id + "/portions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multipliers(0.5, 0.5))
                        .with(owner()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/history/" + id + "/portions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multipliers(0.5))
                        .with(owner()))
                .andExpect(status().isOk());
    }
}
