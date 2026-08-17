import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))

const { default: WorkoutComplete } = await import('./WorkoutComplete.jsx')

function renderComplete(summary = {}) {
  const onSubmit = vi.fn()
  const onDone = vi.fn()
  render(
    <WorkoutComplete
      summary={{ minutes: 28, exercises: 6, sets: 11, coachReply: '', ...summary }}
      onSubmit={onSubmit}
      onDone={onDone}
    />,
  )
  return { onSubmit, onDone }
}

describe('workout completion', () => {
  it('shows what was actually done', () => {
    renderComplete()

    expect(screen.getByText('workout.completeTitle')).toBeTruthy()
    expect(screen.getByText('28')).toBeTruthy()
    expect(screen.getByText('6')).toBeTruthy()
    expect(screen.getByText('11')).toBeTruthy()
  })

  /**
   * The design's actual promise, and the one thing a stubbed backend hid during
   * manual checking: the coach card appears *after* you answer, never before.
   * A reaction printed before you say anything is decoration; printed after, it
   * is the app demonstrating it listened.
   */
  it('says nothing until there is something to react to', () => {
    renderComplete({ coachReply: '' })

    expect(screen.queryByText('workout.coach')).toBeNull()
  })

  it('shows the coach reply once one arrives', () => {
    renderComplete({ coachReply: 'The next session drops a set from everything.' })

    expect(screen.getByText('workout.coach')).toBeTruthy()
    expect(screen.getByText('The next session drops a set from everything.')).toBeTruthy()
  })

  it('submits a rating as soon as it is picked, without a separate save', async () => {
    const { onSubmit } = renderComplete()

    await userEvent.click(screen.getByRole('button', { name: 'workout.feel.too_hard' }))

    expect(onSubmit).toHaveBeenCalledWith({ feel: 'too_hard', energy: null })
  })

  /** Energy is optional and separate; answering it must not clear the feel. */
  it('keeps both answers when the second one is given', async () => {
    const { onSubmit } = renderComplete()

    await userEvent.click(screen.getByRole('button', { name: 'workout.feel.just_right' }))
    await userEvent.click(screen.getByRole('button', { name: 'workout.energy.tired' }))

    expect(onSubmit).toHaveBeenLastCalledWith({ feel: 'just_right', energy: 'tired' })
  })

  it('marks the chosen answers as pressed', async () => {
    renderComplete()

    await userEvent.click(screen.getByRole('button', { name: 'workout.feel.too_easy' }))

    expect(screen.getByRole('button', { name: 'workout.feel.too_easy' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'workout.feel.too_hard' })).toHaveAttribute('aria-pressed', 'false')
  })

  /** Both questions are optional — Finish must work having answered neither. */
  it('finishes without any rating at all', async () => {
    const { onDone, onSubmit } = renderComplete()

    await userEvent.click(screen.getByRole('button', { name: 'workout.finish' }))

    expect(onDone).toHaveBeenCalled()
    expect(onSubmit).not.toHaveBeenCalled()
  })
})
