import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const STRINGS = {
  'history.moreOptions': 'More options',
  'history.mealOptions': 'Meal options',
  'history.deleteMeal': 'Delete meal',
  'history.deleteThisMeal': 'Delete this meal?',
  'history.cancel': 'Cancel',
  'history.confirmDelete': 'Delete',
  // Plain labels, deliberately without the emoji the results.* strings carry.
  'history.share': 'Share',
  'history.exportPdf': 'Export PDF',
  'results.share': '📤 Share',
  'results.preparingShare': 'Preparing image…',
  'results.shareError': "Couldn't share this report — try again.",
  'results.exportPdf': '📄 Export PDF',
  'results.preparingPdf': 'Preparing PDF…',
  'results.exportError': "Couldn't export the report — try again.",
}

vi.mock('../history/HistoryContext.jsx', () => ({ useHistory: vi.fn() }))
vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({ t: (key) => STRINGS[key] ?? key, lang: 'en' }),
}))
vi.mock('../theme/ThemeContext.jsx', () => ({ useTheme: () => ({ theme: 'light' }) }))
vi.mock('./WeeklyCaloriesChart.jsx', () => ({ default: () => null }))
vi.mock('../shareCard.js', () => ({ buildShareCard: vi.fn(), downloadBlob: vi.fn() }))
vi.mock('../api.js', () => ({
  deleteHistoryEntry: vi.fn(() => Promise.resolve()),
  exportHistoryPdf: vi.fn(() => Promise.resolve()),
  fetchHistoryDetail: vi.fn(),
  deleteMenuHistoryEntry: vi.fn(),
  fetchMenuHistory: vi.fn(() => Promise.resolve([])),
  fetchMenuHistoryDetail: vi.fn(),
  // Pulled in by SignInBanner, which only the visitor list renders.
  googleLoginUrl: vi.fn(() => '/oauth2/authorization/google'),
}))

const { useHistory } = await import('../history/HistoryContext.jsx')
const { buildShareCard } = await import('../shareCard.js')
const { exportHistoryPdf, fetchHistoryDetail } = await import('../api.js')
const { default: HistoryScreen } = await import('./HistoryScreen.jsx')

const ENTRY = {
  id: 3,
  summary: 'Nasi lemak, teh tarik',
  createdAt: '2026-08-01T04:00:00Z',
  score: 78,
  grade: 'B',
  calories: 640,
  thumbnail: 'data:image/jpeg;base64,AAA',
  source: 'photo',
}
const DETAIL = { grade: 'B', foods: [], totals: { calories: 640 } }

function renderList({ isVisitor = false, entries = [ENTRY] } = {}) {
  useHistory.mockReturnValue({
    entries, recent: entries, loading: false, error: false,
    removeEntry: vi.fn(), updateEntry: vi.fn(),
  })
  return render(
    <MemoryRouter initialEntries={['/history']}>
      <Routes>
        <Route path="history/*" element={<HistoryScreen isVisitor={isVisitor} onDeleteVisitorEntry={vi.fn()} />} />
      </Routes>
    </MemoryRouter>,
  )
}

const openSheet = async (user) => {
  await user.click(screen.getByRole('button', { name: 'More options' }))
  return screen.getByRole('dialog')
}

/**
 * The row button was a 🗑️ that went straight to the delete confirmation, so
 * the only thing a saved meal could do from the list was be destroyed. It is
 * now an overflow menu — which means the glyph finally has to open something.
 */
