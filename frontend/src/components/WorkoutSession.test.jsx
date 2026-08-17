import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))
const fetchWorkoutAlternatives = vi.fn()
vi.mock('../api.js', () => ({ fetchWorkoutAlternatives: (...a) => fetchWorkoutAlternatives(...a) }))

const { default: WorkoutSession } = await import('./WorkoutSession.jsx')

function exercise(position, name, sets, completedSets = []) {
  return {
    position,
    key: `key_${position}`,
    name,
    target: 'Legs',
    sets,
    reps: 12,
    unit: 'reps',
    cue: `Cue for ${name}`,
    completedSets,
  }
}

function session(exercises) {
  return {
    id: 7,
    title: 'Full Body',
    minutes: 30,
    status: 'in_progress',
    totalSets: exercises.reduce((sum, e) => sum + e.sets, 0),
    completedSets: exercises.reduce((sum, e) => sum + e.completedSets.length, 0),
    exercises,
  }
}

function renderSession(exercises, props = {}) {
  const handlers = {
    onExit: vi.fn(),
    onLogSet: vi.fn(),
    onReplace: vi.fn(),
    onFinish: vi.fn(),
  }
  const view = render(
    <WorkoutSession session={session(exercises)} online pendingSets={0} {...handlers} {...props} />,
  )
  return { ...handlers, view }
}

describe('workout session, set-led runner', () => {
  it('shows one exercise with its rep count and cue', () => {
    renderSession([exercise(0, 'Squat', 3), exercise(1, 'Push Up', 3)])

    expect(screen.getByRole('heading', { name: 'Squat' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Push Up' })).toBeNull()
    expect(screen.getByText('Cue for Squat')).toBeTruthy()
    expect(screen.getByText('workout.exerciseOf:1,2')).toBeTruthy()
  })

  it('logs the current set and moves to the next one', async () => {
    const { onLogSet } = renderSession([exercise(0, 'Squat', 3)])

    expect(screen.getByText('workout.setOf:1,3')).toBeTruthy()

    await userEvent.click(screen.getByRole('button', { name: 'workout.completeSet' }))

    expect(onLogSet).toHaveBeenCalledWith(0, 0, true)
    expect(screen.getByText('workout.setOf:2,3')).toBeTruthy()
  })

  it('moves to the next exercise after the last set of one', async () => {
    const { onLogSet } = renderSession([exercise(0, 'Squat', 1), exercise(1, 'Push Up', 2)])

    await userEvent.click(screen.getByRole('button', { name: 'workout.completeSet' }))

    expect(onLogSet).toHaveBeenCalledWith(0, 0, true)
    expect(screen.getByRole('heading', { name: 'Push Up' })).toBeTruthy()
  })

  it('finishes the workout after the very last set', async () => {
    const { onFinish } = renderSession([exercise(0, 'Squat', 1)])

    await userEvent.click(screen.getByRole('button', { name: 'workout.completeSet' }))

    expect(onFinish).toHaveBeenCalled()
  })

  /**
   * Closing the app mid-workout, or picking it up on a second device, has to
   * land on the set you were about to do. Restarting at exercise one would make
   * somebody redo work the server already has.
   */
  it('resumes at the first set that is not already logged', () => {
    renderSession([exercise(0, 'Squat', 3, [0, 1, 2]), exercise(1, 'Push Up', 3, [0])])

    expect(screen.getByRole('heading', { name: 'Push Up' })).toBeTruthy()
    expect(screen.getByText('workout.setOf:2,3')).toBeTruthy()
  })

  it('skips a whole exercise without logging any of its sets', async () => {
    const { onLogSet } = renderSession([exercise(0, 'Squat', 3), exercise(1, 'Push Up', 3)])

    await userEvent.click(screen.getByRole('button', { name: 'workout.skipExercise' }))

    expect(onLogSet).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: 'Push Up' })).toBeTruthy()
  })

  it('ends the workout when the last exercise is skipped', async () => {
    const { onFinish } = renderSession([exercise(0, 'Squat', 3)])

    await userEvent.click(screen.getByRole('button', { name: 'workout.skipExercise' }))

    expect(onFinish).toHaveBeenCalled()
  })

  it('tells the user their sets are being held locally when offline', () => {
    renderSession([exercise(0, 'Squat', 3)], { online: false, pendingSets: 2 })

    expect(screen.getByText(/workout\.offlineBody/)).toBeTruthy()
    expect(screen.getByText(/workout\.unsynced:2/)).toBeTruthy()
  })
})

