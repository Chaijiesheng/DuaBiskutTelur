import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))
const fetchTrendReport = vi.fn()
const exportTrendPdf = vi.fn()
vi.mock('../api.js', () => ({
  fetchTrendReport: (...a) => fetchTrendReport(...a),
  exportTrendPdf: (...a) => exportTrendPdf(...a),
}))
const buildTrendShareCard = vi.fn()
vi.mock('../shareCard.js', () => ({
  buildTrendShareCard: (...a) => buildTrendShareCard(...a),
  downloadBlob: vi.fn(),
}))

const { default: TrendReport } = await import('./TrendReport.jsx')

function days(n, { from = '2026-08-14', calories = 1800, logged = true, overBudget = false } = {}) {
  const start = new Date(`${from}T12:00:00`)
  return [...Array(n)].map((_, i) => {
    const d = new Date(start.getTime() + i * 86400000)
    return {
      date: d.toISOString().slice(0, 10),
      logged,
      mealCount: logged ? 2 : 0,
      calories: logged ? calories : 0,
      overBudget,
    }
  })
}

function report(overrides = {}) {
  return {
    period: 'week',
    from: '2026-08-14',
    to: '2026-08-20',
    daysInWindow: 7,
    calorieBudget: 2000,
    enoughData: true,
    days: days(7),
    totals: {
      daysLogged: 6,
      mealCount: 17,
      avgDailyCalories: 1840,
      avgScore: 74,
      avgGrade: 'B',
      avgDailyProtein: 78,
      vegetableServings: 11,
      fruitDays: 4,
      avgDailyWaterMl: 1900,
      waterDaysOnTarget: 3,
      workoutsDone: 3,
      workoutMinutes: 82,
      weightChangeKg: -0.6,
      latestWeightKg: 71.4,
    },
    previous: null,
    gradeMix: { 'A+': 1, A: 3, B: 8, C: 4, D: 1 },
    bestDayGrade: 'A',
    bestDayDate: '2026-08-18',
    narrative: 'You logged 6 of 7 days.',
    narrativeSource: 'rules',
    ...overrides,
  }
}

