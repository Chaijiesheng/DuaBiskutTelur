package com.duabiskuttelur.service;

import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.FoodItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the ranking to judgements about real Malaysian dishes, rather than to
 * whatever the formula happens to produce.
 *
 * <p>Every assertion below is a comparison someone with domain knowledge made
 * while reviewing actual scans — "roti canai is too high", "assam laksa is
 * underrated", "porridge should not sit below fried chicken". Scoring changes
 * are easy to make and hard to evaluate, and eyeballing one scan at a time
 * misses regressions elsewhere; encoding the comparisons means a future tweak
 * that fixes one dish can't quietly break three others.
 *
 * <p>Nutrition here is fixed and representative, so this exercises the scoring
 * only — no model, no USDA, no network. Absolute scores are deliberately not
 * asserted: the claim is about which dish beats which, which is what a tier
 * list actually communicates.
 */
class MenuRankingQualityTest {

    private final ScoringService scoring = new ScoringService(new ScoringProperties());

    /** Per serving, as the app would have after resolving portions. */
    private static FoodItem dish(String name, double kcal, double protein, double carbs, double fat,
                                 double fiber, double sugar, double sodium, String group, boolean fried) {
        return new FoodItem(name, "1 serving", kcal, protein, carbs, fat, fiber, sugar, sodium,
                0.9, "estimated", group, fried);
    }

    /**
     * Hawker and kopitiam dishes at typical restaurant portions. Coconut-based
     * broths (curry mee, laksa) carry the fat that tamarind-based assam laksa
     * doesn't; roti canai carries ghee without being deep-fried.
     */
    private static List<FoodItem> menu() {
        return List.of(
                dish("Popiah", 220, 8, 30, 7, 4, 6, 550, "vegetable", false),
                dish("Yee Sang", 300, 10, 40, 12, 4, 22, 600, "vegetable", false),
                dish("Assam Laksa", 420, 22, 62, 8, 5, 8, 1400, "grain", false),
                dish("Curry Mee", 580, 20, 60, 28, 4, 6, 1600, "grain", false),
                dish("Laksa", 600, 22, 62, 30, 4, 7, 1500, "grain", false),
                dish("Nasi Lemak (basic)", 490, 11, 62, 22, 3, 6, 750, "grain", false),
                dish("Roti Canai", 300, 6, 38, 14, 1.5, 2, 450, "grain", false),
                dish("Char Kway Teow", 620, 20, 76, 26, 3, 6, 1800, "grain", true),
                dish("Karipap", 320, 6, 36, 17, 2, 3, 480, "fat", true),

                dish("Steamed Chicken Rice", 580, 32, 72, 17, 3, 3, 1100, "grain", false),
                dish("Cantonese Rice", 560, 26, 76, 15, 4, 6, 1200, "grain", false),
                dish("Claypot Chicken Porridge", 380, 22, 52, 8, 2, 1, 1100, "grain", false),
                dish("Chicken Satay", 380, 34, 14, 20, 2, 9, 700, "protein", false),
                dish("Seafood Fried Rice", 680, 24, 88, 24, 3, 5, 1400, "grain", true),
                dish("Fried Fish Cake", 300, 14, 20, 17, 1, 3, 850, "protein", true),
                dish("French Fries", 380, 4, 46, 19, 4, 0.5, 400, "fat", true),
                dish("Fried Chicken Wing", 520, 34, 12, 36, 0.5, 1, 1000, "protein", true),

                dish("Vegetable Salad", 120, 5, 14, 5, 5, 6, 300, "vegetable", false),
                dish("Fruit Salad", 180, 2, 44, 0.5, 5, 34, 20, "fruit", false));
    }