describe('rest timer', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }))
  afterEach(() => vi.useRealTimers())

  it('appears between sets and counts down', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderSession([exercise(0, 'Squat', 3)])

    await user.click(screen.getByRole('button', { name: 'workout.completeSet' }))

    const dialog = screen.getByRole('dialog', { name: 'workout.restTitle' })
    expect(dialog).toBeTruthy()
    expect(screen.getByText('0:45')).toBeTruthy()

    await vi.advanceTimersByTimeAsync(3000)
    expect(screen.getByText('0:42')).toBeTruthy()
  })

  it('can be skipped, and dismisses itself when it runs out', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderSession([exercise(0, 'Squat', 3)])

    await user.click(screen.getByRole('button', { name: 'workout.completeSet' }))
    await user.click(screen.getByRole('button', { name: 'workout.restSkip' }))
    expect(screen.queryByRole('dialog', { name: 'workout.restTitle' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'workout.completeSet' }))
    await vi.advanceTimersByTimeAsync(46000)
    expect(screen.queryByRole('dialog', { name: 'workout.restTitle' })).toBeNull()
  })

  it('adds twenty seconds when asked', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderSession([exercise(0, 'Squat', 3)])

    await user.click(screen.getByRole('button', { name: 'workout.completeSet' }))
    await user.click(screen.getByRole('button', { name: 'workout.restAdd' }))

    expect(screen.getByText(/0:6[45]/)).toBeTruthy()
  })
})

describe('replace exercise sheet', () => {
  beforeEach(() => {
    fetchWorkoutAlternatives.mockReset()
    fetchWorkoutAlternatives.mockResolvedValue([
      { key: 'knee_push_up', name: 'Knee Push Up', target: 'Chest', why: 'Less load on the wrists.' },
      { key: 'incline_push_up', name: 'Incline Push Up', target: 'Chest', why: 'Easier angle.' },
    ])
  })

  it('asks the server which swaps keep the same job, for this slot', async () => {
    renderSession([exercise(0, 'Squat', 3), exercise(1, 'Push Up', 3)])

    await userEvent.click(screen.getByRole('button', { name: 'workout.cantDoThis' }))

    expect(fetchWorkoutAlternatives).toHaveBeenCalledWith(7, 0)
    expect(await screen.findByText('Knee Push Up')).toBeTruthy()
    expect(screen.getByText('Less load on the wrists.')).toBeTruthy()
  })

  it('confirms a chosen swap for the slot being replaced', async () => {
    const { onReplace } = renderSession([exercise(0, 'Squat', 3)])

    await userEvent.click(screen.getByRole('button', { name: 'workout.cantDoThis' }))
    await screen.findByText('Incline Push Up')
    await userEvent.click(screen.getByText('Incline Push Up'))
    await userEvent.click(screen.getByRole('button', { name: 'workout.replaceUse' }))

    expect(onReplace).toHaveBeenCalledWith(0, 'incline_push_up')
  })

  it('closes without swapping when the original is kept', async () => {
    const { onReplace } = renderSession([exercise(0, 'Squat', 3)])

    await userEvent.click(screen.getByRole('button', { name: 'workout.cantDoThis' }))
    await screen.findByText('Knee Push Up')
    await userEvent.click(screen.getByRole('button', { name: 'workout.replaceKeep' }))

    expect(onReplace).not.toHaveBeenCalled()
    expect(screen.queryByText('Knee Push Up')).toBeNull()
  })

  /** An empty sheet with a confirm button would swap the exercise for nothing. */
  it('says so plainly when nothing else can do the job', async () => {
    fetchWorkoutAlternatives.mockResolvedValue([])
    renderSession([exercise(0, 'Squat', 3)])

    await userEvent.click(screen.getByRole('button', { name: 'workout.cantDoThis' }))

    expect(await screen.findByText('workout.replaceEmpty')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'workout.replaceUse' })).toBeNull()
  })
})
