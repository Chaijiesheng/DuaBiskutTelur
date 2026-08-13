import { describe, expect, it } from 'vitest'
import { STAGE_COUNTS, isOverdue, stageAt, stageState } from './analyzingPhases.js'

/**
 * U3 asked for a progress indicator on an 8–20s wait that previously showed
 * none. The backend answers a single POST with no streaming, so this is a
 * timeline rather than a measurement — which makes the honesty properties the
 * things worth pinning, not the exact thresholds.
 *
 * The one that matters most: it must never reach "done". A progress display
 * that completes while the user is still waiting is a lie they catch every
 * time, and it is why there is no percentage anywhere in this module.
 */

describe('stageAt', () => {
  it('starts on the first stage', () => {
    expect(stageAt(0, 'meal')).toBe(0)
    expect(stageAt(-1, 'meal')).toBe(0)
  })

  it('advances through the stages in order as time passes', () => {
    expect(stageAt(1000, 'meal')).toBe(0)
    expect(stageAt(5000, 'meal')).toBe(1)
    expect(stageAt(12000, 'meal')).toBe(2)
  })

  it('never moves backwards as elapsed time grows', () => {
    let previous = 0
    for (let ms = 0; ms <= 60000; ms += 250) {
      const stage = stageAt(ms, 'meal')
      expect(stage).toBeGreaterThanOrEqual(previous)
      previous = stage
    }
  })

  it('holds on the last stage instead of running off the end', () => {
    // An analysis can outlive the timeline — the Gemini budget alone allows far
    // longer than this. Holding is honest; wrapping around or blanking is not.
    const last = STAGE_COUNTS.meal - 1
    expect(stageAt(30_000, 'meal')).toBe(last)
    expect(stageAt(10 * 60_000, 'meal')).toBe(last)
  })

  it('uses each flow’s own stages, since the pipelines differ', () => {
    // A barcode scan does no vision work, so claiming to read a photo would be
    // describing work that is not happening.
    expect(STAGE_COUNTS.barcode).toBe(2)
    // Four, not three: the menu stages now come from the measured "Menu scan
    // finished" timings rather than a guess, and that telemetry separates the
    // upload from the model reading the page.
    expect(STAGE_COUNTS.menu).toBe(4)
    expect(stageAt(2000, 'barcode')).toBe(1)
    expect(stageAt(2000, 'menu')).toBe(0)
  })

  it('falls back to the meal timeline for an unknown flow', () => {
    expect(stageAt(5000, 'something-else')).toBe(stageAt(5000, 'meal'))
  })
})

describe('stageState', () => {
  it('marks passed stages done, the current one active, and the rest pending', () => {
    expect(stageState(0, 1)).toBe('done')
    expect(stageState(1, 1)).toBe('active')
    expect(stageState(2, 1)).toBe('pending')
  })

  it('never reports every stage done, however long the wait runs', () => {
    // The screen unmounts when the response lands, so reaching the last stage
    // means the request is still in flight. It stays active, forever if need be.
    const last = STAGE_COUNTS.meal - 1
    const stage = stageAt(60 * 60_000, 'meal')
    expect(stageState(last, stage)).toBe('active')

    const states = Array.from({ length: STAGE_COUNTS.meal }, (_, i) => stageState(i, stage))
    expect(states.every((s) => s === 'done')).toBe(false)
  })
})

/**
 * The screen tells the user "this usually takes 10–20 seconds". That sentence
 * has an expiry date, and this is it — the only point where the screen is
 * allowed to change its story, and it only ever changes it in the honest
 * direction.
 */
describe('isOverdue', () => {
  it('stays quiet for the whole duration the copy promised', () => {
    expect(isOverdue(0, 'meal')).toBe(false)
    expect(isOverdue(19_000, 'meal')).toBe(false)
    // analyzing.takes says "10–20 seconds", so 20s is the boundary. If that
    // string changes, OVERDUE_MS.meal has to change with it.
    expect(isOverdue(20_000, 'meal')).toBe(false)
  })

  it('admits it is running long once the promise has expired', () => {
    expect(isOverdue(20_001, 'meal')).toBe(true)
    expect(isOverdue(60_000, 'meal')).toBe(true)
  })

  it('holds each flow to its own promise', () => {
    // A barcode lookup promises "a few seconds", so it must not wait 20 of them
    // before admitting something is wrong.
    expect(isOverdue(9000, 'barcode')).toBe(true)
    expect(isOverdue(9000, 'meal')).toBe(false)
    expect(isOverdue(25_000, 'menu')).toBe(false)
  })

  it('falls back to the meal threshold for an unknown flow', () => {
    expect(isOverdue(25_000, 'something-else')).toBe(isOverdue(25_000, 'meal'))
  })
})