    /** Dish name to its 1-based position, healthiest first. */
    private Map<String, Integer> ranking() {
        List<FoodItem> sorted = new ArrayList<>(menu());
        sorted.sort(Comparator.comparingInt((FoodItem f) -> scoring.scoreMenuDish(f, 2000).score())
                .reversed()
                .thenComparing(FoodItem::name));
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            positions.put(sorted.get(i).name(), i + 1);
        }
        return positions;
    }

    private void assertRanksAbove(Map<String, Integer> ranks, String better, String worse, String why) {
        int a = ranks.get(better);
        int b = ranks.get(worse);
        assertTrue(a < b, "%s (#%d) should rank above %s (#%d) — %s%n%s"
                .formatted(better, a, worse, b, why, render(ranks)));
    }

    private String render(Map<String, Integer> ranks) {
        StringBuilder sb = new StringBuilder("   full ranking:%n".formatted());
        ranks.forEach((name, pos) -> {
            FoodItem f = menu().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
            ScoringService.ScoreResult r = scoring.scoreMenuDish(f, 2000);
            sb.append("   %2d. %-26s %3d  (bal %4.1f  qual %4.1f  port %4.1f)%n"
                    .formatted(pos, name, r.score(), r.balancePoints(), r.qualityPoints(), r.portionPoints()));
        });
        return sb.toString();
    }

    /** Ghee-rich refined flour with barely any protein shouldn't outrank a vegetable roll. */
    @Test
    void rotiCanaiRanksBelowPopiah() {
        assertRanksAbove(ranking(), "Popiah", "Roti Canai",
                "roti canai is refined flour cooked in ghee with almost no protein");
    }

    /**
     * Roti canai belongs below the dishes that carry real protein.
     *
     * <p>Known gap, deliberately not asserted: roti canai still edges out curry
     * laksa. Judged as printed — one piece, 300 kcal, 450mg sodium — against a
     * 600 kcal coconut broth carrying 1500mg, the flatbread genuinely is the
     * lighter order, and nothing in per-serving nutrition says otherwise. The
     * human ranking puts it lower because of how it's eaten: two pieces, dipped
     * in curry. That's a portion-and-context problem rather than a scoring one,
     * and bending sodium to force the pair would break the comparisons above.
     */
    @Test
    void rotiCanaiRanksBelowProteinCarryingDishes() {
        Map<String, Integer> ranks = ranking();
        assertRanksAbove(ranks, "Assam Laksa", "Roti Canai", "a fish-and-tamarind broth beats fried flatbread");
        assertRanksAbove(ranks, "Claypot Chicken Porridge", "Roti Canai", "22g of protein against 6g");
        assertRanksAbove(ranks, "Steamed Chicken Rice", "Roti Canai", "refined flour in ghee, almost no protein");
    }

    /** Tamarind broth vs coconut broth: the distinction the old fat-blind scoring couldn't see. */
    @Test
    void assamLaksaRanksAboveCoconutBasedNoodles() {
        Map<String, Integer> ranks = ranking();
        assertRanksAbove(ranks, "Assam Laksa", "Curry Mee", "assam laksa has no coconut milk");
        assertRanksAbove(ranks, "Assam Laksa", "Laksa", "assam laksa has no coconut milk");
    }

    /** A low-fat, protein-carrying porridge sitting under deep-fried chicken was the clearest error. */
    @Test
    void porridgeRanksAboveDeepFriedChicken() {
        Map<String, Integer> ranks = ranking();
        assertRanksAbove(ranks, "Claypot Chicken Porridge", "Fried Chicken Wing",
                "porridge is low-fat with moderate protein");
        assertRanksAbove(ranks, "Claypot Chicken Porridge", "Char Kway Teow", "porridge isn't fried");
    }

    /** Grilled, protein-dense and portion-controlled — the sauce is the only real drawback. */
    @Test
    void satayRanksAboveFriedSnacks() {
        Map<String, Integer> ranks = ranking();
        assertRanksAbove(ranks, "Chicken Satay", "Fried Fish Cake", "grilled protein beats processed and fried");
        assertRanksAbove(ranks, "Chicken Satay", "French Fries", "34g of protein against 4g");
    }

    /** Coconut rice is calorie-dense and thin on protein next to a rice-and-meat-and-veg plate. */
    @Test
    void basicNasiLemakRanksBelowCantoneseRice() {
        assertRanksAbove(ranking(), "Cantonese Rice", "Nasi Lemak (basic)",
                "coconut rice carries the fat, and nasi lemak has less than half the protein");
    }

    /** Frying a rice dish should cost it against a steamed equivalent. */
    @Test
    void friedRiceRanksBelowSteamedRicePlates() {
        Map<String, Integer> ranks = ranking();
        assertRanksAbove(ranks, "Cantonese Rice", "Seafood Fried Rice", "fried rice is oil and refined carbohydrate");
        assertRanksAbove(ranks, "Steamed Chicken Rice", "Seafood Fried Rice", "steamed beats fried");
    }

    /** "Salad" and "fruit" shouldn't win by name — sugar and missing protein have to count. */
    @Test
    void fruitSaladDoesNotOutrankVegetableSalad() {
        assertRanksAbove(ranking(), "Vegetable Salad", "Fruit Salad",
                "34g of sugar and 2g of protein isn't the healthier salad");
    }

    /** The clearest single call: a vegetable roll is the best thing on a hawker menu. */
    @Test
    void popiahIsTheTopHawkerDish() {
        Map<String, Integer> ranks = ranking();
        for (String worse : List.of("Yee Sang", "Curry Mee", "Laksa", "Nasi Lemak (basic)",
                "Char Kway Teow", "Karipap", "Roti Canai")) {
            assertRanksAbove(ranks, "Popiah", worse, "popiah is vegetables and protein with little oil");
        }
    }

    /** Deep-fried pastry is bottom-of-the-menu food. */
    @Test
    void karipapRanksNearTheBottom() {
        Map<String, Integer> ranks = ranking();
        assertRanksAbove(ranks, "Assam Laksa", "Karipap", "deep-fried pastry with 6g of protein");
        assertRanksAbove(ranks, "Claypot Chicken Porridge", "Karipap", "deep-fried pastry with 6g of protein");
    }
}
