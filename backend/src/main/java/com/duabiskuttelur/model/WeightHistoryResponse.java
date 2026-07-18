package com.duabiskuttelur.model;

import java.util.List;

/** Weekly-averaged weigh-ins for the trailing window, oldest first. Empty weeks are omitted, not zero-filled. */
public record WeightHistoryResponse(List<WeightWeekPoint> weeks) {
}
