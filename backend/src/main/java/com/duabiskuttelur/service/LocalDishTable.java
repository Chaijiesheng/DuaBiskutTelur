package com.duabiskuttelur.service;

import com.duabiskuttelur.client.UsdaClient.NutrientsPer100g;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Nutrition for dishes USDA cannot answer for.
 *
 * <p>USDA indexes American foods and generic ingredients. It has no row for
 * nasi lemak, and its fuzzy search will not say so — it returns the nearest
 * text match and the pipeline has no way to tell that apart from real data. On
 * a 30-dish Malaysian benchmark, 26% of USDA-resolved dishes came back
 * arithmetically impossible, against 0% of the dishes that fell through to the
 * vision model's own estimate.
 *
 * <p>So recognised local dishes are answered from {@code malaysian-dishes.csv}
 * before USDA is consulted at all, leaving that lookup to handle the single
 * ingredients it is genuinely good at.
 *
 * @see NutritionValidator for the guard on everything this table doesn't cover
 */
@Component
public class LocalDishTable {

    private static final Logger log = LoggerFactory.getLogger(LocalDishTable.class);
    private static final String RESOURCE = "nutrition/malaysian-dishes.csv";

    /** canonical, aliases, kcal, protein, carbs, fat, fibre, sugar, sodium, foodGroup, fried. */
    private static final int FIELDS = 11;

    /**
     * A table row. {@code key} is one alias, already normalised; a dish with
     * three aliases contributes three entries so lookup is a flat scan.
     */
    record Entry(String key, List<String> tokens, String canonical,
                 NutrientsPer100g nutrients, String foodGroup, boolean fried) {}

    /**
     * Words that carry no dish identity. Dropped from keys so "beef rendang
     * with rice" is three tokens rather than four, and can't win a match on
     * filler alone.
     */
    private static final List<String> STOPWORDS = List.of("with", "and", "the", "of", "in", "on", "a", "w");

    private List<Entry> entries = List.of();

    public LocalDishTable() {
    }

    /**
     * A table with fixed contents, for tests that supply their own fixture
     * nutrition and need the real one out of the way — passing an empty list
     * makes every lookup miss, so a dish keeps whatever the caller gave it.
     */
    LocalDishTable(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    @PostConstruct
    void load() {
        List<Entry> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                parse(trimmed).forEach(loaded::add);
            }
        } catch (Exception e) {
            // A missing or malformed table shouldn't take the app down — every
            // dish simply falls through to the USDA path as it did before.
            log.error("Could not load {}; local dish lookups are disabled", RESOURCE, e);
            return;
        }
        // Most tokens first, so "nasi lemak with fried chicken" wins over the
        // "nasi lemak" it contains; key length breaks ties.
        loaded.sort(Comparator.comparingInt((Entry e) -> e.tokens().size())
                .thenComparingInt(e -> e.key().length()).reversed());
        entries = List.copyOf(loaded);
        log.info("Loaded {} local dish aliases from {}", entries.size(), RESOURCE);
    }

    private static List<Entry> parse(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length != FIELDS) {
            log.warn("Skipping malformed dish row (expected {} fields, got {}): {}", FIELDS, f.length, line);
            return List.of();
        }
        try {
            String canonical = f[0].trim();
            NutrientsPer100g n = new NutrientsPer100g(canonical + " (local table)",
                    Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                    Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]),
                    Double.parseDouble(f[8]));
            String group = f[9].trim();
            boolean fried = Boolean.parseBoolean(f[10].trim());

            List<Entry> out = new ArrayList<>();
            addKey(out, canonical, canonical, n, group, fried);
            for (String alias : f[1].split(";")) {
                addKey(out, alias, canonical, n, group, fried);
            }
            return out;
        } catch (NumberFormatException e) {
            log.warn("Skipping dish row with unparseable numbers: {}", line);
            return List.of();
        }
    }

    /**
     * Menu text carries prices, portion notes and inconsistent romanisation —
     * "Char Kuey Teow (RM11.90)" and "char kway teow" are the same dish. Strip
     * anything parenthesised that looks like a price or size, then reduce to
     * lowercase words so only the dish name is left to match on.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("\\((?=[^)]*(?:rm|\\d))[^)]*\\)", " ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static void addKey(List<Entry> out, String raw, String canonical,
                               NutrientsPer100g n, String group, boolean fried) {
        String key = normalise(raw);
        List<String> tokens = tokensOf(key);
        if (!tokens.isEmpty()) {
            out.add(new Entry(key, tokens, canonical, n, group, fried));
        }
    }

    private static List<String> tokensOf(String normalised) {
        if (normalised.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String word : normalised.split(" ")) {
            if (!word.isBlank() && !STOPWORDS.contains(word)) {
                out.add(word);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Matches when every word of a key appears in the dish name, in any order.
     *
     * <p>Plain substring matching looked sufficient until "Beef Rendang with
     * White Rice" failed to match the "beef rendang with rice" row — menus
     * insert their own adjectives, and there is no way to enumerate them.
     * Requiring the key's words to be present, rather than contiguous, absorbs
     * that; entries are pre-sorted so the most specific row is reached first.
     *
     * @return curated nutrition for this dish name, or empty if it isn't one we know.
     */
    public Optional<Entry> lookup(String dishName) {
        List<String> words = tokensOf(normalise(dishName));
        if (words.isEmpty()) {
            return Optional.empty();
        }
        for (Entry e : entries) {
            if (words.containsAll(e.tokens())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** Visible for tests: how many aliases are loaded. */
    int size() {
        return entries.size();
    }
}
