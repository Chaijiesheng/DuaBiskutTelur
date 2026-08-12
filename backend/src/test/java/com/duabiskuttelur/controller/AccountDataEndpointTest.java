package com.duabiskuttelur.controller;

import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.duabiskuttelur.persistence.MenuScanEntity;
import com.duabiskuttelur.persistence.MenuScanRepository;
import com.duabiskuttelur.persistence.NutritionCacheEntity;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.UserRepository;
import com.duabiskuttelur.persistence.WaterEntity;
import com.duabiskuttelur.persistence.WaterRepository;
import com.duabiskuttelur.persistence.WeightEntity;
import com.duabiskuttelur.persistence.WeightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export and deletion against the real schema and repositories, because both
 * are claims about what is actually in the database — mocks would only restate
 * the code's own assumptions about which tables exist.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:account-data-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AccountDataEndpointTest {

    private static final String LEAVING_SUB = "sub-leaving";
    private static final String STAYING_SUB = "sub-staying";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MealAnalysisRepository mealRepository;
    @Autowired private MenuScanRepository menuScanRepository;
    @Autowired private WaterRepository waterRepository;
    @Autowired private WeightRepository weightRepository;
    @Autowired private NutritionCacheRepository nutritionCacheRepository;

    private Long leavingUserId;
    private Long stayingUserId;

    private static RequestPostProcessor signedInAs(String sub) {
        return oauth2Login().attributes(attrs -> {
            attrs.put("sub", sub);
            attrs.put("email", sub + "@example.com");
            attrs.put("name", "Test " + sub);
        });
    }

    @BeforeEach
    void seedTwoAccounts() {
        mealRepository.deleteAll();
        menuScanRepository.deleteAll();
        waterRepository.deleteAll();
        weightRepository.deleteAll();
        nutritionCacheRepository.deleteAll();
        userRepository.deleteAll();

        leavingUserId = createUserWithData(LEAVING_SUB);
        stayingUserId = createUserWithData(STAYING_SUB);

        // Shared, not personal: keyed by dish name, used by every user's scans.
        NutritionCacheEntity cached = new NutritionCacheEntity();
        cached.setCanonicalName("char kway teow");
        cached.setDisplayName("Char kway teow");
        cached.setResolvedAt(Instant.now());
        cached.setSource("estimated");
        cached.setFried(true);
        cached.setConfidence(0.9);
        cached.setGrams(350);
        cached.setCaloriesPer100g(176);
        cached.setProteinPer100g(6);
        cached.setCarbsPer100g(22);
        cached.setFatPer100g(7);
        cached.setFiberPer100g(1.2);
        cached.setSugarPer100g(2);
        cached.setSodiumPer100g(620);
        nutritionCacheRepository.save(cached);
    }

    private Long createUserWithData(String googleSub) {
        UserEntity user = new UserEntity();
        user.setGoogleSub(googleSub);
        user.setEmail(googleSub + "@example.com");
        user.setName("Test " + googleSub);
        user.setCreatedAt(Instant.now());
        user.setWeightKg(70.0);
        user.setGoal("maintenance");
        Long userId = userRepository.save(user).getId();

        MealAnalysisEntity meal = new MealAnalysisEntity();
        meal.setUserId(userId);
        meal.setCreatedAt(Instant.now());
        meal.setScore(78);
        meal.setGrade("B");
        meal.setCalories(831.0);
        meal.setSummary("Nasi lemak, Telur rebus");
        meal.setSource("photo");
        meal.setResultJson("{\"grade\":\"B\",\"score\":78,\"foods\":[{\"name\":\"Nasi lemak\"}]}");
        mealRepository.save(meal);

        MenuScanEntity menu = new MenuScanEntity();
        menu.setUserId(userId);
        menu.setCreatedAt(Instant.now());
        menu.setDishCount(3);
        menu.setTruncated(false);
        menu.setSummary("Char kway teow, Roti canai");
        menu.setResultJson("{\"dishCount\":3}");
        menuScanRepository.save(menu);

        WaterEntity water = new WaterEntity();
        water.setUserId(userId);
        water.setDate(LocalDate.now());
        water.setTotalMl(1500);
        waterRepository.save(water);

        WeightEntity weight = new WeightEntity();
        weight.setUserId(userId);
        weight.setWeightKg(70.5);
        weight.setLoggedAt(Instant.now());
        weightRepository.save(weight);

        return userId;
    }

    // --- export ---

    @Test
    void exportReturnsTheWholeRecordAsADownloadableFile() throws Exception {
        mvc.perform(get("/api/account/export").with(signedInAs(LEAVING_SUB)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("duabiskuttelur-data-export.json")))
                .andExpect(jsonPath("$.profile.email").value(LEAVING_SUB + "@example.com"))
                .andExpect(jsonPath("$.profile.weightKg").value(70.0))
                .andExpect(jsonPath("$.meals.length()").value(1))
                .andExpect(jsonPath("$.menuScans.length()").value(1))
                .andExpect(jsonPath("$.water.length()").value(1))
                .andExpect(jsonPath("$.weighIns.length()").value(1))
                .andExpect(jsonPath("$.weighIns[0].weightKg").value(70.5));
    }

    /**
     * The stored analysis is a JSON string in the database. Exporting it as a
     * string would hand the user an escaped blob inside their file; the point of
     * an export is that its subject can read it.
     */
    @Test
    void storedAnalysesAreEmbeddedAsRealJsonNotEscapedStrings() throws Exception {
        mvc.perform(get("/api/account/export").with(signedInAs(LEAVING_SUB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meals[0].result.grade").value("B"))
                .andExpect(jsonPath("$.meals[0].result.foods[0].name").value("Nasi lemak"));
    }

    @Test
    void exportOnlyEverContainsTheCallersOwnData() throws Exception {
        String body = mvc.perform(get("/api/account/export").with(signedInAs(LEAVING_SUB)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(LEAVING_SUB), "expected the caller's own record");
        assertTrue(!body.contains(STAYING_SUB), "another account leaked into the export");
    }

    @Test
    void exportRequiresSigningIn() throws Exception {
        mvc.perform(get("/api/account/export")).andExpect(status().isUnauthorized());
    }

    // --- deletion ---

    @Test
    void deletionRemovesEveryRowBelongingToTheAccount() throws Exception {
        mvc.perform(delete("/api/account").with(signedInAs(LEAVING_SUB)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertTrue(userRepository.findByGoogleSub(LEAVING_SUB).isEmpty(), "user row survived");
        assertEquals(0, mealRepository.findByUserIdOrderByCreatedAtDesc(leavingUserId).size(), "meals survived");
        assertEquals(0, menuScanRepository.findByUserIdOrderByCreatedAtDesc(leavingUserId).size(), "menu scans survived");
        assertEquals(0, waterRepository.findByUserIdOrderByDateDesc(leavingUserId).size(), "water survived");
        assertEquals(0, weightRepository.findByUserIdOrderByLoggedAtDesc(leavingUserId).size(), "weigh-ins survived");
    }

    @Test
    void deletingOneAccountLeavesEveryOtherAccountIntact() throws Exception {
        mvc.perform(delete("/api/account").with(signedInAs(LEAVING_SUB)))
                .andExpect(status().isNoContent());

        assertTrue(userRepository.findByGoogleSub(STAYING_SUB).isPresent(), "the other user was deleted too");
        assertEquals(1, mealRepository.findByUserIdOrderByCreatedAtDesc(stayingUserId).size());
        assertEquals(1, menuScanRepository.findByUserIdOrderByCreatedAtDesc(stayingUserId).size());
        assertEquals(1, waterRepository.findByUserIdOrderByDateDesc(stayingUserId).size());
        assertEquals(1, weightRepository.findByUserIdOrderByLoggedAtDesc(stayingUserId).size());
    }

    /**
     * nutrition_cache is keyed by dish name and shared across all users — "char
     * kway teow has this many calories per 100g" is not a fact about whoever is
     * leaving. Deleting it would degrade every remaining user's results, and
     * would also break the determinism guarantee those rows exist to provide.
     */
    @Test
    void deletionLeavesTheSharedNutritionCacheStanding() throws Exception {
        mvc.perform(delete("/api/account").with(signedInAs(LEAVING_SUB)))
                .andExpect(status().isNoContent());

        assertTrue(nutritionCacheRepository.findByCanonicalName("char kway teow").isPresent(),
                "shared nutrition data was deleted along with the account");
    }

    @Test
    void deletionRequiresSigningIn() throws Exception {
        mvc.perform(delete("/api/account")).andExpect(status().isUnauthorized());
        assertTrue(userRepository.findByGoogleSub(LEAVING_SUB).isPresent(), "an anonymous call deleted an account");
    }
}
