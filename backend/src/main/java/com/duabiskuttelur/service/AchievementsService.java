package com.duabiskuttelur.service;

import com.duabiskuttelur.model.AchievementsResponse;
import com.duabiskuttelur.model.AnalysisResponse;
import com.duabiskuttelur.model.Badge;
import com.duabiskuttelur.model.FoodItem;
import com.duabiskuttelur.persistence.MealAnalysisEntity;
import com.duabiskuttelur.persistence.MealAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Computes the full badge catalog from a user's meal history on every read
 * (no persistence, no unlock timestamps — mirrors the original design).
 * Secret badges are simply omitted from the response while locked; the
 * frontend never even learns they exist until they unlock. Descriptions and
 * XP are likewise withheld until unlocked.
 */
@Service
public class AchievementsService {

    public enum Category { MILESTONE, STREAK, FOOD, AI, HEALTH, FUNNY, SECRET }

    private record BadgeDef(String id, String label, String icon, String description,
                             Category category, int xp, boolean secret, Predicate<Stats> condition) {
    }

    private record Stats(
            int totalMeals,
            int currentStreak,
            int totalActiveDays,
            int maxMealsInADay,
            int vegetableMealCount,
            int vegetableOccurrences,
            int fruitStreak,
            int healthyStreak,
            int breakfastStreak,
            boolean midnightMealLogged,
            int midnightStreak,
            boolean coffeeOnlyMealExists,
            boolean liquidOnlyMealExists,
            int pizzaMeals,
            int friesMeals,
            int fastFoodMeals,
            int dessertMeals,
            int potatoMeals,
            boolean cakeLogged,
            boolean balancedEventually,
            long activeDaysLastYear) {
    }

