import { describe, expect, it } from 'vitest'
import {
  LOW_CONFIDENCE,
  calorieRange,
  hasMeaningfulSpread,
  lowConfidenceItems,
  meanConfidence,
  scoreSpread,
} from './confidence.js'

const food = (name, confidence) => ({ name, confidence })

describe('meanConfidence', () => {
  it('averages what it has and ignores items carrying no score', () => {
    expect(meanConfidence([food('a', 0.9), food('b', 0.5)])).toBeCloseTo(0.7)
    expect(meanConfidence([food('a', 0.8), { name: 'legacy row' }])).toBeCloseTo(0.8)
  })

  it('returns null rather than 0 when there is nothing to average', () => {
    // 0 would read as "certainly wrong", which is the opposite of "unknown" —
    // and would put a maximum-width band on every pre-confidence stored meal.
    expect(meanConfidence([])).toBeNull()
    expect(meanConfidence(undefined)).toBeNull()
    expect(meanConfidence([{ name: 'legacy row' }])).toBeNull()
  })
})

describe('lowConfidenceItems', () => {
  it('names only the items below the escalation threshold, in display order', () => {
    const foods = [food('Nasi lemak', 0.95), food('Unidentified stew', 0.3), food('Kuih', 0.44)]
    expect(lowConfidenceItems(foods).map((f) => f.name)).toEqual(['Unidentified stew', 'Kuih'])
  })

  it('is exclusive at the threshold, so an item exactly at it is not called out', () => {
    expect(lowConfidenceItems([food('Borderline', LOW_CONFIDENCE)])).toEqual([])
  })
})

describe('scoreSpread', () => {
  it('widens as confidence falls', () => {
    expect(scoreSpread(0.95)).toBeLessThan(scoreSpread(0.7))
    expect(scoreSpread(0.7)).toBeLessThan(scoreSpread(0.4))
  })

  it('shows nothing for a confident meal and never swallows a whole grade band', () => {
    expect(hasMeaningfulSpread(scoreSpread(0.95))).toBe(false)
    expect(hasMeaningfulSpread(scoreSpread(0.5))).toBe(true)
    // A 50% identification confidence does not mean the score is meaningless.
    expect(scoreSpread(0)).toBeLessThanOrEqual(12)
  })

  it('shows no band at all when confidence is unknown', () => {
    expect(scoreSpread(null)).toBe(0)
    expect(hasMeaningfulSpread(scoreSpread(null))).toBe(false)
  })
})

describe('calorieRange', () => {
  it('reports the band when the model bracketed the portion', () => {
    expect(calorieRange({ calories: 700, caloriesLow: 560.4, caloriesHigh: 860.6 }))
      .toEqual({ low: 560, high: 861 })
  })

  /**
   * A barcode scan knows its serving exactly, so the server collapses the band
   * onto the point value. Rendering "700–700 kcal" would invent a claim of
   * measurement where there is none.
   */
  it('reports nothing when there is no real uncertainty', () => {
    expect(calorieRange({ calories: 700, caloriesLow: 700, caloriesHigh: 700 })).toBeNull()
  })

  /** Meals stored before the bracket existed deserialize to zeros. */
  it('reports nothing for a meal logged before portions were bracketed', () => {
    expect(calorieRange({ calories: 700 })).toBeNull()
    expect(calorieRange({ calories: 700, caloriesLow: 0, caloriesHigh: 0 })).toBeNull()
    expect(calorieRange(undefined)).toBeNull()
  })
})
