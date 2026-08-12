import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { fetchHistory, fetchRecentHistory } from '../api.js'

/**
 * One copy of the meal history for the whole app.
 *
 * <p>The History and Analysis tabs each used to call fetchHistory() in a mount
 * effect, and both unmount on every tab switch — so History → Analysis →
 * History was three identical requests, each returning up to fifty entries.
 * Worse, every entry carries a base64 thumbnail, so that is a few hundred KB of
 * uncacheable JSON re-downloaded on a tab tap, on mobile data.
 *
 * <p>Fetched once per signed-in session and held here instead. Anything that
 * changes the history tells the provider to reload by bumping {@code version} —
 * declarative rather than an imperative refresh handle, so the shell doesn't
 * need to consume a context it is itself rendering.
 *
 * <p>Visitors have no server history at all; their meals live in App state and
 * are passed straight through, so consumers don't have to care which they are.
 */
const HistoryContext = createContext(null)

export function HistoryProvider({ isVisitor, visitorEntries, onAuthExpired, version = 0, children }) {
  const [fetched, setFetched] = useState(null)
  // The list is capped at 50 rows, which is fine for a list and wrong for
  // arithmetic. Trends read this instead: the same window, complete, and three
  // columns wide so being uncapped costs almost nothing.
  const [recent, setRecent] = useState(null)
  const [error, setError] = useState(false)
  // Concurrent callers share one request rather than racing duplicates.
  const inFlightRef = useRef(null)

  const load = useCallback(() => {
    if (inFlightRef.current) {
      return inFlightRef.current
    }
    const request = Promise.all([fetchHistory(), fetchRecentHistory()])
      .then(([list, points]) => {
        setFetched(list)
        setRecent(points)
        setError(false)
        return list
      })
      .catch((e) => {
        if (e.code === 'UNAUTHENTICATED') onAuthExpired?.()
        else setError(true)
      })
      .finally(() => {
        inFlightRef.current = null
      })
    inFlightRef.current = request
    return request
  }, [onAuthExpired])

  useEffect(() => {
    if (isVisitor) {
      // Signing out must drop the previous account's meals, not leave them
      // readable in the next visitor session.
      setFetched(null)
      setRecent(null)
      setError(false)
      return
    }
    load()
    // version is the invalidation signal: bumping it reloads, and nothing else
    // in this list changes on a tab switch, which is the point.
  }, [isVisitor, version, load])

  /**
   * Patches one cached row in place. A portion correction changes a meal's
   * calories, score and grade; refetching the whole list to learn that would
   * pull every entry's base64 thumbnail down again for a change the response
   * already told us about. Same reasoning as removeEntry, opposite direction.
   */
  const updateEntry = useCallback((id, patch) => {
    const apply = (list) => list?.map((entry) => (entry.id === id ? { ...entry, ...patch } : entry)) ?? list
    setFetched(apply)
    setRecent(apply)
  }, [])

  const removeEntry = useCallback((id) => {
    setFetched((prev) => prev?.filter((entry) => entry.id !== id) ?? prev)
    setRecent((prev) => prev?.filter((entry) => entry.id !== id) ?? prev)
  }, [])

  const value = useMemo(
    () => ({
      entries: isVisitor ? visitorEntries : fetched,
      // Visitors' in-session meals are already complete — there is no cap to
      // work around, so both views read the same array.
      recent: isVisitor ? visitorEntries : recent,
      // `entries === null` means still loading; an empty array is a real answer.
      loading: !isVisitor && fetched === null && !error,
      error: !isVisitor && error,
      removeEntry,
      updateEntry,
    }),
    [isVisitor, visitorEntries, fetched, recent, error, removeEntry, updateEntry],
  )

  return <HistoryContext.Provider value={value}>{children}</HistoryContext.Provider>
}

export function useHistory() {
  const ctx = useContext(HistoryContext)
  if (!ctx) throw new Error('useHistory must be used within a HistoryProvider')
  return ctx
}
