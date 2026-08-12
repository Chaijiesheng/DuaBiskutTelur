package com.duabiskuttelur.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * One multiplier per food, in the order the foods were returned.
 *
 * <p>Positional rather than keyed by name: two items on the same plate can share
 * a name ("Sambal" twice), so a name is not an identifier. The server rejects a
 * list whose length does not match the stored meal, which is also what catches a
 * client working from a stale copy of the entry.
 *
 * <p>Note what is <em>not</em> here: any nutrition value. The corrected numbers
 * are computed server-side from the stored row, so a request cannot introduce
 * calories of its own into a history that feeds streaks and achievements.
 */
public record PortionCorrectionRequest(
        @NotEmpty(message = "At least one portion multiplier is required")
        @Size(max = 60, message = "More multipliers than any meal can have")
        List<
                @DecimalMin(value = "0.25", message = "Portion multiplier must be at least 0.25")
                @DecimalMax(value = "4.0", message = "Portion multiplier must be at most 4.0")
                Double> multipliers
) {
}
