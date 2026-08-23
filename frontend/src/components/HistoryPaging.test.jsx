import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const STRINGS = {
  'history.showMore': 'Show older meals',
  'history.loadingMore': 'Loading…',
  'history.couldntLoadMore': "Couldn't load older meals — try again.",
  'history.moreOptions': 'More options',
}

vi.mock('../history/HistoryContext.jsx', () => ({ useHistory: vi.fn() }))
vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({ t: (key) => STRINGS[key] ?? key, lang: 'en' }),
}))
vi.mock('../theme/ThemeContext.jsx', () => ({ useTheme: () => ({ theme: 'light' }) }))
vi.mock('./WeeklyCaloriesChart.jsx', () => ({ default: () => null }))
vi.mock('../shareCard.js', () => ({ buildShareCard: vi.fn(), downloadBlob: vi.fn() }))
vi.mock('../api.js', () => ({
  deleteHistoryEntry: vi.fn(), exportHistoryPdf: vi.fn(), fetchHistoryDetail: vi.fn(),
  deleteMenuHistoryEntry: vi.fn(), fetchMenuHistory: vi.fn(() => Promise.resolve([])),
  fetchMenuHistoryDetail: vi.fn(), googleLoginUrl: vi.fn(() => '/oauth2/authorization/google'),
}))

const { useHistory } = await import('../history/HistoryContext.jsx')
const { default: HistoryScreen } = await import('./HistoryScreen.jsx')

const entries = (n) =>
  Array.from({ length: n }, (_, i) => ({
    id: i + 1,
    summary: `meal ${i}`,
    createdAt: '2026-08-01T04:00:00Z',
    score: 78,
    grade: 'B',
    calories: 640,
    source: 'photo',
  }))

function renderList({ isVisitor = false, ...history } = {}) {
  useHistory.mockReturnValue({
    entries: entries(50), recent: entries(50), loading: false, error: false,
    hasMore: false, loadingMore: false, moreError: false, loadMore: vi.fn(),
    removeEntry: vi.fn(), updateEntry: vi.fn(),
    ...history,
  })
  return render(
    <MemoryRouter initialEntries={['/history']}>
      <Routes>
        <Route path="history/*" element={<HistoryScreen isVisitor={isVisitor} onDeleteVisitorEntry={vi.fn()} />} />
      </Routes>
    </MemoryRouter>,
  )
}

/**
 * Getting to meal fifty-one.
 *
 * <p>The list stopped at fifty rows with no way past them and nothing on
 * screen to say so, which reads as "this is everything I have logged" rather
 * than as a limit — the worst kind, because the reader has no reason to doubt
 * it.
 */
describe('the History list past the first page', () => {
  beforeEach(() => vi.clearAllMocks())

  it('offers a way to older meals when there are more', async () => {
    renderList({ hasMore: true })

    expect(screen.getByRole('button', { name: 'Show older meals' })).toBeInTheDocument()
  })

  /**
   * The control has to be absent, not disabled. A greyed-out Show more at the
   * foot of a complete list says there is something being withheld.
   */
  it('offers nothing once the list is complete', () => {
    renderList({ hasMore: false })

    expect(screen.queryByRole('button', { name: 'Show older meals' })).toBeNull()
  })

  it('asks for the next page when tapped', async () => {
    const loadMore = vi.fn()
    renderList({ hasMore: true, loadMore })

    await userEvent.click(screen.getByRole('button', { name: 'Show older meals' }))

    expect(loadMore).toHaveBeenCalledTimes(1)
  })

  it('cannot be tapped again while a page is on its way', () => {
    renderList({ hasMore: true, loadingMore: true })

    expect(screen.getByRole('button', { name: 'Loading…' })).toBeDisabled()
  })

  /** A page that never arrives must say so rather than leave a dead button. */
  it('reports a page that failed to load', () => {
    renderList({ hasMore: true, moreError: true })

    expect(screen.getByRole('alert')).toHaveTextContent("Couldn't load older meals")
    expect(screen.getByRole('button', { name: 'Show older meals' })).toBeEnabled()
  })

  /** A visitor's meals are all in memory; there is no page behind them. */
  it('offers nothing to a visitor', () => {
    renderList({ isVisitor: true, hasMore: false })

    expect(screen.queryByRole('button', { name: 'Show older meals' })).toBeNull()
  })
})
