package com.duabiskuttelur.service;

import com.duabiskuttelur.client.TrendNarrativeClient;
import com.duabiskuttelur.model.TrendDay;
import com.duabiskuttelur.model.TrendReportResponse;
import com.duabiskuttelur.model.TrendTotals;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one paragraph a model writes, and the guarantees around it.
 *
 * <p>The report is the product and the paragraph is a garnish, so most of these
 * are about the paragraph failing safely: a provider outage, an empty answer, a
 * window too thin to describe. The other half is the cache, which is what stops
 * a user who flicks between Week and Month five times from spending five calls.
 */
class TrendNarratorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    private static TrendReportResponse report(TrendTotals totals, boolean enoughData) {
        List<TrendDay> days = List.of(
                new TrendDay(TODAY.minusDays(3), true, 2, 1800, false),
                new TrendDay(TODAY.minusDays(2), true, 2, 1750, false),
                new TrendDay(TODAY.minusDays(1), true, 3, 2400, true),
                new TrendDay(TODAY, true, 2, 1600, false));
        return new TrendReportResponse("week", TODAY.minusDays(6), TODAY, 7, 2000,
                enoughData, days, totals, null,
                Map.of("A+", 0, "A", 2, "B", 5, "C", 1, "D", 0), "A", TODAY, "", "");
    }

    private static TrendTotals totals() {
        return new TrendTotals(4, 9, 1887, 74, "B", 78, 11, 3, 1900, 3, 2, 55, -0.6, 71.4);
    }

    /** No vegetables, no water, no workouts -- so the rule-based gap picks vegetables. */
    private static TrendTotals sparseTotals() {
        return new TrendTotals(4, 9, 1887, 74, "B", 78, 2, 1, 900, 0, 0, null, null, null);
    }

    @Test
    void usesTheModelWhenItAnswers() {
        TrendNarrator narrator = new TrendNarrator((context, language) -> "A good week overall.");

        TrendNarrator.Narrative result = narrator.narrate(report(totals(), true), "en");

        assertEquals("A good week overall.", result.text());
        assertEquals("ai", result.source().tag());
    }

    /**
     * The posture the whole feature depends on: the numbers are already
     * computed, so a provider outage costs a sentence and not the report.
     */
    @Test
    void fallsBackToRulesWhenTheProviderThrows() {
        TrendNarrator narrator = new TrendNarrator((context, language) -> {
            throw new RuntimeException("provider down");
        });

        TrendNarrator.Narrative result = narrator.narrate(report(totals(), true), "en");

        assertEquals("rules", result.source().tag());
        assertFalse(result.text().isBlank(), "an outage left the report with no paragraph at all");
    }

    @Test
    void fallsBackToRulesWhenTheProviderReturnsNothingUsable() {
        for (String empty : new String[]{null, "", "   "}) {
            TrendNarrator narrator = new TrendNarrator((context, language) -> empty);
            TrendNarrator.Narrative result = narrator.narrate(report(totals(), true), "en");
            assertEquals("rules", result.source().tag(), "an empty answer was accepted as prose");
            assertFalse(result.text().isBlank());
        }
    }

    /**
     * There is nothing true to say about three days, and saying it anyway is
     * what makes a report feel like filler. No call is made either.
     */
    @Test
    void writesNothingAtAllWhenThereIsNoTrendYet() {
        AtomicInteger calls = new AtomicInteger();
        TrendNarrator narrator = new TrendNarrator((context, language) -> {
            calls.incrementAndGet();
            return "should not happen";
        });

        TrendNarrator.Narrative result = narrator.narrate(report(totals(), false), "en");

        assertEquals("", result.text());
        assertEquals(0, calls.get(), "spent a model call on a report with no trend in it");
    }

    /**
     * The cache key is a fingerprint of the figures, so re-opening the tab is
     * free and a changed meal is not.
     */
    @Test
    void generatesOncePerSetOfFigures() {
        AtomicInteger calls = new AtomicInteger();
        TrendNarrator narrator = new TrendNarrator((context, language) -> "call " + calls.incrementAndGet());

        TrendReportResponse first = report(totals(), true);
        assertEquals("call 1", narrator.narrate(first, "en").text());
        assertEquals("call 1", narrator.narrate(first, "en").text(), "asked twice for identical figures");
        assertEquals(1, calls.get());
    }

    @Test
    void regeneratesWhenTheFiguresChange() {
        AtomicInteger calls = new AtomicInteger();
        TrendNarrator narrator = new TrendNarrator((context, language) -> "call " + calls.incrementAndGet());

        narrator.narrate(report(totals(), true), "en");
        TrendTotals moved = new TrendTotals(5, 11, 1750, 78, "B", 80, 13, 4, 1900, 3, 2, 55, -0.8, 71.2);
        narrator.narrate(report(moved, true), "en");

        assertEquals(2, calls.get(), "a changed week reused the previous week's paragraph");
    }

    /** Two languages are two paragraphs, not one translated by accident. */
    @Test
    void cachesPerLanguage() {
        AtomicInteger calls = new AtomicInteger();
        TrendNarrator narrator = new TrendNarrator((context, language) -> language + " " + calls.incrementAndGet());

        TrendReportResponse r = report(totals(), true);
        assertTrue(narrator.narrate(r, "en").text().startsWith("English"));
        assertTrue(narrator.narrate(r, "ms").text().startsWith("Malay"));
        assertEquals(2, calls.get());
    }

    @Test
    void writesTheRuleBasedParagraphInTheUsersLanguage() {
        TrendReportResponse r = report(totals(), true);

        String en = TrendNarrator.ruleBased(r, "en");
        String ms = TrendNarrator.ruleBased(r, "ms");
        String zh = TrendNarrator.ruleBased(r, "zh");

        assertTrue(en.contains("logged"), en);
        assertTrue(ms.contains("merekod"), ms);
        assertTrue(zh.contains("记录"), zh);
        assertNotEquals(en, ms);
    }

    /** An unknown language falls back to English rather than producing nothing. */
    @Test
    void fallsBackToEnglishForAnUnsupportedLanguage() {
        String text = TrendNarrator.ruleBased(report(totals(), true), "de");
        assertTrue(text.contains("logged"), text);
    }

    /** The rule-based paragraph names one gap, not a list of everything wrong. */
    @Test
    void namesAtMostOneThingToImprove() {
        String text = TrendNarrator.ruleBased(report(sparseTotals(), true), "en");

        long gaps = List.of("Vegetables are", "Water is", "A short workout").stream()
                .filter(text::contains).count();

        assertEquals(1, gaps, "listed more than one shortfall: " + text);
        assertTrue(text.contains("Vegetables are"), "expected the largest gap first: " + text);
    }

    /**
     * The context is the model's entire input, and every line of it is a number
     * this app computed. Nothing user-authored or model-authored reaches it, so
     * there is no untrusted span for an injected instruction to ride in on.
     */
    @Test
    void sendsOnlyComputedFiguresToTheModel() {
        String context = TrendNarrator.buildContext(report(totals(), true));

        assertTrue(context.contains("average daily calories: 1887"), context);
        assertTrue(context.contains("days logged: 4 of 7"), context);
        assertTrue(context.contains("average grade: B"), context);
        assertTrue(context.contains("days over budget: 1"), context);
    }

    /** A metric the report withheld must not appear in the prompt either. */
    @Test
    void omitsMetricsTheReportCouldNotCompute() {
        TrendTotals gaps = new TrendTotals(4, 9, 1887, 74, "B", null, null, null, null, null, null, null, null, null);

        String context = TrendNarrator.buildContext(report(gaps, true));

        assertFalse(context.contains("protein"), context);
        assertFalse(context.contains("vegetable"), context);
        assertFalse(context.contains("weight"), context);
        assertTrue(context.contains("average daily calories"), context);
    }

    /** Deprived of a client entirely, the narrator still produces something true. */
    @Test
    void survivesAClientThatAlwaysFails() {
        TrendNarrator narrator = new TrendNarrator((context, language) -> {
            throw new IllegalStateException("no keys configured");
        });

        for (String lang : new String[]{"en", "ms", "zh"}) {
            TrendNarrator.Narrative result = narrator.narrate(report(totals(), true), lang);
            assertFalse(result.text().isBlank(), lang + " produced no paragraph");
            assertEquals("rules", result.source().tag());
        }
    }

    /** Compile-time check that the interface stays a one-method contract. */
    @SuppressWarnings("unused")
    private static final TrendNarrativeClient SHAPE = (context, language) -> null;
}
