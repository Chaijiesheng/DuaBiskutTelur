import { beforeEach, describe, expect, it } from 'vitest'
import { buildTrendShareCard } from './shareCard.js'

/**
 * The trend share card.
 *
 * <p>jsdom has no 2D context, which turns out to be the useful thing here: a
 * recording stub says exactly what was drawn, so these assert on the marks that
 * land on the canvas rather than on the arguments handed to the builder. The
 * layout is not tested -- pixels need eyes -- but what is present, what is
 * absent, and what stays inside the frame all are.
 */

const MARGIN_X = 86
const WIDTH = 1080
const CHART_TOP = 790
const CHART_BOTTOM = 1010
const INK = '#2b2a28'
const STAMP_RED = '#b23b3b'
const GHOST = 'rgba(43, 42, 40, 0.07)'

/**
 * A 2D context that remembers. Style properties are snapshotted per call
 * because fillStyle at draw time is what decides whether a bar means "over
 * budget", and a property read after the fact would report the last value set.
 */
function recordingContext() {
  const calls = []
  const stack = []
  let state = {}
  return new Proxy(
    {},
    {
      get(_target, prop) {
        if (prop === 'calls') return calls
        if (prop === 'measureText') return (text) => ({ width: String(text).length * 18 })
        if (prop === 'save') {
          return () => stack.push({ ...state })
        }
        if (prop === 'restore') {
          return () => {
            state = stack.pop() ?? {}
          }
        }
        if (prop in state) return state[prop]
        return (...args) => {
          calls.push({ method: prop, args, fillStyle: state.fillStyle, strokeStyle: state.strokeStyle })
        }
      },
      set(_target, prop, value) {
        state[prop] = value
        return true
      },
    },
  )
}

function week({ overBudget = [], unlogged = [], calories = 1800 } = {}) {
  return [...Array(7)].map((_, i) => ({
    date: `2026-08-${14 + i}`,
    logged: !unlogged.includes(i),
    calories: unlogged.includes(i) ? 0 : overBudget.includes(i) ? 2400 : calories,
    overBudget: overBudget.includes(i),
  }))
}

function args(overrides = {}) {
  return {
    brandTitle: 'DuaBiskutTelur',
    periodLabel: 'Week',
    rangeLabel: '14 Aug – 20 Aug',
    period: 'week',
    grade: 'B',
    daysLogged: 6,
    daysInWindow: 7,
    mealCount: 17,
    rows: [
      { label: 'Days', value: '6/7' },
      { label: 'Avg daily', value: 1840 },
      { label: 'Protein', value: '78g' },
      { label: 'Meals', value: 17 },
      { label: 'Vegetables', value: 11 },
    ],
    days: week(),
    calorieBudget: 2000,
    chartLabel: 'Daily calories',
    shareText: 'I logged 6 of 7 days!',
    ...overrides,
  }
}

