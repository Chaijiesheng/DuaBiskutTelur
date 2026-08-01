package com.duabiskuttelur.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table only earns its place if it matches the names menus actually print —
 * with prices attached, in whichever romanisation the shop chose, and without
 * a general row stealing a dish from a more specific one.
 */
class LocalDishTableTest {

    private LocalDishTable table;

    @BeforeEach
    void setUp() {
        table = new LocalDishTable();
        table.load();
    }

    @Test
    void loadsTheShippedTable() {
        assertTrue(table.size() > 100, "expected the full alias set, got " + table.size());
    }

    /** Menu rows carry prices and portion notes; neither is part of the dish name. */
    @Test
    void matchesThroughPricesAndPunctuation() {
        for (String printed : List.of("Char Kway Teow (RM11.90)", "CHAR KWAY TEOW", "Char-Kway-Teow",
                "Char Koay Teow (RM 12)", "Char Kuey Teow")) {
            Optional<LocalDishTable.Entry> hit = table.lookup(printed);
            assertTrue(hit.isPresent(), "no match for " + printed);
            assertEquals("char kway teow", hit.get().canonical(), printed);
        }
    }

    /**
     * "Nasi lemak with fried chicken" contains "nasi lemak", and the plain row
     * would otherwise win on registration order — the longer key has to take it,
     * or every set meal collapses onto its base dish.
     */
    @Test
    void themostSpecificRowWins() {
        assertEquals("nasi lemak with fried chicken",
                table.lookup("Nasi Lemak with Fried Chicken (RM12.90)").orElseThrow().canonical());
        assertEquals("nasi lemak",
                table.lookup("Nasi Lemak (RM5.00)").orElseThrow().canonical());
        assertEquals("beef rendang with rice",
                table.lookup("Beef Rendang with White Rice").orElseThrow().canonical());
    }

    /** A key must be a whole word, so an unrelated dish can't borrow one. */
    @Test
    void doesNotMatchOnPartialWords() {
        assertFalse(table.lookup("Ricecakes imported").isPresent());
        assertFalse(table.lookup("Grilled lamb chop").isPresent());
        assertFalse(table.lookup("Caesar salad").isPresent());
    }

    /**
     * The five dishes that USDA got arithmetically wrong in the 30-dish
     * benchmark. Each now has to resolve here instead, and the numbers have to
     * be self-consistent — this is the fix for that failure, so it's the thing
     * worth asserting.
     */
    @Test
    void answersTheDishesUsdaGotWrong() {
        for (String dish : List.of("Nasi Lemak with Fried Chicken", "Wantan Mee (Dry)",
                "Murtabak Ayam", "Bak Kut Teh", "Nasi Kerabu Ayam Percik")) {
            LocalDishTable.Entry e = table.lookup(dish)
                    .orElseThrow(() -> new AssertionError("no local row for " + dish));
            var n = e.nutrients();
            double macroEnergy = n.protein() * 4 + n.carbs() * 4 + n.fat() * 9;
            assertTrue(Math.abs(macroEnergy - n.calories()) / n.calories() < 0.10,
                    "%s: macros imply %.0f kcal against %.0f stated".formatted(dish, macroEnergy, n.calories()));
            assertTrue(n.protein() < 60 && n.carbs() < 90, dish + " should be a served portion, not an ingredient");
        }
    }

    /** An empty table is what the ranking tests inject, so it must genuinely match nothing. */
    @Test
    void anEmptyTableNeverMatches() {
        assertFalse(new LocalDishTable(List.of()).lookup("Nasi Lemak").isPresent());
    }

    @Test
    void survivesNullAndBlankNames() {
        assertFalse(table.lookup(null).isPresent());
        assertFalse(table.lookup("   ").isPresent());
    }
}