    private static final List<BadgeDef> CATALOG = List.of(
            new BadgeDef("it_begins", "It Begins", "🍽️",
                    "You logged your first meal. The food diary officially has receipts.",
                    Category.MILESTONE, 10, false, s -> s.totalMeals() >= 1),
            new BadgeDef("food_paparazzi", "Food Paparazzi", "🥉",
                    "Your camera roll is becoming a restaurant menu.",
                    Category.MILESTONE, 20, false, s -> s.totalMeals() >= 10),
            new BadgeDef("camera_eats_first", "Camera Eats First", "📷",
                    "You've mastered the ancient art of taking food photos before eating.",
                    Category.MILESTONE, 35, false, s -> s.totalMeals() >= 25),
            new BadgeDef("certified_snack_detective", "Certified Snack Detective", "🥈",
                    "Nothing edible escapes your lens anymore.",
                    Category.MILESTONE, 50, false, s -> s.totalMeals() >= 50),
            new BadgeDef("human_barcode_scanner", "Human Barcode Scanner", "🥇",
                    "Even your fries know they're being watched.",
                    Category.MILESTONE, 100, false, s -> s.totalMeals() >= 100),
            new BadgeDef("data_goblin", "Data Goblin", "📈",
                    "Numbers make your stomach happy too.",
                    Category.MILESTONE, 25, false, s -> s.totalActiveDays() >= 7),

            new BadgeDef("barely_trying", "Barely Trying", "🔥",
                    "Three days? That's longer than some New Year's resolutions.",
                    Category.STREAK, 15, false, s -> s.currentStreak() >= 3),
            new BadgeDef("still_alive", "Still Alive", "⚡",
                    "You came back every day. We're starting to believe in you.",
                    Category.STREAK, 30, false, s -> s.currentStreak() >= 7),
            new BadgeDef("is_this_discipline", "Is This... Discipline?", "🏆",
                    "Consistency unlocked. Who even are you now?",
                    Category.STREAK, 100, false, s -> s.currentStreak() >= 30),
            new BadgeDef("unstoppable_ish", "Unstoppable-ish", "👑",
                    "At this point, logging meals is a lifestyle.",
                    Category.STREAK, 200, false, s -> s.currentStreak() >= 60),
            new BadgeDef("cant_stop_wont_stop", "Can't Stop, Won't Stop", "💀",
                    "Your dedication is mildly concerning... in a good way.",
                    Category.STREAK, 400, false, s -> s.currentStreak() >= 100),

            new BadgeDef("nutrition_fbi", "Nutrition FBI", "🕵️",
                    "No calorie left behind. Investigation complete.",
                    Category.FUNNY, 30, false, s -> s.maxMealsInADay() >= 3),
            new BadgeDef("midnight_criminal", "Midnight Criminal", "🌙",
                    "Calories don't know what time it is anyway.",
                    Category.FUNNY, 15, false, Stats::midnightMealLogged),
            new BadgeDef("coffee_counts", "Coffee Counts... Right?", "☕",
                    "Breakfast is apparently a social construct now.",
                    Category.FUNNY, 15, false, Stats::coffeeOnlyMealExists),
            // 🥤 not 🍺 — alcohol imagery is a poor fit for this app's largely
            // Muslim home market, and the badge is about any liquid-only meal.
            new BadgeDef("liquid_dinner", "Liquid Dinner", "🥤",
                    "We're pretending that's balanced.",
                    Category.FUNNY, 20, false, Stats::liquidOnlyMealExists),

            new BadgeDef("rabbit_mode", "Rabbit Mode", "🥬",
                    "Ate vegetables on purpose. Bold move.",
                    Category.HEALTH, 20, false, s -> s.vegetableMealCount() >= 5),
            new BadgeDef("an_apple_a_day", "An Apple a Day", "🍎",
                    "Your doctor is intrigued. Keep it up.",
                    Category.HEALTH, 35, false, s -> s.fruitStreak() >= 5),
            new BadgeDef("suspiciously_healthy", "Suspiciously Healthy", "🥦",
                    "Are you feeling okay? Who replaced you?",
                    Category.HEALTH, 40, false, s -> s.healthyStreak() >= 7),
            new BadgeDef("leaf_me_alone", "Leaf Me Alone", "🌱",
                    "Vegetables have officially become friends instead of decorations.",
                    Category.HEALTH, 60, false, s -> s.vegetableOccurrences() >= 20),

            new BadgeDef("cake_happens", "Cake Happens", "🎂",
                    "Sometimes happiness comes with frosting. No regrets.",
                    Category.FOOD, 10, false, Stats::cakeLogged),
            new BadgeDef("pizza_again", "Pizza Again?", "🍕",
                    "We're not judging... much. Pizza might be your best friend now.",
                    Category.FOOD, 25, false, s -> s.pizzaMeals() >= 5),
            new BadgeDef("calorie_collector", "Calorie Collector", "🍔",
                    "Every bite has been documented for the record.",
                    Category.FOOD, 25, false, s -> s.fastFoodMeals() >= 5),
            new BadgeDef("fries_before_guys", "Fries Before Guys", "🍟",
                    "Fries detected. Priorities confirmed.",
                    Category.FOOD, 30, false, s -> s.friesMeals() >= 10),
            new BadgeDef("sugar_speedrun", "Sugar Speedrun", "🍩",
                    "Instant happiness unlocked. Your sweet tooth wins again.",
                    Category.FOOD, 30, false, s -> s.dessertMeals() >= 10),
            new BadgeDef("potato_enthusiast", "Potato Enthusiast", "🥔",
                    "Potatoes keep appearing. We see the pattern.",
                    Category.FOOD, 30, false, s -> s.potatoMeals() >= 10),
            new BadgeDef("breakfast_club", "Breakfast Club", "🍳",
                    "Mornings are finally winning.",
                    Category.FOOD, 45, false, s -> s.breakfastStreak() >= 7),

            new BadgeDef("we_saw_that", "We Saw That", "👀",
                    "Sneaky... but not sneaky enough. The fridge has reported suspicious activity.",
                    Category.SECRET, 40, true, s -> s.midnightStreak() >= 3),
            new BadgeDef("balanced_eventually", "Balanced... Eventually", "🎭",
                    "A beautiful recovery attempt. Your nutrition report is confused.",
                    Category.SECRET, 35, true, Stats::balancedEventually),
            new BadgeDef("built_different", "Built Different", "💯",
                    "At this point, you're basically part of the app. We should name a feature after you.",
                    Category.SECRET, 500, true, s -> s.activeDaysLastYear() >= 330));

