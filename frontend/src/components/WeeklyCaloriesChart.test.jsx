import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, arg) => (key === 'analysis.weekAvg' ? `Avg ${arg} kcal` : key),
    lang: 'en',
  }),
}))
vi.mock('../theme/ThemeContext.jsx', () => ({ useTheme: () => ({ theme: 'dark' }) }))

const { default: WeeklyCaloriesChart, getWeeklyDays } = await import('./WeeklyCaloriesChart.jsx')

const now = Date.now()
const day = (back, calories) => ({
  id: back, createdAt: new Date(now - back * 86400000).toISOString(), calories,
})
const ENTRIES = [day(6, 1400), day(5, 1850), day(4, 2600), day(3, 900), day(2, 1500), day(1, 1650), day(0, 1200)]

const bars = () => screen.getAllByRole('button')

function renderChart(entries = ENTRIES) {
  return render(<WeeklyCaloriesChart entries={entries} dailyBudget={2000} />)
}

/**
 * This chart was an SVG with a `viewBox` and a `<g role="button">` per bar, and
 * that shape caused three separate defects at once. It is flex-and-divs now;
 * these pin the properties that made the rewrite worth doing.
 */
describe('WeeklyCaloriesChart', () => {
  it('renders one control per day of the week', () => {
    renderChart()
    expect(bars()).toHaveLength(7)
  })

  it('uses real buttons, so focus-visible actually applies', () => {
    renderChart()

    // The old bars were <g role="button" tabIndex={0}>. :focus-visible never
    // matches a pointer press on those, so the styled green ring never showed
    // and the browser drew its own — around the group's full-height bounding
    // box, day label included.
    for (const bar of bars()) {
      expect(bar.tagName).toBe('BUTTON')
      expect(bar.className).toContain('focus-visible:ring-2')
    }
  })

  it('fills the width it is given rather than letterboxing', () => {
    const { container } = renderChart()

    // The old viewBox="0 0 100 54" inside h-24 w-full scaled uniformly under
    // xMidYMid meet, drawing the chart at 43% of the card and centring it.
    // Flex columns have no intrinsic aspect ratio to preserve.
    expect(container.querySelector('svg')).toBeNull()
    const row = container.querySelector('[aria-label]').parentElement
    expect(row.className).toContain('flex')
    for (const bar of bars()) expect(bar.className).toContain('flex-1')
  })

  it('marks the selected day with more than a shade of the bar colour', async () => {
    const user = userEvent.setup()
    renderChart()
    const bar = bars()[2]

    await user.click(bar)

    // Selection used to be a #15803d stroke on a #22c55e fill — green on green.
    // It is a filled column track now, which does not need two greens told apart.
    expect(bar).toHaveAttribute('aria-pressed', 'true')
    expect(bar.className).toMatch(/bg-slate-100|bg-slate-700/)
  })

  it('dims the other days so the selected one stands out', async () => {
    const user = userEvent.setup()
    renderChart()

    await user.click(bars()[2])

    expect(bars()[2].className).not.toContain('opacity-40')
    expect(bars()[0].className).toContain('opacity-40')
  })

  it('shows that day’s total, and returns to the average when deselected', async () => {
    const user = userEvent.setup()
    renderChart()

    await user.click(bars()[2])
    expect(screen.getByText(/2600 kcal/)).toBeInTheDocument()

    await user.click(bars()[2])
    // Tapping the same bar again clears it rather than trapping the reader on
    // one day with no way back to the summary.
    expect(screen.getByText(/^Avg /)).toBeInTheDocument()
  })

  it('names every bar for screen readers', () => {
    renderChart()
    for (const bar of bars()) {
      expect(bar.getAttribute('aria-label')).toMatch(/\d+ kcal$/)
    }
  })

  it('still gives a day with no meals a visible column', () => {
    const { container } = renderChart([])

    // Seven empty columns, not seven invisible ones — "logged nothing" is
    // information the chart should show.
    expect(bars()).toHaveLength(7)
    const heights = [...container.querySelectorAll('span[style*="height"]')]
      .map((s) => parseFloat(s.style.height))
    expect(heights).toHaveLength(7)
    expect(heights.every((h) => h > 0)).toBe(true)
  })
})

/** The bucketing is shared with AnalysisScreen, so it outlives any re-skin. */
describe('getWeeklyDays', () => {
  it('returns seven days, oldest first', () => {
    const days = getWeeklyDays(ENTRIES, 'en-US')
    expect(days).toHaveLength(7)
    expect(days[0].totalCalories).toBe(1400)
    expect(days[6].totalCalories).toBe(1200)
  })

  it('sums several meals landing on the same day', () => {
    const days = getWeeklyDays([day(1, 300), day(1, 200)], 'en-US')
    expect(days[5].totalCalories).toBe(500)
    expect(days[5].mealCount).toBe(2)
  })

  it('reports empty days rather than skipping them', () => {
    const days = getWeeklyDays([day(3, 700)], 'en-US')
    expect(days.filter((d) => d.mealCount === 0)).toHaveLength(6)
  })
})
