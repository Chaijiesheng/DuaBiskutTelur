import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

// Real English strings would make these assertions test the copy; the key echo
// makes them test the structure, which is what varies.
vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))

const { default: WorkoutOnboarding, STEPS } = await import('./WorkoutOnboarding.jsx')

function renderOnboarding(overrides = {}) {
  const onSave = vi.fn()
  const onCancel = vi.fn()
  render(<WorkoutOnboarding onSave={onSave} onCancel={onCancel} {...overrides} />)
  return { onSave, onCancel }
}

/** The option labelled with a given vocabulary tag, whichever step it is on. */
function option(tag) {
  return screen.getByRole('button', { name: `workout.options.${tag}` })
}

describe('workout onboarding, one question per screen', () => {
  /**
   * The whole point of variant A. If two questions are ever visible at once the
   * flow has quietly become the single-page form it was chosen over.
   */
  it('shows exactly one question at a time', async () => {
    renderOnboarding()

    expect(screen.getByRole('heading')).toHaveTextContent('workout.questions.goal.title')
    expect(screen.queryByText('workout.questions.level.title')).toBeNull()

    await userEvent.click(option('lose_weight'))

    expect(screen.getByRole('heading')).toHaveTextContent('workout.questions.level.title')
    expect(screen.queryByText('workout.questions.goal.title')).toBeNull()
  })

  /** No Continue button on a single-select step — tapping the answer is the answer. */
  it('advances on tap for single-select and needs a button for multi-select', async () => {
    renderOnboarding()

    expect(screen.queryByRole('button', { name: 'workout.continue' })).toBeNull()

    await userEvent.click(option('lose_weight'))
    await userEvent.click(option('beginner'))
    await userEvent.click(screen.getByRole('button', { name: '3' }))
    await userEvent.click(screen.getByRole('button', { name: 'workout.options.minutes:30' }))

    // Equipment is multi-select: tapping is how you add a second answer, so it
    // cannot also be how you move on.
    expect(screen.getByText('workout.questions.equipment.title')).toBeTruthy()
    await userEvent.click(option('dumbbells'))
    expect(screen.getByText('workout.questions.equipment.title')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'workout.continue' })).toBeTruthy()
  })

  it('lets multi-select hold more than one answer, and lets one be taken back', async () => {
    renderOnboarding()
    await userEvent.click(option('lose_weight'))
    await userEvent.click(option('beginner'))
    await userEvent.click(screen.getByRole('button', { name: '3' }))
    await userEvent.click(screen.getByRole('button', { name: 'workout.options.minutes:30' }))

    await userEvent.click(option('dumbbells'))
    await userEvent.click(option('bands'))
    expect(option('dumbbells')).toHaveAttribute('aria-pressed', 'true')
    expect(option('bands')).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(option('bands'))
    expect(option('bands')).toHaveAttribute('aria-pressed', 'false')
  })

  it('goes back a step, and out of onboarding from the first one', async () => {
    const { onCancel } = renderOnboarding()

    await userEvent.click(option('lose_weight'))
    expect(screen.getByText('workout.questions.level.title')).toBeTruthy()

    await userEvent.click(screen.getByRole('button', { name: 'workout.back' }))
    expect(screen.getByText('workout.questions.goal.title')).toBeTruthy()
    expect(onCancel).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'workout.back' }))
    expect(onCancel).toHaveBeenCalled()
  })

  it('keeps an answer when you step back to it', async () => {
    renderOnboarding()

    await userEvent.click(option('build_muscle'))
    await userEvent.click(screen.getByRole('button', { name: 'workout.back' }))

    expect(option('build_muscle')).toHaveAttribute('aria-pressed', 'true')
    expect(option('lose_weight')).toHaveAttribute('aria-pressed', 'false')
  })

  it('reports the step count so the progress bars are not the only signal', () => {
    renderOnboarding()
    expect(screen.getByText(`workout.stepOf:1,${STEPS.length}`)).toBeTruthy()
  })

  /** Only the last step is skippable; skipping earlier ones would send nulls. */
  it('offers a skip on the last step and nowhere else', async () => {
    renderOnboarding()
    expect(screen.queryByRole('button', { name: 'workout.skipStep' })).toBeNull()

    await userEvent.click(option('lose_weight'))
    await userEvent.click(option('beginner'))
    await userEvent.click(screen.getByRole('button', { name: '3' }))
    await userEvent.click(screen.getByRole('button', { name: 'workout.options.minutes:30' }))
    await userEvent.click(option('none'))
    await userEvent.click(screen.getByRole('button', { name: 'workout.continue' }))

    expect(screen.getByText('workout.questions.preferences.title')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'workout.skipStep' })).toBeTruthy()
  })

  /** Every answer is sent as the server's tag, never as the label the user read. */
  it('submits vocabulary tags rather than display text', async () => {
    const { onSave } = renderOnboarding()

    await userEvent.click(option('build_muscle'))
    await userEvent.click(option('advanced'))
    await userEvent.click(screen.getByRole('button', { name: '4' }))
    await userEvent.click(screen.getByRole('button', { name: 'workout.options.minutes:45' }))
    await userEvent.click(option('dumbbells'))
    await userEvent.click(screen.getByRole('button', { name: 'workout.continue' }))
    await userEvent.click(option('strength'))
    await userEvent.click(screen.getByRole('button', { name: 'workout.continue' }))

    expect(onSave).toHaveBeenCalledWith({
      goal: 'build_muscle',
      level: 'advanced',
      daysPerWeek: 4,
      sessionMinutes: 45,
      equipment: ['dumbbells'],
      preferences: ['strength'],
    })
  })

  /**
   * Bodyweight is not equipment you can fail to own, and the server rejects an
   * empty list — so somebody who taps straight past this step must still get a
   * plan rather than a 400.
   */
  it('falls back to bodyweight when no equipment is chosen', async () => {
    const { onSave } = renderOnboarding()

    await userEvent.click(option('maintain'))
    await userEvent.click(option('beginner'))
    await userEvent.click(screen.getByRole('button', { name: '2' }))
    await userEvent.click(screen.getByRole('button', { name: 'workout.options.minutes:15' }))
    await userEvent.click(screen.getByRole('button', { name: 'workout.continue' }))
    await userEvent.click(screen.getByRole('button', { name: 'workout.skipStep' }))

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({ equipment: ['none'], preferences: [] }),
    )
  })

  it('surfaces a rejected profile instead of failing silently', () => {
    renderOnboarding({ error: 'goal is not valid' })
    expect(screen.getByRole('alert')).toHaveTextContent('goal is not valid')
  })
})
