import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key) => {
      const strings = {
        'waterTracker.title': 'Water',
        'waterTracker.glass': 'Glass +250ml',
        'waterTracker.bottle': 'Bottle +500ml',
        'waterTracker.reset': 'Reset',
      }
      if (key === 'waterTracker.celebrations') return ['nice']
      return strings[key] ?? key
    },
    lang: 'en',
  }),
}))
vi.mock('../api.js', () => ({
  adjustWater: vi.fn(),
  fetchWaterToday: vi.fn(() => new Promise(() => {})),
  resetWater: vi.fn(),
  setWaterTarget: vi.fn(),
}))

const { default: WaterTrackerCard } = await import('./WaterTrackerCard.jsx')

/** Visitor mode keeps everything local, so the card renders without a backend. */
function renderCard() {
  return render(<WaterTrackerCard isVisitor />)
}

/**
 * The quick-add buttons used 🥛 (glass of MILK) and 🍼 (BABY BOTTLE) — the
 * closest glass- and bottle-shaped characters Unicode offers, both of which
 * depict dairy. On a water tracker that is the wrong drink entirely, and it was
 * being read aloud that way too: "glass of milk, Glass +250ml".
 */
describe('WaterTrackerCard quick-add buttons', () => {
  it('shows no dairy anywhere on a water tracker', () => {
    const { container } = renderCard()

    // Unicode has no glass-of-water emoji, which is how this happened. The
    // answer is a drawn icon, not a closer-looking emoji.
    expect(container.textContent).not.toContain('🥛')
    expect(container.textContent).not.toContain('🍼')
  })

  it('names each button by its serving, with nothing else announced', () => {
    renderCard()

    // The icons are decorative and aria-hidden, so the accessible name is the
    // visible label alone — no "glass of milk" prefix.
    expect(screen.getByRole('button', { name: 'Glass +250ml' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Bottle +500ml' })).toBeInTheDocument()
  })

  it('draws the icons so they follow the button colour and disabled state', () => {
    const { container } = renderCard()

    // currentColor is the point: an emoji cannot dim with the button when the
    // 8000ml ceiling disables it.
    const icons = [...container.querySelectorAll('svg[aria-hidden="true"]')]
    const drinkIcons = icons.filter((s) => s.getAttribute('stroke') === 'currentColor')
    expect(drinkIcons.length).toBeGreaterThanOrEqual(2)
  })
})
