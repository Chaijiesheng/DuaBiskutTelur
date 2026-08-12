package com.duabiskuttelur.controller;

import com.duabiskuttelur.client.ProviderBusyException;
import com.duabiskuttelur.client.VisionAnalysisClient;
import com.duabiskuttelur.model.IdentifiedFood;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through the real service pipeline (vision provider stubbed):
 * upload a menu photo -> dishes identified -> each scored independently ->
 * grouped into 5 tiers -> persisted only for signed-in users.
 */
@SpringBootTest(properties = {
        "app.gemini-api-keys[0]=test-key",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:menu-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class MenuControllerTest {

    private static RequestPostProcessor googleUser(String sub) {
        return oauth2Login().attributes(attrs -> {
            attrs.put("sub", sub);
            attrs.put("email", sub + "@example.com");
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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VisionAnalysisClient visionClient;

    private static final byte[] FAKE_JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    @BeforeEach
    void resetMocks() {
        Mockito.reset(visionClient);
    }

    /** grams chosen so the dish is a single-item "meal" clearly outside small-snack territory (>250 kcal). */
    private static IdentifiedFood dish(String name, String group, boolean fried,
                                        double caloriesPer100g, double sodiumPer100g, double grams) {
        return new IdentifiedFood(name, "1 serving / ~" + (int) grams + "g", grams, grams * 0.8, grams * 1.2,
                name, caloriesPer100g, 8, 15, 6, 1.5, 2, sodiumPer100g, group,
                fried ? "deep-fried" : "steamed", 0.9);
    }

    private static List<IdentifiedFood> spreadAcrossTiers() {
        return List.of(
                dish("Steamed fish", "protein", false, 90, 60, 220),   // light, low sodium -> likely a top tier
                dish("Fried chicken wings", "protein", true, 260, 550, 220), // fried + high sodium -> likely a low tier
                dish("Fried rice", "grain", true, 190, 480, 300));
    }

    @Test
    void menuRankEndpointReturnsFiveTierGroups() throws Exception {
        Mockito.when(visionClient.identifyMenuDishes(any(), anyString())).thenReturn(spreadAcrossTiers());

        MockMultipartFile image = new MockMultipartFile("image", "menu.jpg", "image/jpeg", FAKE_JPEG);

        mockMvc.perform(multipart("/api/menu/rank").file(image).with(googleUser("menu-happy")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiers.length()").value(5))
                .andExpect(jsonPath("$.tiers[0].tier").value("HANG"))
                .andExpect(jsonPath("$.tiers[0].label").value("夯"))
                .andExpect(jsonPath("$.tiers[4].tier").value("LAWANLE"))
                .andExpect(jsonPath("$.tiers[4].label").value("拉完了"))
                .andExpect(jsonPath("$.dishCount").value(3))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.persisted").value(true));
    }

    @Test
    void emptyMenuReturnsNoDishesDetected422() throws Exception {
        Mockito.when(visionClient.identifyMenuDishes(any(), anyString())).thenReturn(List.of());

        MockMultipartFile image = new MockMultipartFile("image", "blur.jpg", "image/jpeg", FAKE_JPEG);

        mockMvc.perform(multipart("/api/menu/rank").file(image).with(googleUser("menu-empty")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_DISHES_DETECTED"));
    }

    @Test
    void anonymousVisitorCanRankButNothingIsPersisted() throws Exception {
        Mockito.when(visionClient.identifyMenuDishes(any(), anyString())).thenReturn(spreadAcrossTiers());
        MockMultipartFile image = new MockMultipartFile("image", "menu.jpg", "image/jpeg", FAKE_JPEG);

        mockMvc.perform(multipart("/api/menu/rank").file(image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dishCount").value(3))
                .andExpect(jsonPath("$.persisted").value(false));

        // A brand-new signed-in user has an empty menu history, proving the
        // anonymous scan above wasn't stored anywhere.
        mockMvc.perform(get("/api/menu/history").with(googleUser("menu-fresh-" + System.nanoTime())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void signedInScanAppearsInHistoryAndCanBeReopenedAndDeleted() throws Exception {
        Mockito.when(visionClient.identifyMenuDishes(any(), anyString())).thenReturn(spreadAcrossTiers());
        MockMultipartFile image = new MockMultipartFile("image", "menu.jpg", "image/jpeg", FAKE_JPEG);
        RequestPostProcessor owner = googleUser("menu-owner-" + System.nanoTime());

        mockMvc.perform(multipart("/api/menu/rank").file(image).with(owner))
                .andExpect(status().isOk());

        String historyJson = mockMvc.perform(get("/api/menu/history").with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dishCount").value(3))
                .andReturn().getResponse().getContentAsString();
        long entryId = com.jayway.jsonpath.JsonPath.parse(historyJson).read("$[0].id", Long.class);

        mockMvc.perform(get("/api/menu/history/" + entryId).with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiers.length()").value(5))
                .andExpect(jsonPath("$.dishCount").value(3));

        // A different signed-in user can't see or reopen someone else's scan.
        RequestPostProcessor otherUser = googleUser("menu-other-" + System.nanoTime());
        mockMvc.perform(get("/api/menu/history").with(otherUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/menu/history/" + entryId).with(otherUser))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/menu/history/" + entryId).with(owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/menu/history").with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void menuHistoryRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/menu/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitedProviderReturnsFriendly503() throws Exception {
        Mockito.when(visionClient.identifyMenuDishes(any(), anyString()))
                .thenThrow(new ProviderBusyException("quota"));

        MockMultipartFile image = new MockMultipartFile("image", "menu.jpg", "image/jpeg", FAKE_JPEG);

        mockMvc.perform(multipart("/api/menu/rank").file(image).with(googleUser("menu-busy")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ANALYZER_BUSY"));
    }

    @Test
    void missingUploadIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/menu/rank").with(googleUser("menu-missing")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void emptyImagePartIsABadRequest() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("image", "menu.jpg", "image/jpeg", new byte[0]);

        mockMvc.perform(multipart("/api/menu/rank").file(empty).with(googleUser("menu-emptyfile")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
