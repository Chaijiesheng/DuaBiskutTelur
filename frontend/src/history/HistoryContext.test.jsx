import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HistoryProvider, useHistory } from './HistoryContext.jsx'

vi.mock('../api.js', () => ({
  fetchHistory: vi.fn(),
  fetchRecentHistory: vi.fn(),
}))
const { fetchHistory, fetchRecentHistory } = await import('../api.js')

/** Stands in for the History and Analysis tabs, which both read the same list. */
function Consumer({ label }) {
  const { entries, loading, error } = useHistory()
  if (error) return <p>{label}: error</p>
  if (loading) return <p>{label}: loading</p>
  return <p>{`${label}: ${entries?.length ?? 0}`}</p>
}

/** Stands in for the History tab's Show more control. */
function Pager() {
  const { hasMore, loadingMore, moreError, loadMore } = useHistory()
  return (
    <div>
      <p>{`more: ${hasMore}`}</p>
      {loadingMore && <p>paging</p>}
      {moreError && <p>page failed</p>}
      <button type="button" onClick={loadMore}>show more</button>
    </div>
  )
}

/** Stands in for anything computing a trend, which must read the uncapped window. */
function TrendConsumer() {
  const { recent } = useHistory()
  return <p>{`trend: ${recent?.length ?? 0}`}</p>
}

const ENTRIES = [
  { id: 1, summary: 'Nasi lemak', calories: 800, grade: 'B', createdAt: '2026-08-20T12:00:00Z' },
  { id: 2, summary: 'Char kway teow', calories: 900, grade: 'C', createdAt: '2026-08-20T09:00:00Z' },
]

/** The endpoint returns a page, not a bare list. */
const page = (entries, hasMore = false) => ({ entries, hasMore })

