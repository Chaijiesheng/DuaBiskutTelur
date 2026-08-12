/**
 * Client-side bookkeeping for "that was half that much".
 *
 * The rescaling and the re-grading both happen on the server — the scoring
 * engine is deterministic Java and reimplementing it here would create a second
 * copy to drift out of sync, which is the same trap `calorieCalculator.js`
 * already sits in with `CalorieBudget.java`. All this module does is track which
 * multiplier is showing for which food and decide when there is anything to
 * send.
 */

/** What the buttons offer. Coarse on purpose: a user knows "half", not "0.6×". */
export const PORTION_STEPS = [0.5, 1, 1.5, 2]

/**
 * The multipliers currently applied, read back off the foods themselves rather
 * than kept in a parallel array. Reopening a corrected meal from history has to
 * show the corrections it already carries, and a separate piece of state would
 * start at 1× and silently offer to undo them.
 */
export function multipliersFrom(foods) {
  return (foods ?? []).map((f) => (typeof f?.portionMultiplier === 'number' && f.portionMultiplier > 0
    ? f.portionMultiplier
    : 1))
}

/** Immutably sets one entry, leaving the rest as they are. */
export function withMultiplierAt(multipliers, index, value) {
  return multipliers.map((current, i) => (i === index ? value : current))
}

/**
 * Whether a request is worth making. Guards two cases that both look like a
 * no-op but are not: an unchanged value (tapping the button already selected),
 * and a length mismatch, which means this list belongs to a different meal —
 * sending it would apply one meal's corrections to another's foods.
 */
export function shouldSubmit(next, current) {
  if (!Array.isArray(next) || !Array.isArray(current)) return false
  if (next.length === 0 || next.length !== current.length) return false
  return next.some((value, i) => value !== current[i])
}

/** True once any item is no longer at the model's original estimate. */
export function isCorrected(multipliers) {
  return (multipliers ?? []).some((m) => m !== 1)
}
