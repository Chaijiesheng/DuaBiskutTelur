import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Navigate, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../history/HistoryContext.jsx', () => ({ useHistory: vi.fn() }))
vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({ t: (key) => key, lang: 'en' }),
}))
vi.mock('../theme/ThemeContext.jsx', () => ({ useTheme: () => ({ theme: 'light' }) }))
vi.mock('./MenuResultsScreen.jsx', () => ({ default: () => <div>menu detail</div> }))
vi.mock('./ResultsScreen.jsx', () => ({ default: () => <div>meal detail</div> }))
vi.mock('../api.js', () => ({
  deleteHistoryEntry: vi.fn(),
  exportHistoryPdf: vi.fn(),
  fetchHistoryDetail: vi.fn(() => Promise.resolve({ foods: [], totals: {} })),
  deleteMenuHistoryEntry: vi.fn(),
  fetchMenuHistory: vi.fn(),
  fetchMenuHistoryDetail: vi.fn(),
}))

const { useHistory } = await import('../history/HistoryContext.jsx')
const { fetchMenuHistory, fetchMenuHistoryDetail } = await import('../api.js')
const { default: HistoryScreen } = await import('./HistoryScreen.jsx')

const MENU_ENTRIES = [
  { id: 7, summary: 'Kopitiam lunch menu', createdAt: '2026-08-01T04:00:00Z', dishCount: 12, thumbnail: null },
]

/**
 * Mirrors the real route table in App.jsx — including the catch-all, which is
 * the piece that makes this bug what the user actually sees. A detail link that
 * builds the wrong path does not 404; it matches `*`, redirects to `/`, and
 * dumps the user on the capture screen with no explanation.
 *
 * Testing HistoryScreen on its own would hide that entirely: the bad path would
 * simply render nothing, and the test would pass while the app was broken.
 */
function renderApp(initialPath) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route index element={<div>main capture screen</div>} />
        <Route path="history/*" element={<HistoryScreen isVisitor={false} />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('opening a saved entry from history', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useHistory.mockReturnValue({
      entries: [{ id: 3, summary: 'Nasi lemak', createdAt: '2026-08-01T04:00:00Z', score: 78, grade: 'B', calories: 600 }],
      loading: false,
      error: false,
      refresh: vi.fn(),
      remove: vi.fn(),
    })
    fetchMenuHistory.mockResolvedValue(MENU_ENTRIES)
    fetchMenuHistoryDetail.mockResolvedValue({ dishCount: 12, truncated: false, tiers: [] })
  })

  it('opens a saved menu scan instead of bouncing to the main page', async () => {
    const user = userEvent.setup()
    renderApp('/history?view=menus')

    const entry = await screen.findByText('Kopitiam lunch menu')
    await user.click(entry)

    // The bug: the link resolved to /menu/7 rather than /history/menu/7, so the
    // catch-all sent the user home and their scan was simply gone.
    await waitFor(() => expect(screen.getByText('menu detail')).toBeInTheDocument())
    expect(screen.queryByText('main capture screen')).not.toBeInTheDocument()
  })

  it('asks the server for the scan that was tapped', async () => {
    const user = userEvent.setup()
    renderApp('/history?view=menus')

    await user.click(await screen.findByText('Kopitiam lunch menu'))

    // Landing on the right route is only half of it — it has to carry the id.
    await waitFor(() => expect(fetchMenuHistoryDetail).toHaveBeenCalledWith('7'))
  })

  it('still opens a saved meal, which was never broken', async () => {
    const user = userEvent.setup()
    renderApp('/history')

    await user.click(await screen.findByText('Nasi lemak'))

    // Guards the fix: the meal link is the pattern the menu link should match,
    // so a "tidy-up" that changes both together would break this first.
    await waitFor(() => expect(screen.getByText('meal detail')).toBeInTheDocument())
    expect(screen.queryByText('main capture screen')).not.toBeInTheDocument()
  })
})
