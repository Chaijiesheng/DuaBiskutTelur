package com.duabiskuttelur.model;

import java.util.Locale;
import java.util.Set;

/**
 * The two closed vocabularies the vision model is asked to choose from, and the
 * server-side check that it actually did.
 *
 * <p>The prompt has always listed the eight food groups, but nothing verified
 * the answer. A model returning {@code "noodles"} or {@code "carbohydrate"}
 * instead of {@code "grain"} produced a value that
 * {@link com.duabiskuttelur.service.ScoringService}'s variety count treats as a
 * distinct group — so a plate of three differently-labelled starches could
 * score full variety points. Sending the vocabulary as a {@code responseSchema}
 * enum makes that unlikely at the source; normalizing here makes it impossible,
 * and also covers the barcode path and rows written before the schema existed.
 *
 * <p>Unknown values normalize to null rather than to a default group. A wrong
 * group is worse than no group: null is skipped by the variety count and the
 * vegetable bonus, which is the honest outcome when the model gave an answer
 * outside the vocabulary it was handed.
 */
public final class FoodTaxonomy {

    /** Mirrors the enum sent in the vision responseSchema. */
    public static final Set<String> FOOD_GROUPS = Set.of(
            "protein", "grain", "vegetable", "fruit", "dairy", "fat", "sweet", "beverage");

    /**
     * Cooking methods, replacing the old {@code fried} boolean. "Deep-fried" and
     * "stir-fried" are kept apart deliberately: a boolean forced the model to
     * call char kway teow and deep-fried chicken wings the same thing, and they
     * are not the same thing nutritionally — see
     * {@link com.duabiskuttelur.service.ScoringService}, which now penalizes
     * them differently.
     */
    public static final Set<String> COOKING_METHODS = Set.of(
            "deep-fried", "stir-fried", "grilled", "steamed", "boiled", "raw", "baked", "other");

    /**
     * What a menu item is, as read off the menu's own section headings. Null is
     * a legitimate value — a plate photo has no sections — and every caller
     * treats null as a main.
     */
    public static final Set<String> MENU_KINDS = Set.of("main", "addon", "drink");

    private FoodTaxonomy() {
    }

    /**
     * @return the kind in canonical lowercase form, or null if it is not one of
     *         the three. Normalised here for the same reason group and method
     *         are: it arrives as free text the model read off a photo, so
     *         nothing outside the closed vocabulary should reach the ranker.
     */
    public static String normalizeKind(String raw) {
        return normalizeAgainst(raw, MENU_KINDS);
    }

    /** @return the group in canonical lowercase form, or null if it is not one of the eight */
    public static String normalizeGroup(String raw) {
        return normalizeAgainst(raw, FOOD_GROUPS);
    }

    /** @return the method in canonical lowercase form, or null if it is not one of the eight */
    public static String normalizeMethod(String raw) {
        return normalizeAgainst(raw, COOKING_METHODS);
    }

    /**
     * True when this cooking method should trigger the fried penalty at all.
     * Kept here rather than in the scorer so the barcode path, the achievements
     * flags and the scorer cannot drift apart on what "fried" means.
     */
    public static boolean isFried(String cookingMethod) {
        return "deep-fried".equals(cookingMethod) || "stir-fried".equals(cookingMethod);
    }

    private static String normalizeAgainst(String raw, Set<String> vocabulary) {
        if (raw == null) {
            return null;
        }
        // Hyphen and space are interchangeable in practice ("deep fried",
        // "deep-fried"); everything else has to match the vocabulary exactly.
        String candidate = raw.strip().toLowerCase(Locale.ROOT).replace(' ', '-');
        return vocabulary.contains(candidate) ? candidate : null;
    }
}
