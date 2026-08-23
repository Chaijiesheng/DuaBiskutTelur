import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, ...args) => (args.length ? `${key}:${args.join(',')}` : key),
    lang: 'en',
  }),
}))

const { default: ExerciseDemo } = await import('./ExerciseDemo.jsx')

/**
 * The GIF is the authority on the movement, so the things worth testing are the
 * ways this component could misrepresent it: showing nothing, showing a broken
 * image, or moving the page while the user is reaching for a button.
 */
describe('the exercise demonstration', () => {
  it('shows the registered GIF for an exercise that has one', () => {
    render(<ExerciseDemo exerciseKey="plank" name="Plank" />)

    const img = screen.getByRole('img')
    expect(img.getAttribute('src')).toBe('/exercises/plank.gif')
    expect(img.getAttribute('alt')).toBe('workout.demoAlt:Plank')
  })

  /**
   * The reason 59 exercises can ship against 14 assets. An exercise with no
   * demonstration must say so — not render an empty box, and not borrow a
   * different exercise's GIF, which would teach the wrong movement.
   */
  it('says so when an exercise has no demonstration', () => {
    render(<ExerciseDemo exerciseKey="barbell_deadlift" name="Barbell Deadlift" />)

    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.getByText('workout.demoUnavailable')).toBeTruthy()
  })

  /**
   * A registered asset can still fail — a bad deploy, a half-written file. The
   * browser's own broken-image icon is the one outcome that must never appear.
   */
  it('falls back to the unavailable state when the file fails to load', () => {
    render(<ExerciseDemo exerciseKey="plank" name="Plank" />)

    fireEvent.error(screen.getByRole('img'))

    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.getByText('workout.demoUnavailable')).toBeTruthy()
  })

  /**
   * The box is reserved at the authored ratio before the image arrives. Without
   * this the rep count and Complete-set button jump downward the instant a
   * 300 KB GIF finishes decoding.
   */
  it('reserves the frame at the authored aspect ratio', () => {
    const { container } = render(<ExerciseDemo exerciseKey="plank" name="Plank" />)
    const withRatio = container.querySelector('[style*="aspect-ratio"]')

    expect(withRatio).toBeTruthy()
    expect(withRatio.style.aspectRatio.replace(/\s/g, '')).toBe('960/540')
  })

  it('reserves the same frame when there is no asset, so the layout does not shift either way', () => {
    const { container } = render(<ExerciseDemo exerciseKey="barbell_deadlift" name="Deadlift" />)
    expect(container.querySelector('[style*="aspect-ratio"]')).toBeTruthy()
  })

  /**
   * Opening a six-exercise preview must not pull several megabytes at once.
   * Only the exercise actually in front of the user loads eagerly.
   */
  it('lazy-loads by default and eager-loads only the current exercise', () => {
    const { container: lazy } = render(<ExerciseDemo exerciseKey="plank" name="Plank" />)
    const { container: eager } = render(<ExerciseDemo exerciseKey="plank" name="Plank" priority />)

    expect(lazy.querySelector('img').getAttribute('loading')).toBe('lazy')
    expect(eager.querySelector('img').getAttribute('loading')).toBe('eager')
  })

  /** The GIF loops on its own; nothing is drawn over it and it is not transformed. */
  it('leaves the image untransformed and unobscured', () => {
    const { container } = render(<ExerciseDemo exerciseKey="plank" name="Plank" />)
    const img = container.querySelector('img')

    expect(img.className).not.toMatch(/scale-|rotate-|translate-/)
    expect(img.className).toContain('object-contain')
  })
})