    /** id -> [label, description], one map per non-English language. English uses CATALOG's own text. */
    private static final Map<String, Map<String, String[]>> BADGE_I18N = Map.of(
            "en", Map.of(),
            "zh", Map.ofEntries(
                    Map.entry("it_begins", new String[]{"迈出第一步", "你记录了第一餐。饮食日记正式有据可查了。"}),
                    Map.entry("food_paparazzi", new String[]{"美食狗仔队", "你的相册正在变成一份餐厅菜单。"}),
                    Map.entry("camera_eats_first", new String[]{"先拍照后吃饭", "你已掌握了吃饭前先拍照的古老技艺。"}),
                    Map.entry("certified_snack_detective", new String[]{"认证零食侦探", "任何能吃的东西都逃不过你的镜头了。"}),
                    Map.entry("human_barcode_scanner", new String[]{"人体条码扫描仪", "连你的薯条都知道自己正被盯着。"}),
                    Map.entry("data_goblin", new String[]{"数据小怪兽", "数字也能让你的胃感到开心。"}),
                    Map.entry("barely_trying", new String[]{"勉强坚持中", "三天？这比某些新年愿望坚持得还久。"}),
                    Map.entry("still_alive", new String[]{"依然健在", "你每天都回来了。我们开始相信你了。"}),
                    Map.entry("is_this_discipline", new String[]{"这是……自律吗？", "自律已解锁。你现在到底是谁？"}),
                    Map.entry("unstoppable_ish", new String[]{"势不可挡（差不多）", "到这个阶段，记录饮食已经成了一种生活方式。"}),
                    Map.entry("cant_stop_wont_stop", new String[]{"停不下来", "你的坚持有点让人担心……不过是好的那种。"}),
                    Map.entry("nutrition_fbi", new String[]{"营养 FBI", "一卡都不放过，调查完毕。"}),
                    Map.entry("midnight_criminal", new String[]{"深夜罪犯", "反正卡路里也不知道现在几点。"}),
                    Map.entry("coffee_counts", new String[]{"咖啡也算一餐吧？", "早餐显然已经变成了一种社会概念。"}),
                    Map.entry("liquid_dinner", new String[]{"液体晚餐", "我们就当这也算均衡饮食吧。"}),
                    Map.entry("rabbit_mode", new String[]{"兔子模式", "特意吃了蔬菜，勇气可嘉。"}),
                    Map.entry("an_apple_a_day", new String[]{"一天一苹果", "你的医生很感兴趣，继续保持。"}),
                    Map.entry("suspiciously_healthy", new String[]{"健康得可疑", "你还好吗？是不是被人调包了？"}),
                    Map.entry("leaf_me_alone", new String[]{"叶不离手", "蔬菜正式从摆盘装饰变成了你的朋友。"}),
                    Map.entry("cake_happens", new String[]{"蛋糕万岁", "有时候幸福就是带着糖霜的，不后悔。"}),
                    Map.entry("pizza_again", new String[]{"又是披萨？", "我们不予置评……披萨可能已经是你最好的朋友了。"}),
                    Map.entry("calorie_collector", new String[]{"卡路里收集家", "每一口都被记录在案。"}),
                    Map.entry("fries_before_guys", new String[]{"薯条优先", "检测到薯条，优先级已确认。"}),
                    Map.entry("sugar_speedrun", new String[]{"糖分速通", "瞬间快乐已解锁，你的甜食瘾又赢了。"}),
                    Map.entry("potato_enthusiast", new String[]{"土豆爱好者", "土豆一直在出现，我们注意到规律了。"}),
                    Map.entry("breakfast_club", new String[]{"早餐俱乐部", "早晨终于开始赢了。"}),
                    Map.entry("we_saw_that", new String[]{"我们看到了", "偷偷摸摸……但还不够偷偷摸摸。冰箱已举报可疑活动。"}),
                    Map.entry("balanced_eventually", new String[]{"最终还是……平衡了", "一次华丽的补救。你的营养报告表示很困惑。"}),
                    Map.entry("built_different", new String[]{"与众不同", "到这个地步，你基本上已经是这个应用的一部分了。我们应该用你的名字命名一个功能。"})),
            "ms", Map.ofEntries(
                    Map.entry("it_begins", new String[]{"Ia Bermula", "Anda telah merekod makanan pertama anda. Diari makanan kini rasmi mempunyai resit."}),
                    Map.entry("food_paparazzi", new String[]{"Paparazzi Makanan", "Album kamera anda semakin menjadi menu restoran."}),
                    Map.entry("camera_eats_first", new String[]{"Kamera Makan Dahulu", "Anda telah menguasai seni kuno mengambil gambar makanan sebelum makan."}),
                    Map.entry("certified_snack_detective", new String[]{"Detektif Snek Bertauliah", "Tiada apa yang boleh dimakan terlepas daripada lensa anda lagi."}),
                    Map.entry("human_barcode_scanner", new String[]{"Pengimbas Barkod Manusia", "Malah kentang goreng anda pun tahu ia sedang diperhatikan."}),
                    Map.entry("data_goblin", new String[]{"Goblin Data", "Nombor turut membuat perut anda gembira."}),
                    Map.entry("barely_trying", new String[]{"Baru Nak Mula", "Tiga hari? Itu lebih lama daripada sesetengah azam Tahun Baharu."}),
                    Map.entry("still_alive", new String[]{"Masih Hidup", "Anda kembali setiap hari. Kami mula percaya pada anda."}),
                    Map.entry("is_this_discipline", new String[]{"Adakah Ini... Disiplin?", "Konsistensi dibuka. Siapa sebenarnya anda sekarang?"}),
                    Map.entry("unstoppable_ish", new String[]{"Tidak Boleh Dihalang (Lebih Kurang)", "Pada tahap ini, merekod makanan sudah menjadi gaya hidup."}),
                    Map.entry("cant_stop_wont_stop", new String[]{"Tak Boleh Berhenti", "Dedikasi anda agak membimbangkan... dalam erti kata yang baik."}),
                    Map.entry("nutrition_fbi", new String[]{"FBI Pemakanan", "Tiada satu kalori pun tertinggal. Siasatan selesai."}),
                    Map.entry("midnight_criminal", new String[]{"Penjenayah Tengah Malam", "Kalori pun tak tahu pukul berapa sekarang."}),
                    Map.entry("coffee_counts", new String[]{"Kopi Dikira... Betul?", "Sarapan nampaknya kini hanya konsep sosial."}),
                    Map.entry("liquid_dinner", new String[]{"Makan Malam Cecair", "Kita anggap sahaja itu seimbang."}),
                    Map.entry("rabbit_mode", new String[]{"Mod Arnab", "Makan sayur dengan sengaja. Berani."}),
                    Map.entry("an_apple_a_day", new String[]{"Sebiji Epal Sehari", "Doktor anda tertarik. Teruskan."}),
                    Map.entry("suspiciously_healthy", new String[]{"Sihat Secara Mencurigakan", "Anda okay tak? Siapa yang gantikan anda?"}),
                    Map.entry("leaf_me_alone", new String[]{"Biar Daun Ini", "Sayur-sayuran kini rasmi menjadi kawan, bukan hiasan sahaja."}),
                    Map.entry("cake_happens", new String[]{"Kek Berlaku", "Kadangkala kebahagiaan datang berserta aising. Tiada penyesalan."}),
                    Map.entry("pizza_again", new String[]{"Pizza Lagi?", "Kami tidak menghakimi... sangat. Pizza mungkin kawan baik anda sekarang."}),
                    Map.entry("calorie_collector", new String[]{"Pengumpul Kalori", "Setiap suapan telah direkod untuk rekod rasmi."}),
                    Map.entry("fries_before_guys", new String[]{"Kentang Goreng Dahulu", "Kentang goreng dikesan. Keutamaan disahkan."}),
                    Map.entry("sugar_speedrun", new String[]{"Larian Pantas Gula", "Kebahagiaan segera dibuka. Gigi manis anda menang lagi."}),
                    Map.entry("potato_enthusiast", new String[]{"Peminat Kentang", "Kentang terus muncul. Kami perasan corak ini."}),
                    Map.entry("breakfast_club", new String[]{"Kelab Sarapan", "Waktu pagi akhirnya menang."}),
                    Map.entry("we_saw_that", new String[]{"Kami Nampak Itu", "Senyap-senyap... tapi tak cukup senyap. Peti sejuk melaporkan aktiviti mencurigakan."}),
                    Map.entry("balanced_eventually", new String[]{"Seimbang... Akhirnya", "Percubaan pemulihan yang cantik. Laporan pemakanan anda keliru."}),
                    Map.entry("built_different", new String[]{"Dibina Berbeza", "Pada tahap ini, anda pada dasarnya sebahagian daripada aplikasi ini. Kami patut namakan satu ciri sempena anda."})));

