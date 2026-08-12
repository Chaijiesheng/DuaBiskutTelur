/**
 * What the per-item confidence score is allowed to change on screen.
 *
 * The model has always returned a 0-1 confidence per item, and the app has
 * always shown it as a coloured dot with a percentage. That surfaces the
 * number but not its meaning: 55% and 95% render as the same layout, the same
 * font weight, and the same air of authority around the calorie total. This
 * module is the one place the thresholds live, so the food card, the calorie
 * band and the score band cannot drift apart on what "unsure" means.
 *
 * Deliberately *not* done here: nothing in this file changes a score, a total,
 * or a grade. Those stay deterministic arithmetic computed on the server. Low
 * confidence widens what the app *claims*, never what it computed — an app
 * that quietly marked down uncertain meals would be lying in a new direction
 * rather than being honest.
 */

/** Below this, the item is called out by name and the meal gets a banner. */
export const LOW_CONFIDENCE = 0.45

/** Below this, the item is flagged on its own card but not escalated. */
export const UNSURE = 0.6

/** Mean confidence across the meal, or null when there is nothing to average. */
export function meanConfidence(foods) {
  const scores = (foods ?? []).map((f) => f?.confidence).filter((c) => typeof c === 'number')
  if (scores.length === 0) return null
  return scores.reduce((sum, c) => sum + c, 0) / scores.length
}

/** The items the app should admit it may have got wrong, in the order shown. */
export function lowConfidenceItems(foods) {
  return (foods ?? []).filter((f) => typeof f?.confidence === 'number' && f.confidence < LOW_CONFIDENCE)
}

/**
 * How many points of "we might be wrong about what this even is" to show
 * around the score.
 *
 * A linear widening, floored at zero and capped so the band never swallows a
 * whole grade boundary's worth of points — past that it stops informing and
 * starts reading as "the score is meaningless", which is not what a 50%
 * identification confidence implies. Bands under 2 are not rendered at all
 * (see `hasMeaningfulSpread`): a +/-1 on a 100-point scale is noise dressed up
 * as precision, which is the exact failure mode this is meant to fix.
 */
export function scoreSpread(mean) {
  if (mean == null) return 0
  return Math.min(12, Math.max(0, Math.round((1 - mean) * 15)))
}

export function hasMeaningfulSpread(spread) {
  return spread >= 2
}

/**
 * The meal's calorie band, or null when there is nothing honest to show.
 *
 * Absent for barcode scans (the serving is known exactly) and for meals logged
 * before the model was asked to bracket its portion estimate — in both cases
 * the server collapses low and high onto the point value, so the check is the
 * same one either way.
 */
export function calorieRange(totals) {
  const low = totals?.caloriesLow
  const high = totals?.caloriesHigh
  if (typeof low !== 'number' || typeof high !== 'number' || high <= low) return null
  return { low: Math.round(low), high: Math.round(high) }
}
