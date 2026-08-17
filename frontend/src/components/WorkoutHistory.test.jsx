import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))
vi.mock('./SignInBanner.jsx', () => ({ default: () => <div>sign in banner</div> }))
const fetchWorkoutHistory = vi.fn()
vi.mock('../api.js', () => ({ fetchWorkoutHistory: (...a) => fetchWorkoutHistory(...a) }))

const { default: WorkoutHistory } = await import('./WorkoutHistory.jsx')

const WEEK = [
  { date: '2026-08-17', label: 'M', minutes: 28 },
  { date: '2026-08-18', label: 'T', minutes: 0 },
  { date: '2026-08-19', label: 'W', minutes: 25 },
  { date: '2026-08-20', label: 'T', minutes: 0 },
  { date: '2026-08-21', label: 'F', minutes: 30 },
  { date: '2026-08-22', label: 'S', minutes: 0 },
  { date: '2026-08-23', label: 'S', minutes: 0 },
]

function entry(overrides = {}) {
  return {
    id: 1,
    date: '2026-08-17',
    title: 'Full Body',
    focus: 'full_body',
    level: 'beginner',
    minutes: 28,
    status: 'completed',
    completedSets: 11,
    totalSets: 11,
    ...overrides,
  }
}

beforeEach(() => {
  fetchWorkoutHistory.mockReset()
  fetchWorkoutHistory.mockResolvedValue({ entries: [entry()], week: WEEK, weekMinutes: 83 })
})

describe('workouts inside the history tab', () => {
  it('lists past sessions with what was done', async () => {
    render(<WorkoutHistory isVisitor={false} />)

    expect(await screen.findByText('Full Body')).toBeTruthy()
    expect(screen.getByText('workout.chipDone')).toBeTruthy()
    expect(screen.getByText('workout.minutesThisWeek:83')).toBeTruthy()
  })

  /**
   * The whole reason this reads its own endpoint. `/api/workout/today` plans a
   * session when there isn't one, so a History tab built on it would create
   * this morning's workout as a side effect of looking at last week.
   */
  it('never asks for today, which would plan a workout', async () => {
    render(<WorkoutHistory isVisitor={false} />)
    await screen.findByText('Full Body')

    expect(fetchWorkoutHistory).toHaveBeenCalledTimes(1)
    // The module mock exposes only this one function, so importing anything
    // else here would throw — this asserts the shape of that dependency.
    expect(Object.keys(await import('../api.js'))).toEqual(['fetchWorkoutHistory'])
  })

  /** A skipped day is part of an honest record; hiding it would flatter the list. */
  it('shows skipped sessions rather than hiding them', async () => {
    fetchWorkoutHistory.mockResolvedValue({
      entries: [entry({ status: 'skipped', completedSets: 0 })],
      week: WEEK,
      weekMinutes: 83,
    })
    render(<WorkoutHistory isVisitor={false} />)

    expect(await screen.findByText('workout.chipSkipped')).toBeTruthy()
  })

  /**
   * A session abandoned after two sets is neither done nor skipped, and calling
   * it either would be the record lying about what happened.
   */
  it('distinguishes a half-finished session from a finished one', async () => {
    fetchWorkoutHistory.mockResolvedValue({
      entries: [entry({ status: 'in_progress', completedSets: 2, totalSets: 11 })],
      week: WEEK,
      weekMinutes: 83,
    })
    render(<WorkoutHistory isVisitor={false} />)

    expect(await screen.findByText('workout.chipPartial')).toBeTruthy()
    expect(screen.getByText('workout.entrySets:2,11')).toBeTruthy()
  })

  it('says plainly when nothing has been logged', async () => {
    fetchWorkoutHistory.mockResolvedValue({
      entries: [],
      week: WEEK.map((d) => ({ ...d, minutes: 0 })),
      weekMinutes: 0,
    })
    render(<WorkoutHistory isVisitor={false} />)

    expect(await screen.findByText('workout.historyEmpty')).toBeTruthy()
    // The chart still renders — an empty week is a fact worth showing.
    expect(screen.getByText('workout.minutesTrained')).toBeTruthy()
  })

  /**
   * The bars are aria-hidden, so each day needs a readable value of its own —
   * and it has to be the FULL weekday name. The visible labels are narrow
   * letters, where "T" is both Tuesday and Thursday and "S" is both Saturday
   * and Sunday; announcing those would give a listener four columns a week they
   * cannot tell apart. This test caught exactly that.
   */
  it('announces each day of the chart unambiguously', async () => {
    render(<WorkoutHistory isVisitor={false} />)
    await screen.findByText('Full Body')

    expect(screen.getByText('Monday — workout.minutesChip:28')).toBeTruthy()
    expect(screen.getByText('Tuesday — workout.minutesChip:0')).toBeTruthy()
    expect(screen.getByText('Thursday — workout.minutesChip:0')).toBeTruthy()
    expect(screen.getByText('Saturday — workout.minutesChip:0')).toBeTruthy()
    expect(screen.getByText('Sunday — workout.minutesChip:0')).toBeTruthy()
  })

  it('asks a visitor to sign in rather than calling the API', () => {
    render(<WorkoutHistory isVisitor />)

    expect(fetchWorkoutHistory).not.toHaveBeenCalled()
    expect(screen.getByText('sign in banner')).toBeTruthy()
  })

  it('reports a failure instead of hanging on the skeleton', async () => {
    fetchWorkoutHistory.mockRejectedValue(new Error('offline'))
    render(<WorkoutHistory isVisitor={false} />)

    await waitFor(() => expect(screen.getByText('workout.loadError')).toBeTruthy())
  })
})
