import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../shareCard.js', () => ({
  buildShareCard: vi.fn(),
  downloadBlob: vi.fn(),
}))
vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, arg) => {
      const strings = {
        'results.share': 'Share',
        'results.shareAria': 'Share this result',
        'results.preparingShare': 'Preparing image…',
        'results.shareError': "Couldn't share this report — try again.",
        'results.shareText': `I scored ${arg} on my meal!`,
        'app.title1': 'Dua',
        'app.title2': 'BiskutTelur',
      }
      return strings[key] ?? key
    },
    lang: 'en',
  }),
}))
vi.mock('../theme/ThemeContext.jsx', () => ({ useTheme: () => ({ theme: 'light' }) }))
vi.mock('./GradeReveal.jsx', () => ({
  default: () => <div>grade</div>,
  GRADE_COLORS: { light: {}, dark: {} },
}))
vi.mock('./MacroDonut.jsx', () => ({ default: () => null }))
vi.mock('./CalorieBar.jsx', () => ({ default: () => null }))
vi.mock('./ScoringRubric.jsx', () => ({ default: () => null }))
vi.mock('./FoodCard.jsx', () => ({ default: () => null }))
vi.mock('./FoodEquivalents.jsx', () => ({ default: () => null }))
vi.mock('./SignInBanner.jsx', () => ({ default: () => null }))
vi.mock('../api.js', () => ({ correctPortions: vi.fn(), removeFood: vi.fn() }))

const { buildShareCard } = await import('../shareCard.js')
const { default: ResultsScreen } = await import('./ResultsScreen.jsx')

const RESULT = {
  foods: [{ name: 'Nasi lemak', calories: 398, protein: 9, carbs: 50, fat: 18, fiber: 3, sugar: 2, sodium: 500 }],
  totals: { calories: 398, protein: 9, carbs: 50, fat: 18, fiber: 3, sugar: 2, sodium: 500 },
  score: 78,
  grade: 'B',
  highlights: [],
  concerns: [],
  suggestions: [],
  encouragement: 'Nice one',
  source: 'photo',
  scoreBreakdown: null,
}

function renderResults() {
  return render(<ResultsScreen result={RESULT} dailyBudget={2000} onSnapAnother={() => {}} />)
}

const icon = () => screen.getByRole('button', { name: 'Share this result' })
// Exact, not a pattern: the icon's name also contains 'Share'.
const bottomButton = () => screen.getByRole('button', { name: 'Share' })

/**
 * Users reported having to scroll to the bottom of the report to share it, so a
 * share icon now sits beside the grade — where the wanting-to-share moment
 * actually happens — and the original button stays for people who read the
 * whole report first.
 *
 * Two controls for one action is the risk this covers: they must be one flow,
 * not two. Building the share card draws the result and the photo onto a
 * canvas, so it takes long enough to see, and two independent copies of that
 * state would let the icon look idle while the button says it is working.
 */
describe('share controls', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('offers sharing without scrolling, and still at the end of the report', () => {
    renderResults()

    expect(icon()).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Share' })).toBeInTheDocument()
  })

  it('names the icon, which has no text of its own', () => {
    renderResults()

    // An icon-only control with no accessible name is announced as just
    // "button" — the same defect the emoji tab labels had.
    expect(icon()).toHaveAccessibleName('Share this result')
  })

  it('drives both controls from one share, so they cannot disagree', async () => {
    // Never resolves: holds the flow in its preparing state.
    buildShareCard.mockImplementation(() => new Promise(() => {}))
    renderResults()

    await userEvent.click(icon())

    await waitFor(() => expect(icon()).toBeDisabled())
    expect(icon()).toHaveAttribute('aria-busy', 'true')
    // The bottom button reflects the share started from the icon.
    expect(screen.getByRole('button', { name: 'Preparing image…' })).toBeDisabled()
  })

  it('reports a failure once, where there is room to explain it', async () => {
    buildShareCard.mockRejectedValue(new Error('canvas exploded'))
    renderResults()

    await userEvent.click(icon())

    // A 44px circle cannot hold a sentence, so the message belongs to the
    // bottom control — but only one of them should be shouting.
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent("Couldn't share"))
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(icon()).not.toBeDisabled()
  })

  it('treats backing out of the native share sheet as a non-event', async () => {
    buildShareCard.mockRejectedValue(Object.assign(new Error('cancelled'), { name: 'AbortError' }))
    renderResults()

    await userEvent.click(icon())

    // Dismissing the OS share sheet is a decision, not a failure.
    await waitFor(() => expect(icon()).not.toBeDisabled())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shares the same card whichever control is used', async () => {
    buildShareCard.mockResolvedValue({ blob: new Blob(), shareText: 'I scored B on my meal!' })
    renderResults()

    await userEvent.click(icon())
    await waitFor(() => expect(buildShareCard).toHaveBeenCalledTimes(1))
    await userEvent.click(bottomButton())
    await waitFor(() => expect(buildShareCard).toHaveBeenCalledTimes(2))

    const [first, second] = buildShareCard.mock.calls.map(([args]) => args)
    expect(first.result).toBe(second.result)
    expect(first.shareText).toBe(second.shareText)
  })
})
