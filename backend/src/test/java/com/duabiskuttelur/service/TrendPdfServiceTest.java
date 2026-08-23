package com.duabiskuttelur.service;

import com.duabiskuttelur.model.TrendDay;
import com.duabiskuttelur.model.TrendReportResponse;
import com.duabiskuttelur.model.TrendTotals;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exported report.
 *
 * <p>Asserted against the text the PDF actually draws rather than against the
 * bytes handed to the renderer -- a PDF that silently drops what it cannot
 * encode looks perfectly healthy from the outside, which is the failure mode
 * this file exists to catch.
 */
class TrendPdfServiceTest {

    private static final LocalDate TO = LocalDate.of(2026, 8, 20);
    private final TrendPdfService service = new TrendPdfService();

    private static Map<String, Integer> mix(int aplus, int a, int b, int c, int d) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("A+", aplus);
        m.put("A", a);
        m.put("B", b);
        m.put("C", c);
        m.put("D", d);
        return m;
    }

    private static TrendTotals fullTotals() {
        return new TrendTotals(6, 17, 1840, 74, "B", 78, 11, 4, 1900, 3, 3, 82, -0.6, 71.4);
    }

    private static TrendReportResponse report(TrendTotals totals, TrendTotals previous, String narrative) {
        List<TrendDay> days = List.of(
                new TrendDay(TO.minusDays(3), true, 2, 1750, false),
                new TrendDay(TO.minusDays(2), false, 0, 0, false),
                new TrendDay(TO.minusDays(1), true, 3, 2400, true),
                new TrendDay(TO, true, 2, 1600, false));
        return new TrendReportResponse("week", TO.minusDays(6), TO, 7, 2000, true,
                days, totals, previous, mix(1, 3, 8, 4, 1), "A", TO, narrative, "rules");
    }

    private String textOf(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder all = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append('\n');
            }
            return all.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void producesAReadablePdf() throws Exception {
        byte[] pdf = service.render(report(fullTotals(), null, "A steady week."));

        assertTrue(pdf.length > 500, "suspiciously small for a full report: " + pdf.length + " bytes");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(textOf(pdf).contains("Weekly Report"));
    }

    @Test
    void statesTheRangeAndHowMuchOfItWasLogged() throws Exception {
        String text = textOf(service.render(report(fullTotals(), null, "")));

        assertTrue(text.contains("14 Aug 2026"), text);
        assertTrue(text.contains("20 Aug 2026"), text);
        assertTrue(text.contains("6 of 7 days logged"), text);
    }

    @Test
    void carriesTheHeadlineFiguresAndTheHabits() throws Exception {
        String text = textOf(service.render(report(fullTotals(), null, "")));

        assertTrue(text.contains("1840"), "average daily calories missing");
        assertTrue(text.contains("Average grade"), text);
        assertTrue(text.contains("Vegetable servings"), text);
        assertTrue(text.contains("Latest weight"), text);
    }

    /**
     * A PDF outlives the screen it came from. A metric the report withheld as
     * "not enough to say" has to be absent from the document, because a zero
     * printed here will be read as a measurement long after the context is gone.
     */
    @Test
    void leavesOutMetricsTheReportCouldNotCompute() throws Exception {
        TrendTotals gaps = new TrendTotals(6, 17, 1840, 74, "B",
                null, null, null, null, null, null, null, null, null);

        String text = textOf(service.render(report(gaps, null, "")));

        assertFalse(text.contains("Average daily protein"), text);
        assertFalse(text.contains("Vegetable servings"), text);
        assertFalse(text.contains("Latest weight"), text);
        assertTrue(text.contains("Average daily calories"), "the metrics that did compute went missing too");
    }

    /** The gaps are the point: a doctor needs to see the day that is missing. */
    @Test
    void showsUnloggedDaysRatherThanHidingThem() throws Exception {
        String text = textOf(service.render(report(fullTotals(), null, "")));

        assertTrue(text.contains("not logged"), text);
        assertTrue(text.contains("Tue 18 Aug"), "an unlogged day was dropped from the table: " + text);
    }

    @Test
    void showsTheChangeAgainstThePreviousPeriodWhenThereIsOne() throws Exception {
        TrendTotals previous = new TrendTotals(6, 15, 2050, 68, "C", 84, 8, 2, 1700, 2, 1, 30, -0.2, 72.0);

        String text = textOf(service.render(report(fullTotals(), previous, "")));

        assertTrue(text.contains("-210"), "calorie change against last week is missing: " + text);
        assertTrue(text.contains("was C"), "grade change is missing: " + text);
    }

    @Test
    void includesTheWrittenSummaryWhenThereIsOne() throws Exception {
        String text = textOf(service.render(report(fullTotals(), null, "You logged six of seven days.")));
        assertTrue(text.contains("You logged six of seven days."), text);
    }

    /**
     * The reason this document is English-only, pinned as behaviour rather than
     * left as a comment.
     *
     * <p>The base-14 fonts are Cp1252, so CJK glyphs cannot be drawn. What
     * matters is that the failure is confined: the rest of the report must
     * still render. If a CJK face is ever embedded, this test is the one that
     * should start failing.
     */
    @Test
    void survivesTextItCannotEncodeWithoutLosingTheRestOfTheReport() throws Exception {
        String text = textOf(service.render(report(fullTotals(), null, "你在 7 天中记录了 6 天。")));

        assertTrue(text.contains("Weekly Report"), "non-Latin prose took the whole document down");
        assertTrue(text.contains("1840"), "the figures survived, so the export is still useful");
    }

    /**
     * A month is thirty day-rows on top of four tables, which does not fit on
     * one A4 page. It has to paginate rather than overflow -- and it has to
     * paginate into a sane number of pages, not one per row.
     */
    @Test
    void aMonthReportPaginatesRatherThanOverflowing() throws Exception {
        java.util.List<TrendDay> days = new java.util.ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            days.add(new TrendDay(TO.minusDays(i), i % 5 != 0, 2, 1800 + (i * 20), i % 7 == 0));
        }
        TrendReportResponse month = new TrendReportResponse("month", TO.minusDays(29), TO, 30, 2000,
                true, days, fullTotals(), null, mix(2, 6, 14, 5, 1), "A", TO, "A steady month.", "rules");

        byte[] pdf = service.render(month);
        PdfReader reader = new PdfReader(pdf);
        int pages = reader.getNumberOfPages();
        reader.close();

        assertTrue(pages >= 1 && pages <= 3, "a month report rendered across " + pages + " pages");
        String text = textOf(pdf);
        assertTrue(text.contains("Monthly Report"), text);
        assertTrue(text.contains("not logged"), "unlogged days were dropped from the month table");
    }

    @Test
    void namesTheFileAfterThePeriodAndItsEndDate() {
        assertEquals("duabiskuttelur-week-2026-08-20.pdf",
                TrendPdfService.filenameFor(report(fullTotals(), null, "")));
    }
}