describe('the trend report', () => {
  beforeEach(() => {
    fetchTrendReport.mockReset()
    exportTrendPdf.mockReset()
    exportTrendPdf.mockResolvedValue(undefined)
    buildTrendShareCard.mockReset()
    buildTrendShareCard.mockResolvedValue({ blob: new Blob(), shareText: 'I logged 6 of 7 days!' })
    fetchTrendReport.mockResolvedValue(report())
  })

  it('opens on the week and asks the server for it', async () => {
    render(<TrendReport />)

    await screen.findByText('trends.avgDaily')
    expect(fetchTrendReport).toHaveBeenCalledWith('week', 'en')
  })

  it('refetches when the period changes', async () => {
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    await userEvent.click(screen.getByRole('tab', { name: 'trends.month' }))

    await waitFor(() => expect(fetchTrendReport).toHaveBeenLastCalledWith('month', 'en'))
  })

  /**
   * The rule the whole report rests on: a null from the server means "not
   * enough to say", never zero. Rendering it as 0 turns missing data into a
   * reported shortfall — a user whose meals predate the protein column would
   * be told they eat no protein.
   */
  it('hides a metric the server could not compute rather than showing zero', async () => {
    fetchTrendReport.mockResolvedValue(
      report({
        totals: {
          ...report().totals,
          avgDailyProtein: null,
          vegetableServings: null,
          fruitDays: null,
          weightChangeKg: null,
          latestWeightKg: null,
        },
      }),
    )
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    expect(screen.queryByText('trends.protein')).toBeNull()
    expect(screen.queryByText('trends.vegetables')).toBeNull()
    expect(screen.queryByText('trends.weight')).toBeNull()
    // The metrics that did compute are still there.
    expect(screen.getByText('trends.water')).toBeTruthy()
  })

  it('says so when there is not enough data to call it a trend', async () => {
    fetchTrendReport.mockResolvedValue(report({ enoughData: false }))
    render(<TrendReport />)

    expect(await screen.findByText('trends.thinTitle')).toBeTruthy()
  })

  it('shows no thin-data notice once there is enough', async () => {
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    expect(screen.queryByText('trends.thinTitle')).toBeNull()
  })

  /** No previous period means no deltas — not deltas against zero. */
  it('omits comparisons when the server sent no previous period', async () => {
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    expect(screen.queryByText(/trends\.vsPrevious/)).toBeNull()
  })

  it('shows a comparison when the previous period is present', async () => {
    fetchTrendReport.mockResolvedValue(
      report({ previous: { ...report().totals, avgDailyCalories: 2050, mealCount: 15 } }),
    )
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    expect(screen.getAllByText(/trends\.vsPrevious/).length).toBeGreaterThan(0)
  })

  /**
   * Every day in the window gets a column, logged or not, so a gap on Saturday
   * renders as a gap instead of shuffling Sunday into its place.
   */
  it('draws a column for every day in the window', async () => {
    const mixed = days(7)
    mixed[2] = { ...mixed[2], logged: false, mealCount: 0, calories: 0 }
    fetchTrendReport.mockResolvedValue(report({ days: mixed }))
    const { container } = render(<TrendReport />)
    await screen.findByText('trends.dailyCalories')

    const columns = container.querySelectorAll('.h-24 > div')
    expect(columns).toHaveLength(7)
  })

  it('renders all five grades even when some are unused', async () => {
    fetchTrendReport.mockResolvedValue(
      report({ gradeMix: { 'A+': 0, A: 2, B: 5, C: 0, D: 0 } }),
    )
    render(<TrendReport />)
    // Scoped to the mix: "B" also appears as the average-grade tile above it.
    const mix = (await screen.findByText('trends.gradeMix')).closest('div')

    for (const grade of ['A+', 'A', 'B', 'C', 'D']) {
      expect(within(mix).getByText(grade), `${grade} is missing from the mix`).toBeTruthy()
    }
  })

  it('shows the written summary when the server sent one', async () => {
    render(<TrendReport />)

    expect(await screen.findByText('You logged 6 of 7 days.')).toBeTruthy()
  })

  /**
   * A thin report has no paragraph, and an empty string must render as nothing
   * rather than as an empty bubble sitting under the charts.
   */
  it('renders no summary block when there is no paragraph', async () => {
    fetchTrendReport.mockResolvedValue(report({ narrative: '', narrativeSource: 'rules' }))
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    expect(screen.queryByText(/You logged/)).toBeNull()
  })

  it('exports the period currently on screen', async () => {
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')
    await userEvent.click(screen.getByRole('tab', { name: 'trends.month' }))
    await screen.findByText('trends.avgDaily')

    await userEvent.click(screen.getByRole('button', { name: 'trends.exportPdf' }))

    expect(exportTrendPdf).toHaveBeenCalledWith('month')
  })

  /**
   * A document covering three days is not something anyone takes to a doctor,
   * and offering it invites the user to make one and conclude the feature is thin.
   */
  it('offers no export until there is a trend worth exporting', async () => {
    fetchTrendReport.mockResolvedValue(report({ enoughData: false }))
    render(<TrendReport />)
    await screen.findByText('trends.thinTitle')

    expect(screen.queryByRole('button', { name: 'trends.exportPdf' })).toBeNull()
  })

  it('says so when the export fails instead of failing silently', async () => {
    exportTrendPdf.mockRejectedValue(new Error('offline'))
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    await userEvent.click(screen.getByRole('button', { name: 'trends.exportPdf' }))

    expect(await screen.findByText('trends.exportFailed')).toBeTruthy()
  })

  /**
   * The one figure that must never leave for a group chat.
   *
   * <p>Weight is on the report and it is in the PDF, because the user saves
   * that and hands it to somebody they chose. A share card goes somewhere the
   * user does not control, and a button that quietly carries their weight there
   * makes that decision for them once, irreversibly.
   */
  it('never puts body weight on the share card', async () => {
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    await userEvent.click(screen.getByRole('button', { name: 'trends.share' }))

    await waitFor(() => expect(buildTrendShareCard).toHaveBeenCalled())
    const card = JSON.stringify(buildTrendShareCard.mock.calls[0][0])
    expect(card).not.toContain('71.4')
    expect(card).not.toContain('eight')
  })

  it('shares the period currently on screen, with its own figures', async () => {
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')
    await userEvent.click(screen.getByRole('tab', { name: 'trends.month' }))
    await screen.findByText('trends.avgDaily')

    await userEvent.click(screen.getByRole('button', { name: 'trends.share' }))

    await waitFor(() => expect(buildTrendShareCard).toHaveBeenCalled())
    const card = buildTrendShareCard.mock.calls[0][0]
    expect(card.period).toBe('month')
    expect(card.grade).toBe('B')
    expect(card.daysLogged).toBe(6)
    expect(card.days).toHaveLength(7)
  })

  /** Same threshold as the PDF: nothing to carry anywhere yet. */
  it('offers no share until there is a trend worth sharing', async () => {
    fetchTrendReport.mockResolvedValue(report({ enoughData: false }))
    render(<TrendReport />)
    await screen.findByText('trends.thinTitle')

    expect(screen.queryByRole('button', { name: 'trends.share' })).toBeNull()
  })

  it('says so when the share fails instead of failing silently', async () => {
    buildTrendShareCard.mockRejectedValue(new Error('canvas gone'))
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    await userEvent.click(screen.getByRole('button', { name: 'trends.share' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('results.shareError')
  })

  /** Backing out of the OS share sheet is a decision, not a failure. */
  it('stays quiet when the user dismisses the share sheet', async () => {
    buildTrendShareCard.mockRejectedValue(
      Object.assign(new Error('cancelled'), { name: 'AbortError' }))
    render(<TrendReport />)
    await screen.findByText('trends.avgDaily')

    await userEvent.click(screen.getByRole('button', { name: 'trends.share' }))

    await waitFor(() => expect(buildTrendShareCard).toHaveBeenCalled())
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('reports a failure instead of rendering an empty report', async () => {
    fetchTrendReport.mockRejectedValue(new Error('offline'))
    render(<TrendReport />)

    expect(await screen.findByText('trends.couldntLoad')).toBeTruthy()
  })
})
