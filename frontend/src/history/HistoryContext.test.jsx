import { render, screen, waitFor } from '@testing-library/react'
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

/** Stands in for anything computing a trend, which must read the uncapped window. */
function TrendConsumer() {
  const { recent } = useHistory()
  return <p>{`trend: ${recent?.length ?? 0}`}</p>
}

const ENTRIES = [
  { id: 1, summary: 'Nasi lemak', calories: 800, grade: 'B' },
  { id: 2, summary: 'Char kway teow', calories: 900, grade: 'C' },
]

describe('HistoryProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchHistory.mockResolvedValue(ENTRIES)
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
    fetchHistory.mockResolvedValue(withNewMeal)
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
   * The list endpoint is capped at 50 rows. A user logging eight meals a day
   * exhausts that in six days, so a weekly total summed from it was quietly
   * short — with nothing on screen to suggest the number was wrong. Trends read
   * the uncapped window instead.
   */
  it('serves trends from the complete window, not the capped list', async () => {
    const cappedList = Array.from({ length: 50 }, (_, i) => ({ id: i + 1, calories: 500 }))
    const wholeWeek = Array.from({ length: 73 }, (_, i) => ({ id: i + 1, calories: 500 }))
    fetchHistory.mockResolvedValue(cappedList)
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
})
