package com.duabiskuttelur.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared food-name keyword lists, used both when persisting a meal
 * (AnalysisService precomputes the achievement-relevant booleans once, while
 * the full food list is already in memory) and when reading legacy rows that
 * predate those denormalized columns (AchievementsService falls back to
 * matching resultJson).
 *
 * <p>Matching is on whole words. It used to be a plain {@code contains}, which
 * quietly misfiled food that merely had a keyword buried inside it: "steak"
 * contains "tea" and "chocolate" contains "cola", so a steak dinner or a
 * chocolate bar logged on its own was recorded as a drinks-only meal. That
 * verdict is written into {@code beverage_only}/{@code coffee_only} at save
 * time, so it stuck — the wrong badge stayed unlocked for good (V8 repairs the
 * rows already affected).
 *
 * <p>Whole-word matching is strictly narrower than substring matching, so the
 * risk runs the other way now: real matches that used to be caught by accident
 * can be missed. Two allowances keep that in check — an optional plural suffix,
 * and compounds spelled out in the lists themselves ("cheesecake" would
 * otherwise stop counting as cake). Enumerating those is the point: what counts
 * as a dessert should be a decision in this file, not a side effect of one word
 * happening to contain another.
 */
public final class FoodKeywords {

    private FoodKeywords() {
    }

    /**
     * One category's keywords, pre-compiled into a single alternation. Built
     * once at class load — these lists are fixed, and matching runs per food
     * item per meal across a user's whole history when achievements are read.
     */
    public static final class KeywordSet {

        private final Pattern pattern;

        private KeywordSet(List<String> keywords) {
            // \b before each keyword is what stops interior matches ("tea"
            // inside "steak"); (?:e?s)? after it still allows the plural, so
            // "cookies" and "burgers" keep matching "cookie" and "burger".
            this.pattern = Pattern.compile(
                    keywords.stream().map(Pattern::quote).collect(
                            Collectors.joining("|", "\\b(?:", ")(?:e?s)?\\b")),
                    Pattern.CASE_INSENSITIVE);
        }

        static KeywordSet of(String... keywords) {
            return new KeywordSet(List.of(keywords));
        }

        boolean matches(String haystack) {
            return haystack != null && pattern.matcher(haystack).find();
        }
    }

    public static final KeywordSet PIZZA = KeywordSet.of("pizza");
    public static final KeywordSet FRIES = KeywordSet.of("fries", "french fry", "french fries");
    public static final KeywordSet FAST_FOOD = KeywordSet.of(
            "burger", "hamburger", "cheeseburger", "fried chicken", "nugget", "hot dog",
            "kfc", "mcdonald", "mcdonalds", "fries", "pizza");
    public static final KeywordSet DESSERT = KeywordSet.of(
            "cake", "cheesecake", "ice cream", "cookie", "chocolate", "donut", "doughnut",
            "pudding", "pastry", "brownie", "cupcake");
    public static final KeywordSet POTATO = KeywordSet.of(
            "potato", "potatoes", "hash brown", "fries", "french fry", "french fries");
    public static final KeywordSet CAKE = KeywordSet.of("cake", "cheesecake");
    public static final KeywordSet COFFEE = KeywordSet.of(
            "coffee", "latte", "espresso", "cappuccino", "americano", "mocha");
    public static final KeywordSet BEVERAGE = KeywordSet.of(
            "coffee", "latte", "espresso", "cappuccino", "americano", "mocha", "tea",
            "juice", "soda", "soft drink", "beer", "wine", "smoothie", "milkshake", "cola", "drink");

    public static boolean matchesAny(String haystack, KeywordSet keywords) {
        return keywords.matches(haystack);
    }

    /**
     * Whether every named food matches — the "this meal was only drinks" test.
     * An empty meal is not a drinks-only meal, matching what AnalysisService
     * writes at save time.
     */
    public static boolean allMatch(List<String> foodNames, KeywordSet keywords) {
        if (foodNames == null || foodNames.isEmpty()) {
            return false;
        }
        return foodNames.stream()
                .allMatch(name -> keywords.matches(name == null ? "" : name.toLowerCase(Locale.ROOT)));
    }
}
