package com.duabiskuttelur.service;

import com.duabiskuttelur.client.FeedbackClient;
import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.config.ScoringProperties;
import com.duabiskuttelur.model.FeedbackResult;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.model.Totals;
import com.duabiskuttelur.service.ScoringService.ScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates the friendly feedback section. Uses a second (text-only) AI call
 * when a provider key is configured; otherwise falls back to deterministic,
 * rule-based feedback so the app still works end to end. Both paths honor the
 * caller's requested language ("en", "zh", "ms") — the AI path via a prompt
 * instruction, the rule-based path via a translated string catalog below.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private static final Set<String> SUPPORTED_LANGS = Set.of("en", "zh", "ms");
    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "en", "English",
            "zh", "Simplified Chinese",
            "ms", "Malay (Bahasa Melayu)");

    private final FeedbackClient feedbackClient;
    private final AppProperties appProps;
    private final ScoringProperties scoringProps;

    public FeedbackService(FeedbackClient feedbackClient, AppProperties appProps, ScoringProperties scoringProps) {
        this.feedbackClient = feedbackClient;
        this.appProps = appProps;
        this.scoringProps = scoringProps;
    }

    private static final Set<String> SUPPORTED_GOALS = Set.of("weight_loss", "muscle_gain", "maintenance");
    private static final Map<String, String> GOAL_PROMPT_HINTS = Map.of(
            "weight_loss", "The user's goal is weight loss — emphasize portion control, satiety and calorie "
                    + "density in your feedback; still be encouraging, not restrictive.",
            "muscle_gain", "The user's goal is muscle gain — emphasize protein adequacy and training fuel; "
                    + "don't flag a moderate calorie surplus as a concern.");

    /** Calories/protein left in the day's targets, computed once in Java and never handed to the model to derive. */
    public record RemainingBudget(double calories, double protein) {
    }

    private static final double PORTION_FLAG_REMAINING_SHARE = 0.5;
    private static final double PORTION_FLAG_MIN_CALORIES = 300;
    private static final double PORTION_FLAG_FALLBACK_CALORIES = 900;
    private static final double LOW_PROTEIN_FLAG_MAX_GRAMS = 25;
    private static final double LOW_PROTEIN_FLAG_MIN_REMAINING = 50;
    private static final double LOW_PROTEIN_FLAG_FALLBACK_GRAMS = 20;

    public FeedbackResult feedbackFor(List<FoodItem> foods, Totals totals, ScoreResult score, String lang,
                                       String goal, RemainingBudget remaining, int dailyCalorieBudget) {
        String normalizedLang = SUPPORTED_LANGS.contains(lang) ? lang : "en";
        String normalizedGoal = goal != null && SUPPORTED_GOALS.contains(goal) ? goal : "maintenance";
        boolean flagPortion = shouldFlagPortion(normalizedGoal, totals, remaining);
        boolean flagLowProtein = shouldFlagLowProtein(normalizedGoal, totals, remaining);
        if (appProps.hasGeminiKey()) {
            try {
                return feedbackClient.generateFeedback(buildContext(foods, totals, score, normalizedLang,
                        normalizedGoal, remaining, flagPortion, flagLowProtein, dailyCalorieBudget));
            } catch (Exception e) {
                log.warn("AI feedback call failed, using rule-based fallback: {}", e.getMessage());
            }
        }
        return ruleBasedFeedback(foods, totals, score, normalizedLang, normalizedGoal, remaining, flagPortion, flagLowProtein);
    }

    /**
     * A meal eats too far into what's left of a weight-loss day. Falls back to a
     * fixed 900 kcal check when there's no "remaining" data (visitors have no
     * history), so the trigger still fires meaningfully rather than going silent.
     */
    private boolean shouldFlagPortion(String goal, Totals totals, RemainingBudget remaining) {
        if (!"weight_loss".equals(goal)) return false;
        if (remaining == null) return totals.calories() >= PORTION_FLAG_FALLBACK_CALORIES;
        return totals.calories() > remaining.calories() * PORTION_FLAG_REMAINING_SHARE
                && totals.calories() > PORTION_FLAG_MIN_CALORIES;
    }

    /**
     * A meal is light on protein for a muscle-gain day. Flat gram threshold, not
     * a percentage of what's left — a percentage over-fires early in the day when
     * "remaining" is close to the full target. Skips the check once there's not
     * much protein target left anyway (e.g. the last meal of the day).
     */
    private boolean shouldFlagLowProtein(String goal, Totals totals, RemainingBudget remaining) {
        if (!"muscle_gain".equals(goal)) return false;
        if (remaining == null) return totals.protein() < LOW_PROTEIN_FLAG_FALLBACK_GRAMS;
        return totals.protein() < LOW_PROTEIN_FLAG_MAX_GRAMS && remaining.protein() > LOW_PROTEIN_FLAG_MIN_REMAINING;
    }

    private String buildContext(List<FoodItem> foods, Totals totals, ScoreResult score, String lang, String goal,
                                 RemainingBudget remaining, boolean flagPortion, boolean flagLowProtein,
                                 int dailyCalorieBudget) {
        String foodLines = foods.stream()
                .map(f -> "- %s (%s): %.0f kcal, %.1fg protein, %.1fg carbs, %.1fg fat, %.1fg fiber, %.1fg sugar, %.0fmg sodium%s"
                        .formatted(f.name(), f.estimatedPortion(), f.calories(), f.protein(), f.carbs(),
                                f.fat(), f.fiber(), f.sugar(), f.sodium(), f.fried() ? " (fried)" : ""))
                .collect(Collectors.joining("\n"));
        String goalHint = GOAL_PROMPT_HINTS.getOrDefault(goal, "");
        String remainingLine = remaining != null
                ? "Remaining today: %.0f kcal, %.0fg protein.".formatted(remaining.calories(), remaining.protein())
                : "";
        String flagLine = flagPortion
                ? " This meal takes up more than half of what's left today — mention this as a gentle portion concern."
                : flagLowProtein
                ? " This meal is low in protein relative to what's still needed today — mention this as a gentle "
                        + "concern and suggest adding a protein source."
                : "";
        return """
                Foods:
                %s
                Totals: %.0f kcal, %.1fg protein, %.1fg carbs, %.1fg fat, %.1fg fiber, %.1fg sugar, %.0fmg sodium
                Score: %d/100 (grade %s)
                Score breakdown: balance %.0f/%d, nutrient quality %.0f/%d, portion %.0f/%d, variety %.0f/%d
                Daily sodium guideline: 2300mg. Daily calorie budget: %.0f kcal.
                %s
                %s%s
                Respond in %s. Keep all JSON field names and structure exactly as specified, \
                only the text values inside them should be in %s."""
                .formatted(foodLines,
                        totals.calories(), totals.protein(), totals.carbs(), totals.fat(),
                        totals.fiber(), totals.sugar(), totals.sodium(),
                        score.score(), score.grade(),
                        score.balancePoints(), scoringProps.getBalanceMaxPoints(),
                        score.qualityPoints(), scoringProps.getQualityMaxPoints(),
                        score.portionPoints(), scoringProps.getPortionMaxPoints(),
                        score.varietyPoints(), scoringProps.getVarietyMaxPoints(),
                        (double) dailyCalorieBudget,
                        goalHint,
                        remainingLine, flagLine,
                        LANGUAGE_NAMES.get(lang), LANGUAGE_NAMES.get(lang));
    }

    /**
     * Forces the deterministic path regardless of whether a Gemini key is
     * configured. Used for barcode-derived meals, where the nutrient numbers
     * are already exact — there's nothing left for a model to add, so calling
     * one would only add latency and cost.
     */
    public FeedbackResult ruleBasedFeedbackOnly(List<FoodItem> foods, Totals totals, ScoreResult score, String lang,
                                                 String goal, RemainingBudget remaining) {
        String normalizedLang = SUPPORTED_LANGS.contains(lang) ? lang : "en";
        String normalizedGoal = goal != null && SUPPORTED_GOALS.contains(goal) ? goal : "maintenance";
        boolean flagPortion = shouldFlagPortion(normalizedGoal, totals, remaining);
        boolean flagLowProtein = shouldFlagLowProtein(normalizedGoal, totals, remaining);
        return ruleBasedFeedback(foods, totals, score, normalizedLang, normalizedGoal, remaining, flagPortion, flagLowProtein);
    }

    FeedbackResult ruleBasedFeedback(List<FoodItem> foods, Totals totals, ScoreResult score, String lang, String goal,
                                      RemainingBudget remaining, boolean flagPortion, boolean flagLowProtein) {
        Strings s = STRINGS.get(lang);
        List<String> highlights = new ArrayList<>();
        List<String> concerns = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if ("muscle_gain".equals(goal) && totals.protein() >= 30) {
            highlights.add(s.greatProteinForGain.formatted(totals.protein()));
        } else if (totals.protein() >= 25) {
            highlights.add(s.goodProtein.formatted(totals.protein()));
        }
        boolean hasVeg = foods.stream().anyMatch(f ->
                "vegetable".equalsIgnoreCase(f.foodGroup()) || "fruit".equalsIgnoreCase(f.foodGroup()));
        // A drink has no "side of vegetables" to add — the suggestion only makes
        // sense when there's actual food being eaten, not just a beverage logged alone.
        boolean isBeverageOnly = foods.stream().allMatch(f -> "beverage".equalsIgnoreCase(f.foodGroup()));
        if (hasVeg) {
            highlights.add(s.hasVegOrFruit);
        }
        if (totals.fiber() >= scoringProps.getFiberBonusThresholdGrams()) {
            highlights.add(s.solidFiber.formatted(totals.fiber()));
        }
        if ("weight_loss".equals(goal) && totals.calories() <= 600) {
            highlights.add(s.lowCalorieGoodForLoss.formatted(totals.calories()));
        }
        if (highlights.isEmpty()) {
            highlights.add(s.loggedMeal);
        }

        if (totals.sodium() > scoringProps.getSodiumPenaltyThresholdMg()) {
            concerns.add(s.highSodium.formatted(totals.sodium(), totals.sodium() / 2300 * 100));
            suggestions.add(s.reduceSodium);
        }
        if (totals.sugar() > scoringProps.getSugarPenaltyThresholdGrams()) {
            concerns.add(s.highSugar.formatted(totals.sugar()));
            suggestions.add(s.reduceSugar);
        }
        if (foods.stream().anyMatch(FoodItem::fried)) {
            concerns.add(s.friedItems);
            suggestions.add(s.grillInstead);
        }
        if (!hasVeg && !isBeverageOnly) {
            concerns.add(s.lowFiber);
            suggestions.add(s.addVeg);
        }
        if (flagPortion) {
            concerns.add(remaining != null
                    ? s.bigPortionForLossWithRemaining.formatted(remaining.calories(), totals.calories())
                    : s.bigPortionForLoss.formatted(totals.calories()));
            suggestions.add(s.reducePortionForLoss);
        }
        if (flagLowProtein) {
            concerns.add(remaining != null
                    ? s.lowProteinForGainWithRemaining.formatted(totals.protein(), remaining.protein())
                    : s.lowProteinForGain.formatted(totals.protein()));
            suggestions.add(s.addProteinSource);
        }
        if (concerns.isEmpty()) {
            concerns.add(s.nothingMajor);
        }
        if (suggestions.isEmpty()) {
            suggestions.add(s.keepBalance);
        }

        String encouragement = switch (score.grade()) {
            case "A+" -> s.encourageAPlus;
            case "A" -> s.encourageA;
            case "B" -> s.encourageB;
            case "C" -> s.encourageC;
            default -> s.encourageD;
        };

        return new FeedbackResult(
                highlights.stream().limit(3).toList(),
                concerns.stream().limit(3).toList(),
                suggestions.stream().limit(3).toList(),
                encouragement);
    }

    /** Translated string set for the rule-based (no-Gemini-key) fallback path. */
    private record Strings(
            String goodProtein, String hasVegOrFruit, String solidFiber, String loggedMeal,
            String highSodium, String reduceSodium, String highSugar, String reduceSugar,
            String friedItems, String grillInstead, String lowFiber, String addVeg,
            String nothingMajor, String keepBalance,
            String encourageAPlus, String encourageA, String encourageB, String encourageC, String encourageD,
            String lowCalorieGoodForLoss, String bigPortionForLoss, String reducePortionForLoss,
            String greatProteinForGain, String lowProteinForGain, String addProteinSource,
            String bigPortionForLossWithRemaining, String lowProteinForGainWithRemaining) {
    }

    private static final Map<String, Strings> STRINGS = Map.of(
            "en", new Strings(
                    "Good protein content (%.0fg)",
                    "Contains vegetables or fruit",
                    "Solid fiber intake (%.0fg)",
                    "You logged your meal — tracking is the first step",
                    "High sodium — %.0fmg is over %.0f%% of the 2300mg daily limit in one meal",
                    "Ask for less sauce or kicap next time to cut sodium",
                    "Sugar is high at %.0fg for a single meal",
                    "Swap sweet drinks for plain water or teh o kosong",
                    "Deep-fried items add a lot of hidden fat",
                    "Choose grilled or steamed versions to save ~150-200 kcal",
                    "Low fiber relative to calories — no vegetables detected",
                    "Add a side of ulam, kangkung or cucumber to boost fiber",
                    "Nothing major — keep portions consistent",
                    "Keep the same balance tomorrow and stay hydrated",
                    "Perfect plate! This is exactly what balanced eating looks like.",
                    "Excellent meal — you're clearly making smart choices!",
                    "Solid meal! A couple of tweaks and you're in A territory.",
                    "Not bad — small swaps next time will move the needle fast.",
                    "Every meal is a fresh start. Try one of the suggestions next time!",
                    "Light meal (%.0f kcal) — fits comfortably in a deficit day",
                    "This meal alone is %.0f kcal — a big chunk of a deficit day",
                    "Try a smaller portion, or split it across two meals",
                    "%.0fg protein — right in range for muscle repair",
                    "Only %.0fg protein — a bit light for a muscle-gain day",
                    "Add a source like chicken, eggs, tofu or fish next time",
                    "You have %.0f kcal left today — this %.0f kcal meal would use most of it",
                    "Only %.0fg protein, with %.0fg still needed today — worth adding more"),
            "zh", new Strings(
                    "蛋白质含量不错（%.0fg）",
                    "含有蔬菜或水果",
                    "膳食纤维摄入充足（%.0fg）",
                    "你已经记录了这一餐 —— 记录是第一步",
                    "钠含量偏高 —— %.0fmg 已超过每日 2300mg 限额的 %.0f%%",
                    "下次可以要求少放酱料或酱油以减少钠摄入",
                    "这一餐的糖分偏高，达到 %.0fg",
                    "把含糖饮料换成白开水或无糖茶",
                    "油炸食品会带来大量隐藏脂肪",
                    "选择烤制或蒸煮的版本，可节省约 150-200 千卡",
                    "相对于卡路里而言膳食纤维偏低 —— 未检测到蔬菜",
                    "加一份生菜、空心菜或黄瓜来提升纤维摄入",
                    "没有大问题 —— 保持份量稳定即可",
                    "明天保持同样的均衡，并记得多喝水",
                    "完美的一餐！这正是均衡饮食该有的样子。",
                    "非常棒的一餐 —— 你显然在做出明智的选择！",
                    "不错的一餐！稍作调整就能达到 A 的水平。",
                    "还不错 —— 下次做些小调整，进步会很快。",
                    "每一餐都是新的开始，下次试试其中一个建议吧！",
                    "清淡的一餐（%.0f 千卡）—— 很适合减脂期",
                    "仅这一餐就有 %.0f 千卡 —— 在减脂日中占比不小",
                    "下次可以减少份量，或分成两餐吃",
                    "%.0fg 蛋白质 —— 非常适合肌肉修复",
                    "蛋白质只有 %.0fg —— 对增肌日来说偏少",
                    "下次可以加鸡肉、鸡蛋、豆腐或鱼类等蛋白质来源",
                    "你今天还剩 %.0f 千卡 —— 这一餐 %.0f 千卡就用掉了大半",
                    "蛋白质只有 %.0fg，今天还需要 %.0fg —— 值得再补充一些"),
            "ms", new Strings(
                    "Kandungan protein yang baik (%.0fg)",
                    "Mengandungi sayur-sayuran atau buah-buahan",
                    "Pengambilan serat yang baik (%.0fg)",
                    "Anda telah merekod makanan anda — merekod adalah langkah pertama",
                    "Natrium tinggi — %.0fmg melebihi %.0f%% daripada had harian 2300mg dalam satu makanan",
                    "Minta kurangkan sos atau kicap lain kali untuk kurangkan natrium",
                    "Gula tinggi pada %.0fg untuk satu hidangan",
                    "Tukar minuman manis kepada air kosong atau teh o kosong",
                    "Makanan yang digoreng menambah banyak lemak tersembunyi",
                    "Pilih versi panggang atau kukus untuk jimat ~150-200 kkal",
                    "Serat rendah berbanding kalori — tiada sayur-sayuran dikesan",
                    "Tambah ulam, kangkung atau timun untuk tingkatkan serat",
                    "Tiada isu besar — kekalkan saiz hidangan yang konsisten",
                    "Kekalkan imbangan yang sama esok dan minum air yang cukup",
                    "Hidangan yang sempurna! Beginilah rupa pemakanan seimbang.",
                    "Hidangan yang cemerlang — anda jelas membuat pilihan yang bijak!",
                    "Hidangan yang baik! Beberapa penyesuaian dan anda akan capai gred A.",
                    "Tidak buruk — sedikit perubahan lain kali akan memberi kesan cepat.",
                    "Setiap hidangan adalah permulaan baharu. Cuba salah satu cadangan lain kali!",
                    "Hidangan ringan (%.0f kkal) — sesuai untuk hari defisit kalori",
                    "Hidangan ini sahaja %.0f kkal — bahagian besar dalam hari defisit kalori",
                    "Cuba kurangkan saiz hidangan, atau bahagikan kepada dua waktu makan",
                    "%.0fg protein — sesuai untuk pembaikan otot",
                    "Protein hanya %.0fg — agak rendah untuk hari penambahan otot",
                    "Tambah sumber seperti ayam, telur, tauhu atau ikan lain kali",
                    "Anda masih ada %.0f kkal hari ini — hidangan %.0f kkal ini akan guna sebahagian besarnya",
                    "Protein hanya %.0fg, sedangkan %.0fg lagi diperlukan hari ini — patut tambah lagi"));
}
