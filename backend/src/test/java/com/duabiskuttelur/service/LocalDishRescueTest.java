package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient;
import com.duabiskuttelur.client.UsdaClient.NutrientsPer100g;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.persistence.NutritionCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The curated dish table is a <em>rescue</em>, not a first resort.
 *
 * <p>This class used to assert the opposite — table first, USDA only if the
 * table missed — and that ordering was abandoned on measurement, not taste. One
 * curated row has to stand for a dish every stall cooks differently, so it loses
 * to a specific USDA match that passed validation (rho 0.790 against 0.665 on
 * the 30-dish benchmark, and 0.596 against 0.484 once production re-measured
 * it). It wins only where the USDA path has already produced something
 * impossible. {@link NutritionResolutionTest} carries the measurement itself;
 * this class pins the branch structure that implements it.
 *
 * <p>Four things have to hold, and each is a separate way the rescue could rot
 * into a first resort without anyone noticing:
 * <ol>
 *   <li>USDA is asked first, always;
 *   <li>a match that passes validation ends it — the table is never reached;
 *   <li>the table answers when, and only when, USDA has failed (missed, or been
 *       rejected by {@link NutritionValidator});
 *   <li>the model estimate still backstops both.
 * </ol>
 *
 * <p>The three sources are given deliberately different calorie densities, so
 * the number on the plate names the source that produced it and no assertion
 * has to trust a mock's call log to know which branch ran.
 */
class LocalDishRescueTest {

    private final UsdaClient usdaClient = Mockito.mock(UsdaClient.class);
    /**
     * The old DB-backed lookup, kept wired into the constructor but deliberately
     * out of the resolution path. Mocked with no stubbing at all, so
     * {@link #resolutionNeverConsultsTheDatabaseBackedLookup()} fails the moment
     * anything puts it back.
     */
    private final LocalFoodService localFoodService = Mockito.mock(LocalFoodService.class);

    /** 210 kcal/100g — 630 at the photo's 300g portion, and nothing else lands there. */
    private static final NutrientsPer100g CURATED =
            new NutrientsPer100g("nasi lemak (local table)", 210, 5, 30, 8, 1.5, 2, 320);

    /**
     * A plate of nasi lemak. {@code usdaSearchTerm} is the model translating a
     * local dish into the nearest generic USDA has — the approximation the whole
     * rescue exists to catch. Its own fallback is 150 kcal/100g, so a model
     * estimate shows up as 450.
     */
    private static IdentifiedFood nasiLemak() {
        return new IdentifiedFood("Nasi lemak", "1 plate / ~300g", 300, 240, 360, "coconut rice",
                150, 3, 25, 5, 1, 1, 250, "grain", "steamed", 0.9, null);
    }

    /**
     * What a good day at USDA looks like: a coconut rice row that reconciles and
     * carries the carbohydrate a rice dish must have. 160 kcal/100g → 480.
     */
    private static NutrientsPer100g plausibleUsdaMatch() {
        return new NutrientsPer100g("Rice, coconut", 160, 3, 26, 5, 1, 1, 200);
    }

    /**
     * What a bad day looks like, and it is not a miss — USDA answers, with
     * canned coconut milk. Internally consistent, wrong food: 3g of carbohydrate
     * for a dish whose name starts with "nasi" is the case
     * {@link NutritionValidator}'s starch rule exists for. Rejected, so the
     * rescue runs.
     */
    private static NutrientsPer100g implausibleUsdaMatch() {
        return new NutrientsPer100g("Coconut milk, canned", 230, 2.3, 3.0, 24.0, 0.0, 3.0, 15);
    }

    private static LocalDishTable tableKnowingNasiLemak() {
        return tableKnowingNasiLemak("grain", false);
    }

    private static LocalDishTable tableKnowingNasiLemak(String foodGroup, boolean fried) {
        return new LocalDishTable(List.of(new LocalDishTable.Entry(
                "nasi lemak", List.of("nasi", "lemak"), "nasi lemak", CURATED, foodGroup, fried)));
    }

    /** A table that knows nothing, so resolution has to fall past it. */
    private static LocalDishTable emptyTable() {
        return new LocalDishTable(List.of());
    }

