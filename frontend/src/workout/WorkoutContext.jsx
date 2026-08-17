import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import {
  completeWorkoutSession,
  fetchWorkoutToday,
  logWorkoutSet,
  replaceWorkoutExercise,
  saveWorkoutProfile,
  skipWorkoutSession,
  startWorkoutSession,
} from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * One copy of today's workout for the whole tab, and the only place that talks
 * to the workout API.
 *
 * <p>The dashboard, the detail view, the session runner and the completion
 * screen are four routes over the same session. Fetching per route would mean
 * four requests to walk one workout and a visible reload every time the user
 * pressed back — so it is fetched once and every mutation folds the response
 * back into the same object.
 *
 * <p>The interesting part is set logging. Sets are the one thing people do in
 * places with no signal, so a tapped set updates local state first and reaches
 * the server second. If the request fails it is queued in localStorage and
 * replayed on reconnect. That is only safe because the server treats the write
 * as "make this set done" rather than "add one", and enforces uniqueness on
 * (session, exercise, set) — replaying a queue blindly is therefore correct
 * rather than merely likely to be.
 */
const WorkoutContext = createContext(null)

const QUEUE_KEY = 'dbt_workout_set_queue'

function readQueue() {
  try {
    const parsed = JSON.parse(localStorage.getItem(QUEUE_KEY))
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function writeQueue(queue) {
  try {
    if (queue.length === 0) localStorage.removeItem(QUEUE_KEY)
    else localStorage.setItem(QUEUE_KEY, JSON.stringify(queue))
  } catch {
    /* private mode, or a full quota — the in-memory state is still correct */
  }
}

/** One entry per (session, exercise, set); a later tap on the same set replaces the earlier one. */
function enqueue(entry) {
  const rest = readQueue().filter(
    (q) =>
      !(
        q.sessionId === entry.sessionId &&
        q.exercisePosition === entry.exercisePosition &&
        q.setIndex === entry.setIndex
      ),
  )
  writeQueue([...rest, entry])
}

/**
 * Applies a set change to a session object without a round trip, so the tick
 * appears under the user's thumb rather than after the network.
 */
function applySetLocally(session, exercisePosition, setIndex, done) {
  if (!session) return session
  const exercises = session.exercises.map((exercise) => {
    if (exercise.position !== exercisePosition) return exercise
    const without = exercise.completedSets.filter((i) => i !== setIndex)
    return {
      ...exercise,
      completedSets: done ? [...without, setIndex].sort((a, b) => a - b) : without,
    }
  })
  return {
    ...session,
    exercises,
    completedSets: exercises.reduce((sum, e) => sum + e.completedSets.length, 0),
    // Logging a set starts the session server-side too; mirroring it here keeps
    // the dashboard's button reading "Resume" straight after the first tap.
    status: done && session.status === 'planned' ? 'in_progress' : session.status,
  }
}

export function WorkoutProvider({ isVisitor, children }) {
  const { lang } = useLanguage()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [pending, setPending] = useState(() => readQueue().length)
  const inFlightRef = useRef(null)

  const load = useCallback(() => {
    if (inFlightRef.current) return inFlightRef.current
    const request = fetchWorkoutToday(lang)
      .then((next) => {
        setData(next)
        setError(null)
        return next
      })
      .catch((e) => {
        setError(e)
        return null
      })
      .finally(() => {
        inFlightRef.current = null
      })
    inFlightRef.current = request
    return request
  }, [lang])

  useEffect(() => {
    if (isVisitor) {
      // Signing out must not leave the previous account's plan on screen.
      setData(null)
      setError(null)
      return
    }
    load()
  }, [isVisitor, load])

  /** Replaces just the session, leaving the coach note, week strip and stats alone. */
  const mergeSession = useCallback((session) => {
    setData((prev) => (prev ? { ...prev, session } : prev))
  }, [])

  const flushQueue = useCallback(async () => {
    const queue = readQueue()
    if (queue.length === 0) return
    const failed = []
    let latest = null
    for (const entry of queue) {
      try {
        latest = await logWorkoutSet(
          entry.sessionId, entry.exercisePosition, entry.setIndex, entry.done,
        )
      } catch {
        // Kept for the next attempt. Not retried in a loop here: if the network
        // is down, everything after this will fail too, and hammering it just
        // burns battery on a phone that is already having a bad time.
        failed.push(entry)
      }
    }
    writeQueue(failed)
    setPending(failed.length)
    if (latest && failed.length === 0) mergeSession(latest)
  }, [mergeSession])

  useEffect(() => {
    if (isVisitor) return undefined
    const onOnline = () => { flushQueue() }
    window.addEventListener('online', onOnline)
    // Also on mount: the queue may have survived the app being closed offline.
    if (navigator.onLine) flushQueue()
    return () => window.removeEventListener('online', onOnline)
  }, [isVisitor, flushQueue])

  const logSet = useCallback(
    (exercisePosition, setIndex, done) => {
      const sessionId = data?.session?.id
      if (!sessionId) return Promise.resolve()
      setData((prev) =>
        prev ? { ...prev, session: applySetLocally(prev.session, exercisePosition, setIndex, done) } : prev,
      )
      return logWorkoutSet(sessionId, exercisePosition, setIndex, done)
        .then((session) => {
          mergeSession(session)
        })
        .catch(() => {
          // Deliberately not surfaced as an error and deliberately not rolled
          // back. The set genuinely happened; the app's job is to remember it
          // until it can be told, not to un-tick it mid-workout.
          enqueue({ sessionId, exercisePosition, setIndex, done })
          setPending(readQueue().length)
        })
    },
    [data?.session?.id, mergeSession],
  )

  const start = useCallback(async () => {
    const id = data?.session?.id
    if (!id) return
    try {
      mergeSession(await startWorkoutSession(id))
    } catch {
      // Starting is bookkeeping, not a gate. Failing to record it must not stop
      // somebody who is standing there ready to train.
    }
  }, [data?.session?.id, mergeSession])

  const setSkipped = useCallback(
    async (skipped) => {
      const id = data?.session?.id
      if (!id) return
      mergeSession(await skipWorkoutSession(id, skipped))
    },
    [data?.session?.id, mergeSession],
  )

  const replaceExercise = useCallback(
    async (position, exerciseKey) => {
      const id = data?.session?.id
      if (!id) return
      mergeSession(await replaceWorkoutExercise(id, position, exerciseKey))
    },
    [data?.session?.id, mergeSession],
  )

  const complete = useCallback(
    async ({ feel, energy, actualMinutes }) => {
      const id = data?.session?.id
      if (!id) return null
      // Anything still queued belongs to this session; sending it first means the
      // completion screen's set count is the real one.
      await flushQueue()
      const result = await completeWorkoutSession(id, { feel, energy, actualMinutes, lang })
      mergeSession(result.session)
      return result
    },
    [data?.session?.id, flushQueue, lang, mergeSession],
  )

  const saveProfile = useCallback(
    async (profile) => {
      const next = await saveWorkoutProfile(profile, lang)
      setData(next)
      setError(null)
      return next
    },
    [lang],
  )

  const value = useMemo(
    () => ({
      data,
      session: data?.session ?? null,
      // `data === null` with no error means still loading; hasProfile:false is a
      // real answer, not an absence.
      loading: !isVisitor && data === null && !error,
      error,
      isVisitor,
      pendingSets: pending,
      reload: load,
      saveProfile,
      start,
      logSet,
      setSkipped,
      replaceExercise,
      complete,
    }),
    [data, isVisitor, error, pending, load, saveProfile, start, logSet, setSkipped, replaceExercise, complete],
  )

  return <WorkoutContext.Provider value={value}>{children}</WorkoutContext.Provider>
}

export function useWorkout() {
  const ctx = useContext(WorkoutContext)
  if (!ctx) throw new Error('useWorkout must be used within a WorkoutProvider')
  return ctx
}