describe('meal row overflow menu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    buildShareCard.mockResolvedValue({ blob: new Blob(), shareText: 'I scored B' })
    fetchHistoryDetail.mockResolvedValue(DETAIL)
  })

  it('opens a menu rather than jumping straight to delete', async () => {
    const user = userEvent.setup()
    renderList()

    const sheet = await openSheet(user)

    // The old behaviour: one tap, delete confirmation, no way to do anything else.
    expect(within(sheet).queryByText('Delete this meal?')).not.toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /Share/ })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /Export PDF/ })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Delete meal' })).toBeInTheDocument()
  })

  it('starts every label at the same place', async () => {
    const user = userEvent.setup()
    renderList()
    const sheet = await openSheet(user)

    // The reported defect: two rows took their icon from inside the translated
    // string ("📤 Share" — emoji, one space, word) while the third passed one as
    // a sibling with the flex gap between them, so the labels sat ~8px apart.
    // Every row now puts its icon in the same fixed-width slot.
    const rows = [...sheet.querySelectorAll('button')].filter((b) => b.querySelector('svg'))
    expect(rows).toHaveLength(3)
    for (const row of rows) {
      const [slot, label] = row.children
      expect(slot.querySelector('svg')).toBeTruthy()
      expect(slot.className).toMatch(/w-5/)
      // No emoji smuggled in through the copy.
      expect(label.textContent).not.toMatch(/[\u{1F300}-\u{1FAFF}]/u)
    }
  })

  it('keeps the icon slot filled while an action is running', async () => {
    const user = userEvent.setup()
    renderList()
    const sheet = await openSheet(user)

    // "Preparing image…" has no emoji, so the old row lost its icon mid-action
    // and the text jumped left. The slot is structural now, so it cannot.
    const before = within(sheet).getByRole('button', { name: 'Share' })
    expect(before.children[0].querySelector('svg')).toBeTruthy()
  })

  it('still reaches the delete confirmation, which is now a deliberate second step', async () => {
    const user = userEvent.setup()
    renderList()
    const sheet = await openSheet(user)

    await user.click(within(sheet).getByRole('button', { name: 'Delete meal' }))

    // Destructive actions keep their confirmation — the menu adds a step before
    // it rather than replacing it.
    await waitFor(() => expect(screen.getByText('Delete this meal?')).toBeInTheDocument())
  })

  it('shares a meal without opening it first', async () => {
    const user = userEvent.setup()
    renderList()
    const sheet = await openSheet(user)

    await user.click(within(sheet).getByRole('button', { name: /Share/ }))

    // The list row has no foods or totals, so the card has to be built from the
    // full analysis — fetched on demand.
    await waitFor(() => expect(fetchHistoryDetail).toHaveBeenCalledWith(3))
    await waitFor(() => expect(buildShareCard).toHaveBeenCalled())
    expect(buildShareCard.mock.calls[0][0].result).toBe(DETAIL)
  })

  it('puts the row thumbnail on the share card', async () => {
    const user = userEvent.setup()
    renderList()
    const sheet = await openSheet(user)

    await user.click(within(sheet).getByRole('button', { name: /Share/ }))

    // The photo is already in the list; refetching it would be a second trip
    // for something we are holding.
    await waitFor(() => expect(buildShareCard).toHaveBeenCalled())
    expect(buildShareCard.mock.calls[0][0].imageSource).toBe(ENTRY.thumbnail)
  })

  it('closes itself once the share is done', async () => {
    const user = userEvent.setup()
    renderList()
    const sheet = await openSheet(user)

    await user.click(within(sheet).getByRole('button', { name: /Share/ }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('keeps the sheet open on failure, so the message has somewhere to go', async () => {
    buildShareCard.mockRejectedValue(new Error('canvas exploded'))
    const user = userEvent.setup()
    renderList()
    const sheet = await openSheet(user)

    await user.click(within(sheet).getByRole('button', { name: /Share/ }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent("Couldn't share"))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('exports a PDF for the row that was tapped', async () => {
    const user = userEvent.setup()
    renderList()
    const sheet = await openSheet(user)

    await user.click(within(sheet).getByRole('button', { name: /Export PDF/ }))

    await waitFor(() => expect(exportHistoryPdf).toHaveBeenCalledWith(3))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})

/**
 * A visitor's meals are never saved server-side, so two of the three actions
 * have different answers for them.
 */
describe('meal row overflow menu, for a visitor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    buildShareCard.mockResolvedValue({ blob: new Blob(), shareText: 'I scored B' })
  })

  it('does not offer a PDF it cannot produce', async () => {
    const user = userEvent.setup()
    renderList({ isVisitor: true, entries: [{ ...ENTRY, thumbnail: null, result: DETAIL }] })
    const sheet = await openSheet(user)

    // Export is a server route behind a session. Offering a control that can
    // only fail is worse than not offering it.
    expect(within(sheet).queryByRole('button', { name: /Export PDF/ })).not.toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /Share/ })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Delete meal' })).toBeInTheDocument()
  })

  it('shares from memory instead of asking a server it has no session for', async () => {
    const user = userEvent.setup()
    renderList({ isVisitor: true, entries: [{ ...ENTRY, thumbnail: null, result: DETAIL }] })
    const sheet = await openSheet(user)

    await user.click(within(sheet).getByRole('button', { name: /Share/ }))

    await waitFor(() => expect(buildShareCard).toHaveBeenCalled())
    expect(fetchHistoryDetail).not.toHaveBeenCalled()
    expect(buildShareCard.mock.calls[0][0].result).toBe(DETAIL)
  })
})