describe('the trend share card', () => {
  let ctx

  beforeEach(() => {
    ctx = recordingContext()
    HTMLCanvasElement.prototype.getContext = () => ctx
    HTMLCanvasElement.prototype.toBlob = (callback) => callback(new Blob(['png'], { type: 'image/png' }))
  })

  const drawn = () => ctx.calls.filter((c) => c.method === 'fillText').map((c) => String(c.args[0]))
  const inBand = () =>
    ctx.calls.filter(
      (c) => c.method === 'fillRect' && c.args[1] >= CHART_TOP - 1 && c.args[1] + c.args[3] <= CHART_BOTTOM + 1,
    )
  // One per day, whichever way that day was drawn -- the baseline stub under a
  // ghost column is decoration on top of a slot, not a slot of its own.
  const columns = () => inBand().filter((c) => [INK, STAMP_RED, GHOST].includes(c.fillStyle))
  const bars = () => inBand().filter((c) => c.fillStyle === INK || c.fillStyle === STAMP_RED)

  it('renders a PNG and hands back the caption to send with it', async () => {
    const { blob, shareText } = await buildTrendShareCard(args())

    expect(blob.type).toBe('image/png')
    expect(shareText).toBe('I logged 6 of 7 days!')
  })

  /** Every figure below is meaningless without the window it was measured over. */
  it('names the window it covers', async () => {
    await buildTrendShareCard(args())

    expect(drawn()).toContain('WEEK')
    expect(drawn()).toContain('14 Aug – 20 Aug')
    expect(drawn()).toContain('DUABISKUTTELUR')
  })

  it('stamps the average grade', async () => {
    await buildTrendShareCard(args())

    expect(drawn()).toContain('B')
    expect(drawn()).toContain('AVG GRADE')
  })

  /**
   * A window can be long enough to report on and still hold too few meals to
   * average a grade. The stamp then has to carry something true rather than a
   * grade nobody earned.
   */
  it('stamps how much of the window was logged when no grade was earned', async () => {
    await buildTrendShareCard(args({ grade: null }))

    expect(drawn()).toContain('6/7')
    expect(drawn()).toContain('DAYS')
    expect(drawn()).not.toContain('AVG GRADE')
  })

  /** The same "not enough to say" rule the tiles follow: absent, not zero. */
  it('leaves out a figure the report could not compute', async () => {
    await buildTrendShareCard(args({
      rows: [
        { label: 'Avg daily', value: 1840 },
        { label: 'Protein', value: null },
        { label: 'Meals', value: 17 },
      ],
    }))

    expect(drawn()).not.toContain('Protein')
    expect(drawn()).toContain('Avg daily')
    expect(drawn()).toContain('Meals')
  })

  it('draws a bar for every logged day and a ghost column for one that was never logged', async () => {
    await buildTrendShareCard(args({ days: week({ unlogged: [2, 5] }) }))

    const ghosts = inBand().filter((c) => c.fillStyle === GHOST)
    expect(columns()).toHaveLength(7)
    expect(bars()).toHaveLength(5)
    expect(ghosts).toHaveLength(2)
    // Full height: a missing day holds its slot instead of vanishing from it.
    expect(ghosts.every((c) => c.args[3] === CHART_BOTTOM - CHART_TOP)).toBe(true)
  })

  it('marks a day that went over budget', async () => {
    await buildTrendShareCard(args({ days: week({ overBudget: [3] }) }))

    expect(bars().filter((c) => c.fillStyle === STAMP_RED)).toHaveLength(1)
  })

  /**
   * The scaling decision, pinned.
   *
   * <p>The on-screen chart scales to the budget and lets an over-budget bar
   * overflow its box. A static image has nowhere to overflow to, so it scales
   * to the biggest day instead -- otherwise the bar would be clipped flat at
   * the budget line, turning the one thing the chart exists to show into the
   * one thing it hides.
   */
  it('draws an over-budget day above the budget line rather than clipped at it', async () => {
    const days = week()
    days[3] = { date: '2026-08-17', logged: true, calories: 3000, overBudget: true }

    await buildTrendShareCard(args({ days, calorieBudget: 2000 }))

    const budgetLine = ctx.calls.find(
      (c) => c.method === 'moveTo' && c.args[0] === MARGIN_X && c.args[1] > CHART_TOP && c.args[1] < CHART_BOTTOM,
    )
    const tallest = Math.min(...bars().map((c) => c.args[1]))

    expect(budgetLine).toBeTruthy()
    expect(tallest).toBeLessThan(budgetLine.args[1])
  })

  /** Thirty bars have to fit the same frame seven do. */
  it('keeps a month inside the chart band and the margins', async () => {
    const days = [...Array(30)].map((_, i) => ({
      date: `2026-08-${i + 1}`,
      logged: i % 5 !== 0,
      calories: i % 5 === 0 ? 0 : 1500 + i * 40,
      overBudget: i % 7 === 0,
    }))

    await buildTrendShareCard(args({ days, period: 'month', periodLabel: 'Month' }))

    expect(columns()).toHaveLength(30)
    for (const bar of columns()) {
      expect(bar.args[1]).toBeGreaterThanOrEqual(CHART_TOP)
      expect(bar.args[0] + bar.args[2]).toBeLessThanOrEqual(WIDTH - MARGIN_X + 0.01)
    }
  })
})
