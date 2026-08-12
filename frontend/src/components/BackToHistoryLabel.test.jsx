import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import en from '../i18n/en.js'
import ms from '../i18n/ms.js'
import zh from '../i18n/zh.js'

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({ t: (key) => (key === 'results.backToHistory' ? 'Back to history' : key), lang: 'en' }),
}))
vi.mock('../history/HistoryContext.jsx', () => ({ useHistory: vi.fn() }))
vi.mock('../theme/ThemeContext.jsx', () => ({ useTheme: () => ({ theme: 'light' }) }))
vi.mock('./ResultsScreen.jsx', () => ({ default: () => null }))
vi.mock('./MenuResultsScreen.jsx', () => ({ default: () => null }))
vi.mock('./WeeklyCaloriesChart.jsx', () => ({ default: () => null }))
vi.mock('../shareCard.js', () => ({ buildShareCard: vi.fn(), downloadBlob: vi.fn() }))
vi.mock('../api.js', () => ({
  deleteHistoryEntry: vi.fn(), exportHistoryPdf: vi.fn(), fetchHistoryDetail: vi.fn(),
  deleteMenuHistoryEntry: vi.fn(), fetchMenuHistory: vi.fn(), fetchMenuHistoryDetail: vi.fn(),
  googleLoginUrl: vi.fn(),
}))

const { BackToHistoryLabel } = await import('./HistoryScreen.jsx')

const DICTS = { en, ms, zh }

/**
 * The label used to be '⬅ Back to history' — the glyph lived inside all three
 * translation files. U+2B05 defaults to emoji presentation, so Android drew a
 * blue arrow that could not take the button's white, and screen readers read it
 * aloud as "left arrow". The same string is also reused on a small green text
 * link, where the arrow suited it even less.
 */
describe('back to history label', () => {
  it('carries no glyph in any translation', () => {
    // Translators own words. An icon baked into a string cannot be recoloured,
    // resized, or hidden from a screen reader by the place that renders it.
    for (const [lang, dict] of Object.entries(DICTS)) {
      const label = dict.results.backToHistory
      expect(label, `${lang} still has a leading glyph`).not.toMatch(/[←-⇿⬀-⯿]/)
      expect(label, `${lang} still has an emoji`).not.toMatch(/[\u{1F300}-\u{1FAFF}]/u)
    }
  })

  it('says the same thing in every language', () => {
    // Guards a find-and-replace that strips the arrow from one file only.
    for (const [lang, dict] of Object.entries(DICTS)) {
      expect(dict.results.backToHistory, `${lang} is empty`).toBeTruthy()
      expect(dict.results.backToHistory.trim()).toBe(dict.results.backToHistory)
    }
  })

  it('draws the icon so it takes the colour of whatever it sits on', () => {
    const { container } = render(<BackToHistoryLabel />)

    // It renders on a white-on-green button and on a green text link. Only
    // currentColor works in both.
    const svg = container.querySelector('svg')
    expect(svg).toBeInTheDocument()
    expect(svg.getAttribute('stroke')).toBe('currentColor')
  })

  it('keeps the icon out of the accessible name', () => {
    render(<BackToHistoryLabel />)

    expect(screen.getByText('Back to history')).toBeInTheDocument()
    // "left arrow, Back to history" was what this used to announce.
    expect(document.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })
})
