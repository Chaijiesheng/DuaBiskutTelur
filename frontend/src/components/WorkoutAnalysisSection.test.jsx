import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))
const fetchWorkoutStats = vi.fn()
vi.mock('../api.js', () => ({ fetchWorkoutStats: (...a) => fetchWorkoutStats(...a) }))

const { default: WorkoutAnalysisSection } = await import('./WorkoutAnalysisSection.jsx')

function stats(overrides = {}) {
  return {
    hasProfile: true,
    workoutsThisMonth: 12,
    minutesThisMonth: 348,
    consistencyPercent: 80,
    expectedThisMonth: 15,
    streakDays: 5,
    bestStreakDays: 9,
    progressions: [
      { key: 'plank', name: 'Plank', from: '3 × 25s', to: '3 × 30s' },
      { key: 'push_up', name: 'Push Up', from: '3 × 6', to: '3 × 8' },
    ],
    ...overrides,
  }
}

beforeEach(() => {
  fetchWorkoutStats.mockReset()
  fetchWorkoutStats.mockResolvedValue(stats())
})

describe('workout figures on the analysis tab', () => {
  it('shows the month, consistency, minutes and streak', async () => {
    render(<WorkoutAnalysisSection isVisitor={false} />)

    expect(await screen.findByText('workout.analysisHeading')).toBeTruthy()
    expect(screen.getByText('12')).toBeTruthy()
    expect(screen.getByText('348')).toBeTruthy()
    expect(screen.getByText('80')).toBeTruthy()
    expect(screen.getByText('workout.statConsistencySub:12,15')).toBeTruthy()
  })

  /** A current streak of 5 only means something against what this person has managed before. */
  it('shows the personal best beside the current streak', async () => {
    render(<WorkoutAnalysisSection isVisitor={false} />)

    expect(await screen.findByText('workout.statBest:9')).toBeTruthy()
    expect(screen.getByText('5')).toBeTruthy()
  })

  it('lists exercises whose dose has gone up', async () => {
    render(<WorkoutAnalysisSection isVisitor={false} />)

    expect(await screen.findByText('Plank')).toBeTruthy()
    expect(screen.getByText(/3 × 25s/)).toBeTruthy()
    expect(screen.getByText(/3 × 30s/)).toBeTruthy()
  })

  it('explains the empty progression list rather than showing a blank card', async () => {
    fetchWorkoutStats.mockResolvedValue(stats({ progressions: [] }))
    render(<WorkoutAnalysisSection isVisitor={false} />)

    expect(await screen.findByText('workout.gettingStrongerEmpty')).toBeTruthy()
  })

  /**
   * The Analysis tab belongs to food first. Four zeroes about a feature the
   * user has not opted into would be an advert wearing a stat grid.
   */
  it('renders nothing at all before workout setup', async () => {
    fetchWorkoutStats.mockResolvedValue(stats({ hasProfile: false }))
    const { container } = render(<WorkoutAnalysisSection isVisitor={false} />)

    await waitFor(() => expect(fetchWorkoutStats).toHaveBeenCalled())
    expect(container.textContent).toBe('')
  })

  it('renders nothing for a visitor, and does not call the API', () => {
    const { container } = render(<WorkoutAnalysisSection isVisitor />)

    expect(fetchWorkoutStats).not.toHaveBeenCalled()
    expect(container.textContent).toBe('')
  })

  /**
   * A workout fetch failing must not put an error into a screen that is mostly
   * about food and is otherwise working fine.
   */
  it('stays silent when its own fetch fails', async () => {
    fetchWorkoutStats.mockRejectedValue(new Error('offline'))
    const { container } = render(<WorkoutAnalysisSection isVisitor={false} />)

    await waitFor(() => expect(fetchWorkoutStats).toHaveBeenCalled())
    expect(container.textContent).toBe('')
  })
})
