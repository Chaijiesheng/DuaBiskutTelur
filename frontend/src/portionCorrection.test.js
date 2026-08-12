import { describe, expect, it } from 'vitest'
import {
  PORTION_STEPS,
  isCorrected,
  multipliersFrom,
  shouldSubmit,
  withMultiplierAt,
} from './portionCorrection.js'

describe('multipliersFrom', () => {
  /**
   * The corrections live on the foods, not in separate state. Reopening a meal
   * that was already halved has to show it as halved — independent state would
   * start every visit at 1x and quietly offer to undo the correction.
   */
  it('reads the corrections a meal already carries', () => {
    expect(multipliersFrom([{ portionMultiplier: 0.5 }, { portionMultiplier: 2 }])).toEqual([0.5, 2])
  })

  it('treats a meal logged before corrections existed as uncorrected', () => {
    // Those rows deserialize the field as 0 or omit it entirely; 0 would render
    // as a selected "0x" button and send a multiplier the server rejects.
    expect(multipliersFrom([{}, { portionMultiplier: 0 }, { portionMultiplier: null }])).toEqual([1, 1, 1])
    expect(multipliersFrom(undefined)).toEqual([])
  })
})

describe('withMultiplierAt', () => {
  it('changes one item and leaves the others alone', () => {
    expect(withMultiplierAt([1, 1, 1], 1, 0.5)).toEqual([1, 0.5, 1])
  })

  it('does not mutate the list it was given', () => {
    const original = [1, 1]
    withMultiplierAt(original, 0, 2)
    expect(original).toEqual([1, 1])
  })
})

describe('shouldSubmit', () => {
  it('sends a real change', () => {
    expect(shouldSubmit([0.5, 1], [1, 1])).toBe(true)
  })

  it('skips a tap on the button that is already selected', () => {
    // Each submission is a server round trip that re-grades the meal; tapping
    // "1x" on an uncorrected meal should cost nothing.
    expect(shouldSubmit([1, 1], [1, 1])).toBe(false)
  })

  /**
   * A length mismatch means the two lists describe different meals — the user
   * navigated while a request was in flight. Sending it would apply one meal's
   * corrections to another's foods, and the server rejects it anyway.
   */
  it('refuses a list that belongs to a different meal', () => {
    expect(shouldSubmit([0.5], [1, 1])).toBe(false)
    expect(shouldSubmit([], [])).toBe(false)
    expect(shouldSubmit(undefined, [1])).toBe(false)
  })
})

describe('isCorrected', () => {
  it('is false only while every item sits at the model estimate', () => {
    expect(isCorrected([1, 1])).toBe(false)
    expect(isCorrected([1, 1.5])).toBe(true)
    expect(isCorrected([])).toBe(false)
  })
})

describe('PORTION_STEPS', () => {
  it('offers 1x so a correction can be undone, and stays inside the server bounds', () => {
    expect(PORTION_STEPS).toContain(1)
    expect(Math.min(...PORTION_STEPS)).toBeGreaterThanOrEqual(0.25)
    expect(Math.max(...PORTION_STEPS)).toBeLessThanOrEqual(4)
  })
})
