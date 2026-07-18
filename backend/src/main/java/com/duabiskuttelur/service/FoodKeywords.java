package com.duabiskuttelur.service;

import java.util.List;

/**
 * Shared food-name keyword lists, used both when persisting a meal
 * (AnalysisService precomputes the achievement-relevant booleans once, while
 * the full food list is already in memory) and when reading legacy rows that
 * predate those denormalized columns (AchievementsService falls back to
 * matching resultJson).
 */
final class FoodKeywords {
    private FoodKeywords() {
    }

    static final List<String> PIZZA = List.of("pizza");
    static final List<String> FRIES = List.of("fries", "french fry", "french fries");
    static final List<String> FAST_FOOD = List.of(
            "burger", "hamburger", "cheeseburger", "fried chicken", "nugget", "hot dog",
            "kfc", "mcdonald", "fries", "pizza");
    static final List<String> DESSERT = List.of(
            "cake", "ice cream", "cookie", "chocolate", "donut", "doughnut", "pudding",
            "pastry", "brownie", "cupcake");
    static final List<String> POTATO = List.of(
            "potato", "potatoes", "hash brown", "fries", "french fry", "french fries");
    static final List<String> CAKE = List.of("cake");
    static final List<String> COFFEE = List.of(
            "coffee", "latte", "espresso", "cappuccino", "americano", "mocha");
    static final List<String> BEVERAGE = List.of(
            "coffee", "latte", "espresso", "cappuccino", "americano", "mocha", "tea",
            "juice", "soda", "soft drink", "beer", "wine", "smoothie", "milkshake", "cola", "drink");

    static boolean matchesAny(String haystack, List<String> keywords) {
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
