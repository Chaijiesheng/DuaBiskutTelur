package com.duabiskuttelur.service;

import com.duabiskuttelur.persistence.LocalFoodEntity;
import com.duabiskuttelur.persistence.LocalFoodRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * USDA FoodData Central has no nasi lemak, char kway teow or teh tarik, so every
 * local dish resolves through the nearest generic it can find or falls to the
 * model's own estimate. This is the table that ends that, and the lookup has to
 * agree with the nutrition cache on dish identity or the two drift apart.
 */
class LocalFoodServiceTest {

    private final LocalFoodRepository repository = Mockito.mock(LocalFoodRepository.class);
    private final LocalFoodService service = new LocalFoodService(repository, new SimpleMeterRegistry());

    private static LocalFoodEntity nasiLemak() {
        LocalFoodEntity food = new LocalFoodEntity();
        food.setCanonicalName("nasi lemak");
        food.setDisplayName("Nasi lemak");
        food.setTypicalGrams(230);
        food.setCaloriesPer100g(180);
        food.setProteinPer100g(4);
        food.setCarbsPer100g(28);
        food.setFatPer100g(6);
        food.setFiberPer100g(1);
        food.setSugarPer100g(1);
        food.setSodiumPer100g(300);
        food.setFoodGroup("grain");
        food.setCookingMethod("steamed");
        food.setSource("curated");
        return food;
    }

    private void seed(String canonical, LocalFoodEntity food) {
        Mockito.when(repository.findByCanonicalName(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repository.findByAlias(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repository.findByCanonicalName(canonical)).thenReturn(Optional.of(food));
    }

    @Test
    void matchesTheDishHoweverTheMenuSpelledIt() {
        seed("nasi lemak", nasiLemak());

        // Canonicalized through the same function as the nutrition cache, so
        // these are one dish here exactly as they are one dish there.
        for (String written : new String[]{"Nasi Lemak", "nasi lemak", "NASI  LEMAK", "Nasi-Lemak"}) {
            assertTrue(service.lookup(written).isPresent(), "missed '" + written + "'");
        }
    }

    @Test
    void fallsBackToAnAliasWhenTheNameItselfIsNotTheCanonicalOne() {
        Mockito.when(repository.findByCanonicalName(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repository.findByAlias("nasi lemak bungkus")).thenReturn(Optional.of(nasiLemak()));

        // Malaysian menus routinely print one dish under several names, in
        // several languages, and the model returns whichever it read.
        assertTrue(service.lookup("Nasi Lemak Bungkus").isPresent());
    }

    @Test
    void reportsAHitAsTheHighestTrustSourceWithFullConfidence() {
        seed("nasi lemak", nasiLemak());

        NutritionCacheService.Resolved resolved = service.lookup("Nasi lemak").orElseThrow();

        assertEquals("local", resolved.source());
        // A transcribed composition figure is not an identification guess, and
        // the confidence the UI shows should not imply it might be one.
        assertEquals(1.0, resolved.confidence());
        assertEquals(180, resolved.caloriesPer100g());
        assertEquals(230, resolved.grams());
        assertEquals("steamed", resolved.cookingMethod());
    }

    @Test
    void publishesNoPortionBandBecauseAPublishedServingIsNotAGuessOffAPhoto() {
        seed("nasi lemak", nasiLemak());

        NutritionCacheService.Resolved resolved = service.lookup("Nasi lemak").orElseThrow();

        assertEquals(resolved.grams(), resolved.gramsLow());
        assertEquals(resolved.grams(), resolved.gramsHigh());
    }

    @Test
    void missesQuietlyWhenTheDatabaseIsEmpty() {
        Mockito.when(repository.findByCanonicalName(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repository.findByAlias(Mockito.anyString())).thenReturn(Optional.empty());

        // The shipped state until data is curated in. It has to be an ordinary
        // miss that falls through to USDA, not an error and not a short-circuit.
        assertFalse(service.lookup("Char kway teow").isPresent());
    }

    @Test
    void doesNotQueryAtAllForANameThatCanonicalizesToNothing() {
        assertFalse(service.lookup("   ").isPresent());
        assertFalse(service.lookup(null).isPresent());
        Mockito.verify(repository, Mockito.never()).findByCanonicalName(Mockito.anyString());
    }
}
