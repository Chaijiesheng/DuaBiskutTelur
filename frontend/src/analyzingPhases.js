/**
 * Which stage of the pipeline to show during an analysis, from elapsed time.
 *
 * The backend answers a single POST — there is no streaming, no progress
 * channel, nothing to subscribe to. So this is a *timeline*, not a measurement,
 * and the whole design follows from being honest about that:
 *
 * - **The stages are real and in the real order.** Vision, then nutrition
 *   resolution, then feedback is what the server actually does, so the label on
 *   screen is never describing work that isn't happening.
 * - **It never reaches "done".** The last stage stays in progress until the
 *   response lands, however long that takes. A bar that fills to 100% and then
 *   sits there is a lie the user catches every time, and it is the specific
 *   reason there is no percentage here.
 * - **It can run late without breaking.** Past the last threshold the final
 *   stage simply holds. Nothing rewinds and nothing skips ahead.
 *
 * Thresholds come from the handover's 8–20s typical range, weighted toward
 * vision because that is the slowest leg and the one that varies most.
 */

/** Stage boundaries in milliseconds since the request started. */
const MEAL_STAGES = [0, 4000, 9000]
const MENU_STAGES = [0, 5000, 14000]
const BARCODE_STAGES = [0, 1500]

export const STAGE_COUNTS = {
  meal: MEAL_STAGES.length,
  menu: MENU_STAGES.length,
  barcode: BARCODE_STAGES.length,
}

/**
 * When to stop saying "this usually takes N seconds" and admit it is running
 * long.
 *
 * These are tied to the promise the copy makes, not to the stage boundaries —
 * analyzing.takes says "10–20 seconds", so 20s is the moment that sentence
 * stops being true. Change one and change the other.
 */
const OVERDUE_MS = {
  meal: 20000,
  menu: 30000,
  barcode: 8000,
}

function stagesFor(flow) {
  if (flow === 'menu') return MENU_STAGES
  if (flow === 'barcode') return BARCODE_STAGES
  return MEAL_STAGES
}

/**
 * Index of the stage to show as in-progress.
 *
 * Clamped to the last stage, so an analysis that outlives the timeline holds on
 * "writing your feedback" rather than running off the end or looping.
 */
export function stageAt(elapsedMs, flow = 'meal') {
  const stages = stagesFor(flow)
  let current = 0
  for (let i = 0; i < stages.length; i++) {
    if (elapsedMs >= stages[i]) current = i
  }
  return current
}

/**
 * How a stage should render: 'done' for ones already passed, 'active' for the
 * current one, 'pending' for those ahead.
 *
 * Note the last stage is never 'done' — reaching it means the request is still
 * in flight, and the screen unmounts when it isn't.
 */
export function stageState(index, currentStage) {
  if (index < currentStage) return 'done'
  if (index === currentStage) return 'active'
  return 'pending'
}

/**
 * Whether the analysis has outlived the duration the screen promised.
 *
 * This is the one place the screen is allowed to change its story, and it only
 * ever changes it in the honest direction: from "about this long" to "longer
 * than usual". It never claims to be nearly done.
 */
export function isOverdue(elapsedMs, flow = 'meal') {
  return elapsedMs > (OVERDUE_MS[flow] ?? OVERDUE_MS.meal)
}
