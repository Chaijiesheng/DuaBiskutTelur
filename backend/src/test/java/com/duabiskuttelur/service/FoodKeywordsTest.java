package com.duabiskuttelur.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matching used to be a plain {@code contains}, which misfiled anything with a
 * keyword buried inside it. The verdict is written into the row when a meal is
 * saved, so a wrong answer here doesn't just show a wrong badge once — it is
 * stored, and stays wrong until something repairs it.
 */
class FoodKeywordsTest {

    // --- the false positives that motivated the change ---

    @Test
    void aWordMerelyContainingAKeywordIsNotAMatch() {
        // "steak" contains "tea"; "chocolate" contains "cola". Both used to be
        // logged as drinks-only meals, unlocking Liquid Dinner for a steak.
        assertFalse(FoodKeywords.matchesAny("steak", FoodKeywords.BEVERAGE), "steak is not tea");
        assertFalse(FoodKeywords.matchesAny("grilled steak", FoodKeywords.BEVERAGE));
        assertFalse(FoodKeywords.matchesAny("chocolate", FoodKeywords.BEVERAGE), "chocolate is not cola");
        assertFalse(FoodKeywords.matchesAny("dark chocolate bar", FoodKeywords.BEVERAGE));
    }

    @Test
    void realDrinksStillMatch() {
        assertTrue(FoodKeywords.matchesAny("teh o ais / iced tea", FoodKeywords.BEVERAGE));
        assertTrue(FoodKeywords.matchesAny("iced latte", FoodKeywords.COFFEE));
        assertTrue(FoodKeywords.matchesAny("white coffee", FoodKeywords.COFFEE));
        assertTrue(FoodKeywords.matchesAny("orange juice", FoodKeywords.BEVERAGE));
        assertTrue(FoodKeywords.matchesAny("coca cola", FoodKeywords.BEVERAGE));
    }

    // --- the regression risk the change introduces ---

    /**
     * Whole-word matching is narrower than substring matching, so the danger
     * runs the other way now: things that used to match by accident can start
     * being missed. Plurals are the common case.
     */
    @Test
    void pluralsStillMatchTheirSingularKeyword() {
        assertTrue(FoodKeywords.matchesAny("chocolate chip cookies", FoodKeywords.DESSERT), "cookies");
        assertTrue(FoodKeywords.matchesAny("chicken nuggets", FoodKeywords.FAST_FOOD), "nuggets");
        assertTrue(FoodKeywords.matchesAny("beef burgers", FoodKeywords.FAST_FOOD), "burgers");
        assertTrue(FoodKeywords.matchesAny("brownies", FoodKeywords.DESSERT), "brownies");
        assertTrue(FoodKeywords.matchesAny("mashed potatoes", FoodKeywords.POTATO), "potatoes");
    }

    /**
     * A compound where the keyword isn't its own word has to be listed
     * explicitly — cheesecake is genuinely cake, and only matched before
     * because "cake" happened to be a substring.
     */
    @Test
    void compoundsThatAreGenuinelyTheThingAreListedExplicitly() {
        assertTrue(FoodKeywords.matchesAny("new york cheesecake", FoodKeywords.CAKE));
        assertTrue(FoodKeywords.matchesAny("new york cheesecake", FoodKeywords.DESSERT));
    }

    /** And one that isn't: a pancake is not a cake, and used to be counted as one. */
    @Test
    void compoundsThatMerelyLookLikeTheThingAreNot() {
        assertFalse(FoodKeywords.matchesAny("pancakes with syrup", FoodKeywords.CAKE));
    }

    // --- the "whole meal was drinks" helper the repair migration relies on ---

    @Test
    void allMatchNeedsEveryItemToQualify() {
        assertTrue(FoodKeywords.allMatch(List.of("Teh tarik / milk tea", "Kopi O / black coffee"),
                FoodKeywords.BEVERAGE));
        assertFalse(FoodKeywords.allMatch(List.of("Iced latte", "Croissant"), FoodKeywords.BEVERAGE),
                "one solid item means it wasn't a drinks-only meal");
    }

    @Test
    void anEmptyMealIsNotADrinksOnlyMeal() {
        assertFalse(FoodKeywords.allMatch(List.of(), FoodKeywords.BEVERAGE));
        assertFalse(FoodKeywords.allMatch(null, FoodKeywords.BEVERAGE));
    }

    /** The exact shape the old matcher got wrong, end to end. */
    @Test
    void aSteakDinnerIsNotADrinksOnlyMeal() {
        assertFalse(FoodKeywords.allMatch(List.of("Steak"), FoodKeywords.BEVERAGE));
        assertFalse(FoodKeywords.allMatch(List.of("Chocolate"), FoodKeywords.BEVERAGE));
    }
}