    private final MealAnalysisRepository repository;
    private final ObjectMapper mapper;

    public AchievementsService(MealAnalysisRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public AchievementsResponse forUser(Long userId) {
        return forUser(userId, "en");
    }

    public AchievementsResponse forUser(Long userId, String lang) {
        String normalizedLang = BADGE_I18N.containsKey(lang) ? lang : "en";
        List<MealAnalysisEntity> entries = repository.findByUserIdOrderByCreatedAtDesc(userId);
        Stats stats = computeStats(entries);

        List<Badge> badges = CATALOG.stream()
                .map(def -> toBadge(def, stats, normalizedLang))
                .filter(Objects::nonNull)
                .toList();

        return new AchievementsResponse(stats.totalMeals(), stats.currentStreak(), badges);
    }

    private Badge toBadge(BadgeDef def, Stats stats, String lang) {
        boolean unlocked = def.condition().test(stats);
        if (def.secret() && !unlocked) {
            // Secret + still locked: don't reveal it exists at all.
            return null;
        }
        String[] translated = BADGE_I18N.get(lang).getOrDefault(def.id(), null);
        String label = translated != null ? translated[0] : def.label();
        String description = translated != null ? translated[1] : def.description();
        return new Badge(def.id(), label, def.icon(), def.category().name(), unlocked, def.secret(),
                unlocked ? description : null, unlocked ? def.xp() : null);
    }

    private Stats computeStats(List<MealAnalysisEntity> entriesDesc) {
        ZoneId zone = ZoneId.systemDefault();

        TreeSet<LocalDate> allDays = new TreeSet<>();
        Map<LocalDate, Integer> mealsPerDay = new HashMap<>();
        Map<LocalDate, List<MealAnalysisEntity>> byDay = new HashMap<>();
        Set<LocalDate> fruitDays = new HashSet<>();
        Set<LocalDate> healthyDays = new HashSet<>();
        Set<LocalDate> breakfastDays = new HashSet<>();
        Set<LocalDate> midnightDays = new HashSet<>();

        int vegetableMealCount = 0;
        int vegetableOccurrences = 0;
        int pizzaMeals = 0;
        int friesMeals = 0;
        int fastFoodMeals = 0;
        int dessertMeals = 0;
        int potatoMeals = 0;
        boolean cakeLogged = false;
        boolean coffeeOnlyMealExists = false;
        boolean liquidOnlyMealExists = false;
        boolean midnightMealLogged = false;

        for (MealAnalysisEntity e : entriesDesc) {
            LocalDateTime dt = e.getCreatedAt().atZone(zone).toLocalDateTime();
            LocalDate day = dt.toLocalDate();
            allDays.add(day);
            mealsPerDay.merge(day, 1, Integer::sum);
            byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(e);

            int hour = dt.getHour();
            if (hour < 5) {
                midnightDays.add(day);
                midnightMealLogged = true;
            }
            if (hour >= 5 && hour < 11) {
                breakfastDays.add(day);
            }
            if ("A".equals(e.getGrade()) || "A+".equals(e.getGrade())) {
                healthyDays.add(day);
            }

            // Keyword facts always read the summary column (comma-joined food
            // names, populated for every row since day one) instead of parsing
            // resultJson — same substring-search this file already relied on
            // for balancedEventually below.
            String name = e.getSummary() == null ? "" : e.getSummary().toLowerCase(Locale.ROOT);
            if (FoodKeywords.matchesAny(name, FoodKeywords.PIZZA)) {
                pizzaMeals++;
            }
            if (FoodKeywords.matchesAny(name, FoodKeywords.FRIES)) {
                friesMeals++;
            }
            if (FoodKeywords.matchesAny(name, FoodKeywords.FAST_FOOD)) {
                fastFoodMeals++;
            }
            if (FoodKeywords.matchesAny(name, FoodKeywords.DESSERT)) {
                dessertMeals++;
            }
            if (FoodKeywords.matchesAny(name, FoodKeywords.POTATO)) {
                potatoMeals++;
            }
            if (FoodKeywords.matchesAny(name, FoodKeywords.CAKE)) {
                cakeLogged = true;
            }

            // Vegetable count / fruit presence / drink-only need per-item data
            // that summary alone can't give (an "every item matches" check, and
            // a distinct food-group count) — read from the columns
            // AnalysisService denormalizes at save time, falling back to
            // resultJson only for rows saved before those columns existed.
            MealFacts facts = mealFacts(e);
            vegetableOccurrences += facts.vegetableCount();
            if (facts.vegetableCount() > 0) {
                vegetableMealCount++;
            }
            if (facts.hasFruit()) {
                fruitDays.add(day);
            }
            if (facts.coffeeOnly()) {
                coffeeOnlyMealExists = true;
            }
            if (facts.beverageOnly()) {
                liquidOnlyMealExists = true;
            }
        }

        int maxMealsInADay = mealsPerDay.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        boolean balancedEventually = byDay.values().stream().anyMatch(dayEntries -> {
            List<MealAnalysisEntity> sorted = dayEntries.stream()
                    .sorted(Comparator.comparing(MealAnalysisEntity::getCreatedAt))
                    .toList();
            boolean sawDessert = false;
            for (MealAnalysisEntity e : sorted) {
                String s = e.getSummary() == null ? "" : e.getSummary().toLowerCase(Locale.ROOT);
                boolean isSalad = s.contains("salad");
                if (isSalad && sawDessert) {
                    return true;
                }
                if (FoodKeywords.matchesAny(s, FoodKeywords.DESSERT)) {
                    sawDessert = true;
                }
            }
            return false;
        });

        LocalDate yearAgo = LocalDate.now(zone).minusDays(365);
        long activeDaysLastYear = allDays.stream().filter(d -> !d.isBefore(yearAgo)).count();

        return new Stats(
                entriesDesc.size(),
                consecutiveDayStreak(allDays, zone),
                allDays.size(),
                maxMealsInADay,
                vegetableMealCount,
                vegetableOccurrences,
                consecutiveDayStreak(fruitDays, zone),
                consecutiveDayStreak(healthyDays, zone),
                consecutiveDayStreak(breakfastDays, zone),
                midnightMealLogged,
                consecutiveDayStreak(midnightDays, zone),
                coffeeOnlyMealExists,
                liquidOnlyMealExists,
                pizzaMeals,
                friesMeals,
                fastFoodMeals,
                dessertMeals,
                potatoMeals,
                cakeLogged,
                balancedEventually,
                activeDaysLastYear);
    }

    private List<FoodItem> parseFoods(MealAnalysisEntity e) {
        if (e.getResultJson() == null) {
            return List.of();
        }
        try {
            AnalysisResponse response = mapper.readValue(e.getResultJson(), AnalysisResponse.class);
            return response.foods() == null ? List.of() : response.foods();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private record MealFacts(int vegetableCount, boolean hasFruit, boolean beverageOnly, boolean coffeeOnly) {
    }

    /**
     * Reads the columns AnalysisService denormalizes at save time; only rows
     * saved before those columns existed (vegetableCount null) fall back to
     * parsing resultJson the way this whole method used to, for every row.
     */
    private MealFacts mealFacts(MealAnalysisEntity e) {
        if (e.getVegetableCount() != null) {
            return new MealFacts(e.getVegetableCount(),
                    Boolean.TRUE.equals(e.getHasFruit()),
                    Boolean.TRUE.equals(e.getBeverageOnly()),
                    Boolean.TRUE.equals(e.getCoffeeOnly()));
        }
        List<FoodItem> foods = parseFoods(e);
        int vegetableCount = 0;
        boolean hasFruit = false;
        boolean beverageOnly = !foods.isEmpty();
        boolean coffeeOnly = !foods.isEmpty();
        for (FoodItem f : foods) {
            String name = f.name() == null ? "" : f.name().toLowerCase(Locale.ROOT);
            if ("vegetable".equalsIgnoreCase(f.foodGroup())) {
                vegetableCount++;
            }
            if ("fruit".equalsIgnoreCase(f.foodGroup())) {
                hasFruit = true;
            }
            if (!FoodKeywords.matchesAny(name, FoodKeywords.BEVERAGE)) {
                beverageOnly = false;
            }
            if (!FoodKeywords.matchesAny(name, FoodKeywords.COFFEE)) {
                coffeeOnly = false;
            }
        }
        return new MealFacts(vegetableCount, hasFruit, beverageOnly, coffeeOnly);
    }

    /**
     * Consecutive calendar days (server timezone) present in the given day
     * set, counting back from today. A day is allowed to still be
     * "in progress" — if today isn't qualifying yet but yesterday was, the
     * streak counts from yesterday instead of already being broken.
     */
    private static int consecutiveDayStreak(Set<LocalDate> qualifyingDays, ZoneId zone) {
        if (qualifyingDays.isEmpty()) {
            return 0;
        }
        LocalDate cursor = LocalDate.now(zone);
        if (!qualifyingDays.contains(cursor)) {
            cursor = cursor.minusDays(1);
            if (!qualifyingDays.contains(cursor)) {
                return 0;
            }
        }
        int streak = 0;
        while (qualifyingDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
