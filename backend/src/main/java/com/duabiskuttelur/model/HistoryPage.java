package com.duabiskuttelur.model;

import java.util.List;

/**
 * One page of the meal history, and whether there is another behind it.
 *
 * <p>{@code hasMore} is answered by the page query itself -- it asks for one
 * row more than it returns -- rather than by a second {@code count(*)} over the
 * user's whole history on every scroll.
 *
 * <p>There is no cursor field. The client already holds the last row it was
 * given, which is the cursor; sending it back separately creates a second copy
 * of the same fact that can disagree with the first.
 */
public record HistoryPage(List<HistoryEntry> entries, boolean hasMore) {
}
