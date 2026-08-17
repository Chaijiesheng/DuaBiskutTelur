import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))

const { default: WorkoutDashboard } = await import('./WorkoutDashboard.jsx')

const SESSION = {
  id: 1,
  date: '2026-08-17',
  title: 'Full Body',
  focus: 'full_body',
  minutes: 30,
  level: 'beginner',
  status: 'planned',
  targetSummary: 'Legs · Chest · Core',
  totalSets: 12,
  completedSets: 0,
  exercises: [
    { position: 0, key: 'bodyweight_squat', name: 'Bodyweight Squat', target: 'Legs', sets: 3, reps: 12, unit: 'reps', cue: 'Sit back', completedSets: [] },
  ],
}

const WEEK = [
  { date: '2026-08-17', label: 'M', state: 'done' },
  { date: '2026-08-18', label: 'T', state: 'rest' },
  { date: '2026-08-19', label: 'W', state: 'done' },
  { date: '2026-08-20', label: 'T', state: 'rest' },
  { date: '2026-08-21', label: 'F', state: 'today' },
  { date: '2026-08-22', label: 'S', state: 'planned' },
  { date: '2026-08-23', label: 'S', state: 'rest' },
]

function data(overrides = {}) {
  return {
    hasProfile: true,
    session: SESSION,
    coach: { summary: 'Today adds a set to the squat.', factors: ['Two sessions done this week'] },
    coachSource: 'ai',
    week: WEEK,
    stats: { weightKg: 69.8, workoutsThisMonth: 12, streakDays: 5 },
    ...overrides,
  }
}

function renderDashboard(overrides = {}, props = {}) {
  const handlers = {
    onStart: vi.fn(),
    onOpenDetail: vi.fn(),
    onSkip: vi.fn(),
    onUnskip: vi.fn(),
  }
  render(<WorkoutDashboard data={data(overrides)} online pendingSets={0} {...handlers} {...props} />)
  return handlers
}

describe('workout dashboard, action-first layout', () => {
  /**
   * The layout choice, asserted rather than assumed. The variant this replaced
   * opened with a week-and-nutrition summary; somebody opening this tab is
   * deciding whether to train in the next thirty seconds, so the thing they act
   * on has to come before the thing they read.
   */
  it('puts the plan card before the week strip in the document', () => {
    renderDashboard()

    const card = screen.getByTestId('workout-plan-card')
    const week = screen.getByTestId('workout-week-strip')

    expect(card.compareDocumentPosition(week) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  /** The coach note justifies the card, so it belongs under it — not floating elsewhere. */
  it('puts the coach note between the plan card and the week strip', () => {
    renderDashboard()

    const card = screen.getByTestId('workout-plan-card')
    const note = screen.getByText('Today adds a set to the squat.')
    const week = screen.getByTestId('workout-week-strip')

    expect(card.compareDocumentPosition(note) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(note.compareDocumentPosition(week) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('starts the workout from the card itself', async () => {
    const { onStart } = renderDashboard()

    await userEvent.click(screen.getByRole('button', { name: 'workout.startWorkout' }))

    expect(onStart).toHaveBeenCalled()
  })

  it('offers to resume rather than start once a session is under way', () => {
    renderDashboard({ session: { ...SESSION, status: 'in_progress' } })

    expect(screen.getByRole('button', { name: 'workout.resumeWorkout' })).toBeTruthy()
    // "Not today" on a session you are three sets into is not a real option.
    expect(screen.queryByRole('button', { name: 'workout.notToday' })).toBeNull()
  })

  /** The factors are the disclosure behind the claim, so they stay closed until asked for. */
  it('hides the coach factors until they are asked for', async () => {
    renderDashboard()

    expect(screen.queryByText('Two sessions done this week')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'workout.whyOpen' }))

    expect(screen.getByText('Two sessions done this week')).toBeTruthy()
  })

  /**
   * A rule-based note is a designed fallback, not coaching, and the card says so.
   * Presenting a template under "Why this workout" would be the app claiming a
   * personalisation it did not do.
   */
  it('labels a rule-based note as the standard plan', () => {
    renderDashboard({ coachSource: 'rules' })

    expect(screen.getByText('workout.standardTitle')).toBeTruthy()
    expect(screen.queryByText('workout.whyTitle')).toBeNull()
  })

  it('counts only real training days in the week total', () => {
    renderDashboard()
    // Two done, out of the four non-rest days in the fixture (done, done,
    // today, planned). Rest days must not inflate the denominator — a
    // "2 / 7 done" on a three-day plan reads as failing at a plan nobody made.
    expect(screen.getByText('workout.weekDone:2,4')).toBeTruthy()
  })

  /** The circles are aria-hidden, so each day needs a readable state of its own. */
  it('announces every day of the week strip', () => {
    renderDashboard()

    expect(screen.getByText('M — workout.dayStates.done')).toBeTruthy()
    expect(screen.getByText('F — workout.dayStates.today')).toBeTruthy()
    expect(screen.getByText('S — workout.dayStates.planned')).toBeTruthy()
  })

  it('shows a skipped session with a way back into it', async () => {
    const { onUnskip } = renderDashboard({ session: { ...SESSION, status: 'skipped' } })

    expect(screen.getByText('workout.skippedTitle')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'workout.startWorkout' })).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'workout.actuallyGo' }))
    expect(onUnskip).toHaveBeenCalled()
  })

  it('warns that sets are being held locally when offline', () => {
    renderDashboard({}, { online: false })
    expect(screen.getByText('workout.offlineTitle')).toBeTruthy()
  })

  it('says how many sets are still waiting to sync', () => {
    renderDashboard({}, { pendingSets: 3 })
    expect(screen.getByText('workout.unsynced:3')).toBeTruthy()
  })

  /** Somebody who has never weighed in must see a dash, not "null kg". */
  it('handles a missing weight without inventing one', () => {
    renderDashboard({ stats: { weightKg: null, workoutsThisMonth: 0, streakDays: 0 } })

    expect(screen.getByText('—')).toBeTruthy()
    expect(screen.queryByText(/null/)).toBeNull()
  })
})