    private AnalysisService serviceWith(LocalDishTable table, boolean gateOpen) {
        AppProperties props = new AppProperties();
        // The cache would pin the first answer and make every later assertion
        // about the cache instead of about the branch under test.
        props.setNutritionCacheEnabled(false);
        props.setLocalDishTableEnabled(gateOpen);
        NutritionCacheService cache = new NutritionCacheService(
                Mockito.mock(NutritionCacheRepository.class), props, new SimpleMeterRegistry());
        return new AnalysisService(null, usdaClient, cache, localFoodService, table,
                null, null, null, null, null, props, new ObjectMapper(), new SimpleMeterRegistry());
    }

    /** The gate is off in production; these tests describe the path behind it. */
    private AnalysisService serviceWith(LocalDishTable table) {
        return serviceWith(table, true);
    }

    // ---------------------------------------------------------------- USDA first

    @Test
    void aUsdaMatchThatPassesValidationEndsItAndTheTableIsNeverReached() {
        Mockito.when(usdaClient.lookup("coconut rice")).thenReturn(Optional.of(plausibleUsdaMatch()));

        FoodItem food = serviceWith(tableKnowingNasiLemak()).resolveNutrition(nasiLemak());

        assertEquals("usda", food.source());
        assertEquals(480, food.calories(), 0.1);
        // The table holds a row for this exact dish and still must not be used.
        // Asserting the number rather than the absence of a call is what makes
        // this fail if the ordering is ever flipped back.
        assertNotEquals(630, food.calories(), "the curated row overtook a valid USDA match");
    }

