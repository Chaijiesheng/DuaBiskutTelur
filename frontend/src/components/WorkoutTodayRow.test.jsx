import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))
const fetchWorkoutGlance = vi.fn()
vi.mock('../api.js', () => ({ fetchWorkoutGlance: (...a) => fetchWorkoutGlance(...a) }))

const { default: WorkoutTodayRow } = await import('./WorkoutTodayRow.jsx')

function renderRow() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route index element={<WorkoutTodayRow />} />
        <Route path="workout" element={<div>workout tab</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function glance(overrides = {}) {
  return {
    hasProfile: true,
    trainingDay: true,
    session: {
      id: 1,
      title: 'Full Body',
      minutes: 30,
      status: 'planned',
      completedSets: 0,
      totalSets: 11,
    },
    ...overrides,
  }
}

beforeEach(() => {
  fetchWorkoutGlance.mockReset()
  fetchWorkoutGlance.mockResolvedValue(glance())
})

describe("the workout row on the Snap tab's Today card", () => {
  it('shows today’s session and how to open it', async () => {
    renderRow()

    expect(await screen.findByText('Full Body · workout.minutesChip:30')).toBeTruthy()
    expect(screen.getByText('workout.glancePlanned')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'workout.glanceOpen' })).toBeTruthy()
  })

  it('opens the workout tab', async () => {
    renderRow()
    await screen.findByText('Full Body · workout.minutesChip:30')

    await userEvent.click(screen.getByRole('button', { name: 'workout.glanceOpen' }))

    expect(screen.getByText('workout tab')).toBeTruthy()
  })

  /**
   * The design's constraint, asserted as a size limit: this must stay one row.
   * The workout should be visible from the food screen without the food screen
   * becoming a fitness screen — so no exercise list, no progress ring.
   */
  it('stays one row rather than becoming a card', async () => {
    const { container } = renderRow()
    await screen.findByText('Full Body · workout.minutesChip:30')

    expect(container.querySelectorAll('button')).toHaveLength(1)
    expect(container.querySelectorAll('ul, ol')).toHaveLength(0)
    // Two lines of text plus the button label, and nothing else.
    expect(container.querySelectorAll('p')).toHaveLength(2)
  })

  it('reflects a session already under way', async () => {
    fetchWorkoutGlance.mockResolvedValue(
      glance({ session: { ...glance().session, status: 'in_progress', completedSets: 4 } }),
    )
    renderRow()

    expect(await screen.findByText('workout.glanceInProgress:4,11')).toBeTruthy()
  })

  it('reflects a finished and a skipped session', async () => {
    fetchWorkoutGlance.mockResolvedValue(
      glance({ session: { ...glance().session, status: 'completed' } }),
    )
    const { unmount } = renderRow()
    expect(await screen.findByText('workout.glanceDone')).toBeTruthy()
    unmount()

    fetchWorkoutGlance.mockResolvedValue(
      glance({ session: { ...glance().session, status: 'skipped' } }),
    )
    renderRow()
    expect(await screen.findByText('workout.glanceSkipped')).toBeTruthy()
  })

  /**
   * With no stored session these two states are the same absence in the
   * database, and conflating them would either nag somebody on their rest day
   * or let a training day pass in silence.
   */
  it('tells an unopened training day apart from a rest day', async () => {
    fetchWorkoutGlance.mockResolvedValue(glance({ session: null, trainingDay: true }))
    const { unmount } = renderRow()
    expect(await screen.findByText('workout.glanceNotPlanned')).toBeTruthy()
    unmount()

    fetchWorkoutGlance.mockResolvedValue(glance({ session: null, trainingDay: false }))
    renderRow()
    expect(await screen.findByText('workout.glanceRestDay')).toBeTruthy()
  })

  /**
   * The Snap tab is the screen people open most. A prompt here for a feature
   * they have already walked past would be an advert, not a summary.
   */
  it('renders nothing before workout setup', async () => {
    fetchWorkoutGlance.mockResolvedValue({ hasProfile: false, trainingDay: false, session: null })
    const { container } = renderRow()

    await waitFor(() => expect(fetchWorkoutGlance).toHaveBeenCalled())
    expect(container.textContent).toBe('')
  })

  /**
   * A workout fetch failing must not put an error in front of somebody who
   * opened the app to photograph their lunch.
   */
  it('stays silent when its own fetch fails', async () => {
    fetchWorkoutGlance.mockRejectedValue(new Error('offline'))
    const { container } = renderRow()

    await waitFor(() => expect(fetchWorkoutGlance).toHaveBeenCalled())
    expect(container.textContent).toBe('')
  })

  /**
   * The reason this endpoint exists. `/api/workout/today` plans a session — and
   * fires a Gemini call for the coach note — so pointing the home screen at it
   * would cost a model call per user per app open.
   */
  it('never asks for today, which would plan a workout on every app open', async () => {
    renderRow()
    await screen.findByText('Full Body · workout.minutesChip:30')

    expect(Object.keys(await import('../api.js'))).toEqual(['fetchWorkoutGlance'])
  })
})
