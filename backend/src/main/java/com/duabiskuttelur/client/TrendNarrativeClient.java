package com.duabiskuttelur.client;

/**
 * Provider-agnostic prose for a weekly or monthly trend report (text-only).
 *
 * <p>Note what is deliberately <em>not</em> here: any number. The averages,
 * deltas, grades and counts are computed by {@code TrendReportService} from
 * stored columns before this is called, and the model is handed them as
 * finished facts to describe. A model that can produce a figure can produce a
 * wrong one, and a wrong figure in a report the user checks against last week
 * is worse than no report -- it teaches them the screen cannot be trusted.
 *
 * <p>The contract is therefore narrow on purpose: facts in, one paragraph out.
 * {@code TrendNarrator} has a rule-based paragraph ready for when this is
 * unavailable, so a provider outage costs a sentence and not the report.
 */
public interface TrendNarrativeClient {

    /**
     * @param context      the computed figures, already formatted as plain
     *                     labelled lines. Contains no user-authored text and no
     *                     model-derived text, so there is nothing here for an
     *                     injected instruction to ride in on.
     * @param languageName the language to write in, spelled out for a prompt
     *                     ("Simplified Chinese", not "zh"), matching how the
     *                     feedback and coach clients take it.
     * @return one short paragraph, or null when the provider produced nothing
     *         usable
     */
    String narrate(String context, String languageName);
}