    @Test
    void usdaIsAskedFirstEvenForADishTheTableKnows() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.of(implausibleUsdaMatch()));

        serviceWith(tableKnowingNasiLemak()).resolveNutrition(nasiLemak());

        // Local-first would have answered from the table and never made this
        // call, which is precisely the ordering that measured worse.
        Mockito.verify(usdaClient).lookup("coconut rice");
    }

    // ------------------------------------------------------------ rescue only

    /**
     * The rescue's real trigger. A miss is the easy half; this is the half that
     * matters, because USDA <em>answered</em> and the answer was about a
     * different food.
     */
    @Test
    void aMatchRejectedByTheValidatorIsRescuedByTheCuratedTable() {
        Mockito.when(usdaClient.lookup("coconut rice")).thenReturn(Optional.of(implausibleUsdaMatch()));

        FoodItem food = serviceWith(tableKnowingNasiLemak()).resolveNutrition(nasiLemak());

        assertEquals("local", food.source());
        assertEquals(630, food.calories(), 0.1);
    }

    @Test
    void aUsdaMissIsRescuedByTheCuratedTable() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());

        FoodItem food = serviceWith(tableKnowingNasiLemak()).resolveNutrition(nasiLemak());

        assertEquals("local", food.source());
        assertEquals(630, food.calories(), 0.1);
    }

    /**
     * A zero-calorie row is USDA answering about something that isn't food yet —
     * treated as no answer at all, so the rescue still runs.
     */
    @Test
    void aZeroCalorieMatchCountsAsNoMatchAndStillRescues() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.of(
                new NutrientsPer100g("Rice, raw, uncooked", 0, 0, 0, 0, 0, 0, 0)));

        assertEquals("local", serviceWith(tableKnowingNasiLemak()).resolveNutrition(nasiLemak()).source());
    }

    // ------------------------------------------------------- model backstop

    @Test
    void withNeitherUsdaNorACuratedRowTheModelEstimateStillAnswers() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());

        FoodItem food = serviceWith(emptyTable()).resolveNutrition(nasiLemak());

        assertEquals("estimated", food.source());
        assertEquals(450, food.calories(), 0.1);
    }

    /** Both upstream sources present and both unusable — the backstop is the last word. */
    @Test
    void aRejectedMatchWithNoCuratedRowFallsAllTheWayToTheModel() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.of(implausibleUsdaMatch()));

        FoodItem food = serviceWith(emptyTable()).resolveNutrition(nasiLemak());

        assertEquals("estimated", food.source());
        assertEquals(450, food.calories(), 0.1);
    }

    // -------------------------------------------------------------- the gate

    /**
     * The table ships disabled — it rejects on per-100g density where the fault
     * it is meant to catch is a per-serving total, so it stays off until that is
     * fixed. A gate that only stops the happy path is not a gate: the case worth
     * pinning is the one where the table has an answer and is asked not to give
     * it.
     */
    @Test
    void withTheGateClosedARejectedMatchGoesStraightToTheModel() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.of(implausibleUsdaMatch()));

        FoodItem food = serviceWith(tableKnowingNasiLemak(), false).resolveNutrition(nasiLemak());

        assertEquals("estimated", food.source());
        assertEquals(450, food.calories(), 0.1);
    }

    /** With the gate closed, a valid USDA match is still a valid USDA match. */
    @Test
    void theGateOnlyGovernsTheRescueAndNotTheUsdaPath() {
        Mockito.when(usdaClient.lookup("coconut rice")).thenReturn(Optional.of(plausibleUsdaMatch()));

        FoodItem food = serviceWith(tableKnowingNasiLemak(), false).resolveNutrition(nasiLemak());

        assertEquals("usda", food.source());
        assertEquals(480, food.calories(), 0.1);
    }

    // ------------------------------------------------- what the rescue carries

    /**
     * The photo saw a plate; the table knows a composition. Composition scales to
     * the observed portion — it never replaces it, or every nasi lemak in the app
     * becomes one canned serving size.
     */
    @Test
    void aRescuedDishKeepsThePortionThePhotoActuallySaw() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());

        FoodItem food = serviceWith(tableKnowingNasiLemak()).resolveNutrition(nasiLemak());

        assertEquals("1 plate / ~300g", food.estimatedPortion());
        assertTrue(food.caloriesHigh() > food.caloriesLow(),
                "the photo's portion uncertainty should survive a certain composition");
        // 210 kcal/100g across the 240-360g bracket the model gave.
        assertEquals(504, food.caloriesLow(), 0.1);
        assertEquals(756, food.caloriesHigh(), 0.1);
    }

    /**
     * A menu scan has no plate to measure, and — unlike the DB-backed table this
     * replaced — the CSV carries no typical serving to put in its place. It is
     * per-100g composition and nothing else. So a menu keeps the portion it was
     * given; what makes a menu's portion stable across scans is the cache
     * pinning it, which is {@link NutritionCacheServiceTest}'s subject, not this
     * one. Pinned here so a serving column can't be added back without someone
     * deciding on purpose that the rescue may move a portion.
     */
    @Test
    void aRescuedMenuDishTakesItsCompositionFromTheTableAndItsPortionFromTheScan() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());

        FoodItem dish = serviceWith(tableKnowingNasiLemak()).resolveNutrition(nasiLemak(), true);

        assertEquals(630, dish.calories(), 0.1);
        assertEquals("1 plate / ~300g", dish.estimatedPortion());
    }

    /**
     * Group and fried-ness are properties of the dish, so the curated row states
     * them. The table stores a boolean where the pipeline carries a method, and
     * deep-fried is the honest widening of it for scoring.
     */
    @Test
    void aCuratedRowStatesTheDishsOwnGroupAndFriedFlag() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());

        FoodItem food = serviceWith(tableKnowingNasiLemak("protein", true)).resolveNutrition(nasiLemak());

        assertEquals("protein", food.foodGroup(), "the curated group should beat the photo's guess");
        assertTrue(food.fried());
        assertEquals("deep-fried", food.cookingMethod());
    }

    /**
     * The table says nothing about how a dish that isn't fried was cooked — one
     * boolean cannot — so the model's reading stands rather than being erased.
     */
    @Test
    void anUnfriedCuratedRowStillBorrowsTheModelsCookingMethod() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());

        FoodItem food = serviceWith(tableKnowingNasiLemak()).resolveNutrition(nasiLemak());

        assertEquals("steamed", food.cookingMethod());
        assertTrue(!food.fried());
        assertEquals("local", food.source());
    }

    // ------------------------------------------------------ the retired path

    /**
     * {@link LocalFoodService} is the DB-backed lookup this rescue replaced. It
     * is still constructed and still has its own tests, but it is not in the
     * resolution path — its table ships empty, so restoring it would rescue
     * nothing while quietly reintroducing a second definition of "the local
     * answer". The mock is unstubbed, so any call is an interaction here and a
     * {@code null} dereference in production.
     */
    @Test
    void resolutionNeverConsultsTheDatabaseBackedLookup() {
        Mockito.when(usdaClient.lookup(anyString())).thenReturn(Optional.empty());
        AnalysisService service = serviceWith(tableKnowingNasiLemak());

        service.resolveNutrition(nasiLemak());
        service.resolveNutrition(nasiLemak(), true);

        Mockito.verifyNoInteractions(localFoodService);
    }
}