describe('HistoryProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchHistory.mockResolvedValue(page(ENTRIES))
    fetchRecentHistory.mockResolvedValue(ENTRIES)
  })

  /**
   * The whole reason this provider exists. History and Analysis each called
   * fetchHistory() in their own mount effect, so opening both was two identical
   * requests — each returning up to fifty entries with base64 thumbnails inline.
   */
  it('serves two consumers from a single request', async () => {
    render(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Consumer label="history" />
        <Consumer label="analysis" />
      </HistoryProvider>,
    )

    await waitFor(() => expect(screen.getByText('history: 2')).toBeInTheDocument())
    expect(screen.getByText('analysis: 2')).toBeInTheDocument()
    expect(fetchHistory).toHaveBeenCalledTimes(1)
  })

  /** Switching tabs unmounts and remounts consumers; that must not refetch. */
  it('does not refetch when a consumer remounts', async () => {
    const { rerender } = render(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Consumer label="history" />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('history: 2')).toBeInTheDocument())

    rerender(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Consumer label="analysis" />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('analysis: 2')).toBeInTheDocument())

    expect(fetchHistory).toHaveBeenCalledTimes(1)
  })

  /** A new meal has to invalidate the cache, or History shows a stale list. */
  it('reloads when the version is bumped', async () => {
    const { rerender } = render(
      <HistoryProvider isVisitor={false} visitorEntries={[]} version={0}>
        <Consumer label="history" />
      </HistoryProvider>,
    )
    await waitFor(() => expect(fetchHistory).toHaveBeenCalledTimes(1))

    const withNewMeal = [...ENTRIES, { id: 3, summary: 'Roti canai', calories: 300, grade: 'C' }]
    fetchHistory.mockResolvedValue(page(withNewMeal))
    fetchRecentHistory.mockResolvedValue(withNewMeal)
    rerender(
      <HistoryProvider isVisitor={false} visitorEntries={[]} version={1}>
        <Consumer label="history" />
      </HistoryProvider>,
    )

    await waitFor(() => expect(screen.getByText('history: 3')).toBeInTheDocument())
    expect(fetchHistory).toHaveBeenCalledTimes(2)
  })

  it('never calls the server for a visitor, and passes their in-session meals straight through', async () => {
    render(
      <HistoryProvider isVisitor visitorEntries={[{ id: 9, summary: 'Kopi O' }]}>
        <Consumer label="history" />
      </HistoryProvider>,
    )

    await waitFor(() => expect(screen.getByText('history: 1')).toBeInTheDocument())
    expect(fetchHistory).not.toHaveBeenCalled()
    expect(fetchRecentHistory).not.toHaveBeenCalled()
  })

  /**
   * The list arrives one page at a time. A user logging eight meals a day fills
   * the first page in six days, so a weekly total summed from it was quietly
   * short — and would now also change as they tapped Show more, with nothing on
   * screen to suggest the number had moved. Trends read the uncapped window.
   */
  it('serves trends from the complete window, not the first page', async () => {
    const firstPage = Array.from({ length: 50 }, (_, i) => ({ id: i + 1, calories: 500 }))
    const wholeWeek = Array.from({ length: 73 }, (_, i) => ({ id: i + 1, calories: 500 }))
    fetchHistory.mockResolvedValue(page(firstPage, true))
    fetchRecentHistory.mockResolvedValue(wholeWeek)

    render(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Consumer label="history" />
        <TrendConsumer />
      </HistoryProvider>,
    )

    await waitFor(() => expect(screen.getByText('trend: 73')).toBeInTheDocument())
    expect(screen.getByText('history: 50')).toBeInTheDocument()
  })

  /** Signing out must not leave the previous account's meals readable. */
  it('drops the cached list when the session ends', async () => {
    const { rerender } = render(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Consumer label="history" />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('history: 2')).toBeInTheDocument())

    rerender(
      <HistoryProvider isVisitor visitorEntries={[]}>
        <Consumer label="history" />
      </HistoryProvider>,
    )

    await waitFor(() => expect(screen.getByText('history: 0')).toBeInTheDocument())
  })

  /**
   * The bug this paging exists to fix: meal fifty-one was in the database and
   * on no screen, because the endpoint took no cursor and the service always
   * asked for page zero.
   */
  it('appends the next page rather than replacing the list', async () => {
    const older = [{ id: 3, summary: 'Roti canai', createdAt: '2026-08-19T12:00:00Z' }]
    fetchHistory.mockResolvedValueOnce(page(ENTRIES, true)).mockResolvedValueOnce(page(older, false))

    render(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Consumer label="history" />
        <Pager />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('more: true')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'show more' }))

    await waitFor(() => expect(screen.getByText('history: 3')).toBeInTheDocument())
    expect(screen.getByText('more: false')).toBeInTheDocument()
  })

  /**
   * The cursor is the last row on screen, not a page counter — sent as that
   * row's own createdAt and id, so the server continues exactly where the
   * reader stopped whatever has been added or deleted above it since.
   */
  it('asks for the page behind the last row it holds', async () => {
    fetchHistory.mockResolvedValue(page(ENTRIES, true))

    render(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Pager />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('more: true')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'show more' }))

    await waitFor(() => expect(fetchHistory).toHaveBeenCalledTimes(2))
    expect(fetchHistory).toHaveBeenLastCalledWith({ before: '2026-08-20T09:00:00Z', beforeId: 2 })
  })

  /** Two taps before the first lands must not fetch the same page twice. */
  it('ignores a second tap while a page is already in flight', async () => {
    fetchHistory.mockResolvedValueOnce(page(ENTRIES, true))
    fetchHistory.mockReturnValueOnce(new Promise(() => {}))

    render(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Pager />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('more: true')).toBeInTheDocument())

    const button = screen.getByRole('button', { name: 'show more' })
    await userEvent.click(button)
    await userEvent.click(button)

    expect(fetchHistory).toHaveBeenCalledTimes(2)
    expect(screen.getByText('paging')).toBeInTheDocument()
  })

  /**
   * A page that fails to arrive must not look like the end of the list.
   * Leaving hasMore true keeps the control on screen to retry with, and says
   * what happened rather than going quiet.
   */
  it('keeps the control and reports the failure when a page does not arrive', async () => {
    fetchHistory.mockResolvedValueOnce(page(ENTRIES, true)).mockRejectedValueOnce(new Error('offline'))

    render(
      <HistoryProvider isVisitor={false} visitorEntries={[]}>
        <Pager />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('more: true')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'show more' }))

    expect(await screen.findByText('page failed')).toBeInTheDocument()
    expect(screen.getByText('more: true')).toBeInTheDocument()
  })

  /** A reload starts from the top; pages fetched against the old list are gone. */
  it('drops loaded pages when the list is invalidated', async () => {
    const older = [{ id: 3, summary: 'Roti canai', createdAt: '2026-08-19T12:00:00Z' }]
    fetchHistory.mockResolvedValueOnce(page(ENTRIES, true)).mockResolvedValueOnce(page(older, false))

    const { rerender } = render(
      <HistoryProvider isVisitor={false} visitorEntries={[]} version={0}>
        <Consumer label="history" />
        <Pager />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('more: true')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: 'show more' }))
    await waitFor(() => expect(screen.getByText('history: 3')).toBeInTheDocument())

    fetchHistory.mockResolvedValue(page(ENTRIES, true))
    rerender(
      <HistoryProvider isVisitor={false} visitorEntries={[]} version={1}>
        <Consumer label="history" />
        <Pager />
      </HistoryProvider>,
    )

    await waitFor(() => expect(screen.getByText('history: 2')).toBeInTheDocument())
  })

  /** A visitor's meals are all in memory already; there is no page behind them. */
  it('offers no further pages to a visitor', async () => {
    render(
      <HistoryProvider isVisitor visitorEntries={[{ id: 9, summary: 'Kopi O' }]}>
        <Pager />
      </HistoryProvider>,
    )

    await waitFor(() => expect(screen.getByText('more: false')).toBeInTheDocument())
    expect(fetchHistory).not.toHaveBeenCalled()
  })

  it('reports an expired session to the shell instead of showing an error', async () => {
    const onAuthExpired = vi.fn()
    const unauthenticated = Object.assign(new Error('nope'), { code: 'UNAUTHENTICATED' })
    fetchHistory.mockRejectedValue(unauthenticated)
    fetchRecentHistory.mockRejectedValue(unauthenticated)

    render(
      <HistoryProvider isVisitor={false} visitorEntries={[]} onAuthExpired={onAuthExpired}>
        <Consumer label="history" />
      </HistoryProvider>,
    )

    await waitFor(() => expect(onAuthExpired).toHaveBeenCalled())
    expect(screen.queryByText('history: error')).not.toBeInTheDocument()
  })

  /** An expired session while paging is the same event, and reported the same way. */
  it('reports an expired session that surfaces while paging', async () => {
    const onAuthExpired = vi.fn()
    const unauthenticated = Object.assign(new Error('nope'), { code: 'UNAUTHENTICATED' })
    fetchHistory.mockResolvedValueOnce(page(ENTRIES, true)).mockRejectedValueOnce(unauthenticated)

    render(
      <HistoryProvider isVisitor={false} visitorEntries={[]} onAuthExpired={onAuthExpired}>
        <Pager />
      </HistoryProvider>,
    )
    await waitFor(() => expect(screen.getByText('more: true')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: 'show more' }))

    await waitFor(() => expect(onAuthExpired).toHaveBeenCalled())
    expect(screen.queryByText('page failed')).not.toBeInTheDocument()
  })
})
