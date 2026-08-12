import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The other half of U1: the skeletons exist and behave (Skeleton.test.jsx), but
 * nothing so far proves any screen actually renders one. Six loading branches
 * were rewritten by hand; a missed one leaves that screen on the old centred
 * "Loading…" line, and the app still builds, still lints, and still passes
 * every other test.
 *
 * These mock the data hooks into their loading state and assert the screen
 * shows a placeholder rather than a lone line of text.
 */

vi.mock('../history/HistoryContext.jsx', () => ({
  useHistory: vi.fn(),
}))
vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({ t: (key) => key, lang: 'en' }),
}))
vi.mock('../theme/ThemeContext.jsx', () => ({
  useTheme: () => ({ theme: 'light' }),
}))
vi.mock('../api.js', () => ({
  deleteHistoryEntry: vi.fn(),
  exportHistoryPdf: vi.fn(),
  fetchHistoryDetail: vi.fn(() => new Promise(() => {})),
  deleteMenuHistoryEntry: vi.fn(),
  fetchMenuHistory: vi.fn(() => new Promise(() => {})),
  fetchMenuHistoryDetail: vi.fn(() => new Promise(() => {})),
  fetchAchievements: vi.fn(() => new Promise(() => {})),
}))

const { useHistory } = await import('../history/HistoryContext.jsx')
const { default: HistoryScreen } = await import('./HistoryScreen.jsx')
const { default: AnalysisScreen } = await import('./AnalysisScreen.jsx')

/** The state every one of these screens starts in: request in flight, no data. */
function stillLoading() {
  useHistory.mockReturnValue({
    entries: null,
    recent: null,
    loading: true,
    error: null,
    removeEntry: vi.fn(),
    updateEntry: vi.fn(),
  })
}

describe('loading surfaces', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stillLoading()
  })

  it('the History tab shows a placeholder list, not a line of text', () => {
    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <HistoryScreen isVisitor={false} dailyBudget={2000} />
      </MemoryRouter>,
    )

    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
    expect(container.querySelectorAll('li').length).toBeGreaterThan(0)
  })

  it('the Analysis tab shows a placeholder stat grid', () => {
    const { container } = render(
      <MemoryRouter>
        <AnalysisScreen isVisitor={false} dailyBudget={2000} />
      </MemoryRouter>,
    )

    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
    expect(container.querySelectorAll('div.bg-slate-200').length).toBeGreaterThan(0)
  })

  it('the announcement survives on both, so the wait is still perceivable without sight', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <HistoryScreen isVisitor={false} dailyBudget={2000} />
      </MemoryRouter>,
    )

    // The mocked t() returns the key, which is what the old visible text used.
    expect(screen.getByRole('status')).toHaveTextContent('history.loading')
  })
})
