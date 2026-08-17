import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({ useLanguage: () => ({ lang: 'en' }) }))

const api = {
  fetchWorkoutToday: vi.fn(),
  logWorkoutSet: vi.fn(),
  saveWorkoutProfile: vi.fn(),
  startWorkoutSession: vi.fn(),
  skipWorkoutSession: vi.fn(),
  replaceWorkoutExercise: vi.fn(),
  completeWorkoutSession: vi.fn(),
}
vi.mock('../api.js', () => api)

const { WorkoutProvider, useWorkout } = await import('./WorkoutContext.jsx')

const QUEUE_KEY = 'dbt_workout_set_queue'

function today(completedSets = []) {
  return {
    hasProfile: true,
    coach: { summary: 'note', factors: [] },
    coachSource: 'ai',
    week: [],
    stats: { weightKg: null, workoutsThisMonth: 0, streakDays: 0 },
    session: {
      id: 9,
      title: 'Full Body',
      minutes: 30,
      status: 'planned',
      totalSets: 4,
      completedSets: completedSets.length,
      exercises: [
        { position: 0, key: 'squat', name: 'Squat', target: 'Legs', sets: 2, reps: 12, unit: 'reps', cue: '', completedSets },
        { position: 1, key: 'plank', name: 'Plank', target: 'Core', sets: 2, reps: 30, unit: 'sec', cue: '', completedSets: [] },
      ],
    },
  }
}

/** A probe that renders the bits of context state these assertions care about. */
function Probe() {
  const { session, pendingSets, logSet } = useWorkout()
  return (
    <div>
      <span data-testid="done">{session ? session.exercises[0].completedSets.join(',') : ''}</span>
      <span data-testid="total">{session?.completedSets ?? ''}</span>
      <span data-testid="status">{session?.status ?? ''}</span>
      <span data-testid="pending">{pendingSets}</span>
      <button type="button" onClick={() => logSet(0, 0, true)}>log 0</button>
      <button type="button" onClick={() => logSet(0, 1, true)}>log 1</button>
      <button type="button" onClick={() => logSet(0, 0, false)}>unlog 0</button>
    </div>
  )
}

function renderProvider() {
  return render(
    <WorkoutProvider isVisitor={false}>
      <Probe />
    </WorkoutProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  Object.values(api).forEach((fn) => fn.mockReset())
  api.fetchWorkoutToday.mockResolvedValue(today())
  api.logWorkoutSet.mockImplementation((id, position, setIndex) =>
    Promise.resolve({ ...today([setIndex]).session, status: 'in_progress' }),
  )
})

describe('logging a set', () => {
  it('ticks the set before the server answers', async () => {
    // A request that never settles: whatever appears is purely optimistic.
    api.logWorkoutSet.mockImplementation(() => new Promise(() => {}))
    renderProvider()
    await screen.findByText('Full Body', { exact: false }).catch(() => {})
    await waitFor(() => expect(screen.getByTestId('total').textContent).toBe('0'))

    await userEvent.click(screen.getByText('log 0'))

    expect(screen.getByTestId('done').textContent).toBe('0')
    expect(screen.getByTestId('total').textContent).toBe('1')
  })

  /** Logging a set is starting the session; the dashboard must not still say "start". */
  it('moves the session to in progress on the first set', async () => {
    renderProvider()
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('planned'))

    await userEvent.click(screen.getByText('log 0'))

    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('in_progress'))
  })

  it('unticks a set that is taken back', async () => {
    api.logWorkoutSet.mockImplementation(() => new Promise(() => {}))
    renderProvider()
    await waitFor(() => expect(screen.getByTestId('total').textContent).toBe('0'))

    await userEvent.click(screen.getByText('log 0'))
    await userEvent.click(screen.getByText('unlog 0'))

    expect(screen.getByTestId('done').textContent).toBe('')
    expect(screen.getByTestId('total').textContent).toBe('0')
  })
})

describe('sets logged with no connection', () => {
  /**
   * The set genuinely happened. Rolling the tick back because the network
   * failed would be the app telling the user they did not do something they
   * just did — so it stays ticked and the write is queued instead.
   */
  it('keeps the set ticked and queues the write', async () => {
    api.logWorkoutSet.mockRejectedValue(new Error('offline'))
    renderProvider()
    await waitFor(() => expect(screen.getByTestId('total').textContent).toBe('0'))

    await userEvent.click(screen.getByText('log 0'))

    await waitFor(() => expect(screen.getByTestId('pending').textContent).toBe('1'))
    expect(screen.getByTestId('done').textContent).toBe('0')
    expect(JSON.parse(localStorage.getItem(QUEUE_KEY))).toEqual([
      { sessionId: 9, exercisePosition: 0, setIndex: 0, done: true },
    ])
  })

  /** One entry per set. Tapping the same set on and off must not queue both. */
  it('keeps only the latest intent for a given set', async () => {
    api.logWorkoutSet.mockRejectedValue(new Error('offline'))
    renderProvider()
    await waitFor(() => expect(screen.getByTestId('total').textContent).toBe('0'))

    await userEvent.click(screen.getByText('log 0'))
    await waitFor(() => expect(screen.getByTestId('pending').textContent).toBe('1'))
    await userEvent.click(screen.getByText('unlog 0'))

    await waitFor(() => {
      const queue = JSON.parse(localStorage.getItem(QUEUE_KEY))
      expect(queue).toHaveLength(1)
      expect(queue[0].done).toBe(false)
    })
  })

  it('queues each distinct set separately', async () => {
    api.logWorkoutSet.mockRejectedValue(new Error('offline'))
    renderProvider()
    await waitFor(() => expect(screen.getByTestId('total').textContent).toBe('0'))

    await userEvent.click(screen.getByText('log 0'))
    await userEvent.click(screen.getByText('log 1'))

    await waitFor(() => expect(screen.getByTestId('pending').textContent).toBe('2'))
  })

  it('replays the queue when the connection comes back', async () => {
    api.logWorkoutSet.mockRejectedValue(new Error('offline'))
    renderProvider()
    await waitFor(() => expect(screen.getByTestId('total').textContent).toBe('0'))
    await userEvent.click(screen.getByText('log 0'))
    await waitFor(() => expect(screen.getByTestId('pending').textContent).toBe('1'))

    api.logWorkoutSet.mockResolvedValue({ ...today([0]).session, status: 'in_progress' })
    window.dispatchEvent(new Event('online'))

    await waitFor(() => expect(screen.getByTestId('pending').textContent).toBe('0'))
    expect(localStorage.getItem(QUEUE_KEY)).toBeNull()
  })

  /** A replay that fails again must stay queued rather than being dropped. */
  it('keeps a failed replay for the next attempt', async () => {
    api.logWorkoutSet.mockRejectedValue(new Error('offline'))
    renderProvider()
    await waitFor(() => expect(screen.getByTestId('total').textContent).toBe('0'))
    await userEvent.click(screen.getByText('log 0'))
    await waitFor(() => expect(screen.getByTestId('pending').textContent).toBe('1'))

    window.dispatchEvent(new Event('online'))

    await waitFor(() => expect(JSON.parse(localStorage.getItem(QUEUE_KEY))).toHaveLength(1))
  })

  /** A queue can outlive the app being closed offline, so it is flushed on mount too. */
  it('flushes a queue left over from a previous visit', async () => {
    localStorage.setItem(
      QUEUE_KEY,
      JSON.stringify([{ sessionId: 9, exercisePosition: 1, setIndex: 0, done: true }]),
    )
    api.logWorkoutSet.mockResolvedValue(today([]).session)

    renderProvider()

    await waitFor(() => expect(api.logWorkoutSet).toHaveBeenCalledWith(9, 1, 0, true))
    await waitFor(() => expect(screen.getByTestId('pending').textContent).toBe('0'))
  })
})

describe('signing out', () => {
  it("drops the previous account's plan rather than leaving it on screen", async () => {
    const { rerender } = renderProvider()
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('planned'))

    rerender(
      <WorkoutProvider isVisitor>
        <Probe />
      </WorkoutProvider>,
    )

    expect(screen.getByTestId('status').textContent).toBe('')
  })
})
