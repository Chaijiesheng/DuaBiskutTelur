import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { fetchWorkoutAlternatives } from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * The in-workout runner — variant A, "set led".
 *
 * <p>One exercise at a time, the rep count as the largest thing on screen, and a
 * single oversized primary action at thumb height. The prototype's other option
 * was a checklist of every exercise with tappable set pills, which is a better
 * screen to *look* at and a worse one to *use*: mid-set you are out of breath,
 * possibly holding something, and the question is only ever "did I finish this
 * set". A checklist makes you find the answer among eighteen controls; this puts
 * it under your thumb.
 *
 * <p>The app chrome is hidden while this is open (see App.jsx). That is not
 * decoration — a tab bar during a workout is an invitation to leave one.
 */

const REST_SECONDS = 45
const REST_BONUS_SECONDS = 20

export default function WorkoutSession({
  session, onExit, onLogSet, onReplace, onFinish, pendingSets, online,
}) {
  const { t } = useLanguage()

  const exercises = session.exercises
  // Where to resume: the first set that isn't done. A session picked up on
  // another device, or after the app was killed mid-workout, lands exactly
  // where it was rather than back at exercise one.
  const firstUndone = useMemo(() => findFirstUndone(exercises), [exercises])
  const [cursor, setCursor] = useState(firstUndone)
  const [resting, setResting] = useState(false)
  const [restLeft, setRestLeft] = useState(REST_SECONDS)
  const [replaceOpen, setReplaceOpen] = useState(false)

  const exercise = exercises[Math.min(cursor.exercise, exercises.length - 1)]
  const setIndex = Math.min(cursor.set, exercise.sets - 1)
  const totalSets = session.totalSets
  const doneSets = session.completedSets

  const tick = useRef(null)
  const stopRest = useCallback(() => {
    clearInterval(tick.current)
    tick.current = null
    setResting(false)
  }, [])

  const startRest = useCallback(() => {
    clearInterval(tick.current)
    setResting(true)
    setRestLeft(REST_SECONDS)
    tick.current = setInterval(() => {
      setRestLeft((left) => {
        if (left <= 1) {
          clearInterval(tick.current)
          tick.current = null
          setResting(false)
          return 0
        }
        return left - 1
      })
    }, 1000)
  }, [])

  useEffect(() => () => clearInterval(tick.current), [])

  const completeSet = () => {
    onLogSet(exercise.position, setIndex, true)
    const next = advance(exercises, exercise.position, setIndex)
    if (!next) {
      onFinish()
      return
    }
    setCursor(next)
    startRest()
  }

  const skipExercise = () => {
    const nextExercise = exercise.position + 1
    if (nextExercise >= exercises.length) {
      onFinish()
      return
    }
    stopRest()
    setCursor({ exercise: nextExercise, set: 0 })
  }

  const setRows = Array.from({ length: exercise.sets }, (_, i) => {
    const done = exercise.completedSets.includes(i)
    return { index: i, done, current: i === setIndex && !done }
  })

  const unit = t(`workout.units.${exercise.unit}`)

  return (
    <div className="relative pt-2">
      <div className="flex items-center gap-2.5">
        <button
          type="button"
          onClick={onExit}
          aria-label={t('workout.leave')}
          className="h-9 w-9 shrink-0 rounded-xl border border-slate-200 text-slate-600 dark:border-slate-700 dark:text-slate-300"
        >
          ✕
        </button>
        <div aria-hidden="true" className="flex flex-1 gap-1">
          {exercises.map((e, i) => (
            <span
              key={e.position}
              className={`h-1 flex-1 rounded-full ${
                i < cursor.exercise
                  ? 'bg-grade-aplus dark:bg-green-400'
                  : i === cursor.exercise
                    ? 'bg-slate-400 dark:bg-slate-500'
                    : 'bg-slate-200 dark:bg-slate-700'
              }`}
            />
          ))}
        </div>
        <span className="shrink-0 text-xs font-bold text-slate-500 dark:text-slate-400">
          {doneSets}/{totalSets}
        </span>
      </div>

      {!online && (
        <p className="mt-3 rounded-xl bg-amber-50 px-3 py-2 text-xs font-semibold text-amber-800 dark:bg-amber-950/40 dark:text-amber-300">
          {t('workout.offlineBody')}
          {pendingSets > 0 && ` · ${t('workout.unsynced', pendingSets)}`}
        </p>
      )}

      <p className="mt-4 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('workout.exerciseOf', exercise.position + 1, exercises.length)}
      </p>
      <h2 className="mt-1 text-3xl font-black tracking-tight text-slate-900 dark:text-slate-100">
        {exercise.name}
      </h2>
      <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{exercise.target}</p>

      <div className="mt-4 flex items-baseline justify-between">
        <p className="text-5xl font-black leading-none tracking-tighter text-slate-900 dark:text-slate-100">
          {exercise.reps}
          {unit && <span className="ml-1 text-xl font-extrabold text-slate-500 dark:text-slate-400">{unit}</span>}
        </p>
        <p className="text-sm font-extrabold text-slate-500 dark:text-slate-400">
          {t('workout.setOf', setIndex + 1, exercise.sets)}
        </p>
      </div>

      <ul className="mt-3.5 space-y-1.5">
        {setRows.map((row) => (
          <li
            key={row.index}
            className={`flex items-center gap-3 rounded-xl border-2 px-3.5 py-3 ${
              row.current
                ? 'border-grade-aplus bg-green-50 dark:border-green-400 dark:bg-green-950/40'
                : 'border-slate-200 dark:border-slate-700'
            }`}
          >
            <span
              aria-hidden="true"
              className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full border-2 text-xs font-extrabold ${
                row.done
                  ? 'border-grade-aplus bg-grade-aplus text-white dark:border-green-400 dark:bg-green-400 dark:text-slate-900'
                  : row.current
                    ? 'border-grade-aplus text-grade-aplus dark:border-green-400 dark:text-green-400'
                    : 'border-slate-200 text-slate-400 dark:border-slate-600 dark:text-slate-500'
              }`}
            >
              {row.done ? '✓' : row.index + 1}
            </span>
            <span
              className={`flex-1 text-sm font-bold ${
                row.done ? 'text-slate-400 dark:text-slate-500' : 'text-slate-900 dark:text-slate-100'
              }`}
            >
              {t('workout.setLabel', row.index + 1)}
            </span>
            <span
              className={`text-xs font-bold ${
                row.done || row.current
                  ? 'text-grade-aplus dark:text-green-400'
                  : 'text-slate-500 dark:text-slate-400'
              }`}
            >
              {row.done
                ? t('workout.setDone')
                : row.current
                  ? t('workout.setCurrent')
                  : `${exercise.reps}${unit ? ` ${unit}` : ''}`}
            </span>
          </li>
        ))}
      </ul>

      {/* The one instruction that matters, at the moment it applies. */}
      {exercise.cue && (
        <p className="mt-3 text-xs leading-relaxed text-slate-500 dark:text-slate-400">{exercise.cue}</p>
      )}

      <button
        type="button"
        onClick={completeSet}
        className="mt-4 min-h-[3.75rem] w-full rounded-2xl bg-grade-aplus py-5 text-lg font-extrabold text-white shadow-sm"
      >
        {t('workout.completeSet')}
      </button>
      <div className="mt-2.5 flex gap-2.5">
        <button
          type="button"
          onClick={() => setReplaceOpen(true)}
          className="min-h-[3rem] flex-1 rounded-xl border border-slate-200 py-3 text-sm font-bold text-slate-600 dark:border-slate-700 dark:text-slate-300"
        >
          {t('workout.cantDoThis')}
        </button>
        <button
          type="button"
          onClick={skipExercise}
          className="min-h-[3rem] flex-1 rounded-xl border border-slate-200 py-3 text-sm font-bold text-slate-600 dark:border-slate-700 dark:text-slate-300"
        >
          {t('workout.skipExercise')}
        </button>
      </div>

      {resting && (
        <RestOverlay
          secondsLeft={restLeft}
          nextName={exercise.name}
          nextSet={setIndex + 1}
          onAdd={() => setRestLeft((left) => left + REST_BONUS_SECONDS)}
          onSkip={stopRest}
        />
      )}

      {replaceOpen && (
        <ReplaceSheet
          sessionId={session.id}
          exercise={exercise}
          onClose={() => setReplaceOpen(false)}
          onConfirm={async (key) => {
            await onReplace(exercise.position, key)
            setReplaceOpen(false)
          }}
        />
      )}
    </div>
  )
}

/** The first set that has not been logged, or the last one if the session is complete. */
function findFirstUndone(exercises) {
  for (const exercise of exercises) {
    for (let i = 0; i < exercise.sets; i++) {
      if (!exercise.completedSets.includes(i)) {
        return { exercise: exercises.indexOf(exercise), set: i }
      }
    }
  }
  return { exercise: 0, set: 0 }
}

/** The next slot after finishing (position, setIndex), or null if that was the last one. */
function advance(exercises, position, setIndex) {
  const index = exercises.findIndex((e) => e.position === position)
  if (setIndex + 1 < exercises[index].sets) {
    return { exercise: index, set: setIndex + 1 }
  }
  if (index + 1 < exercises.length) {
    return { exercise: index + 1, set: 0 }
  }
  return null
}

/**
 * The rest timer.
 *
 * <p>A dialog rather than an inline strip, because rest is the one part of a
 * session where the right thing to do is nothing — and something covering the
 * screen says that better than a number next to a button you could still press.
 */
function RestOverlay({ secondsLeft, nextName, nextSet, onAdd, onSkip }) {
  const { t } = useLanguage()
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('workout.restTitle')}
      className="fixed inset-0 z-30 mx-auto flex max-w-md flex-col items-center justify-center gap-1.5 bg-slate-900/80 p-7 backdrop-blur-sm"
    >
      <p className="text-xs font-extrabold uppercase tracking-widest text-green-200">
        {t('workout.restTitle')}
      </p>
      <p className="text-6xl font-black leading-none tracking-tighter text-white tabular-nums">
        0:{String(secondsLeft).padStart(2, '0')}
      </p>
      <p className="mt-1 text-center text-sm text-slate-300">
        {t('workout.restNext', nextName, nextSet)}
      </p>
      <div className="mt-5 flex w-full max-w-xs gap-2.5">
        <button
          type="button"
          onClick={onAdd}
          className="min-h-[3.25rem] flex-1 rounded-2xl border border-white/40 py-4 text-sm font-bold text-white"
        >
          {t('workout.restAdd')}
        </button>
        <button
          type="button"
          onClick={onSkip}
          className="min-h-[3.25rem] flex-1 rounded-2xl bg-grade-aplus py-4 text-sm font-extrabold text-white"
        >
          {t('workout.restSkip')}
        </button>
      </div>
    </div>
  )
}

/**
 * The swap sheet.
 *
 * <p>Options come from the server, which draws them from the same movement
 * pattern at no more equipment than the exercise being replaced. That is what
 * makes "each of these keeps the same job in your session" a fact about the list
 * rather than a claim in the copy.
 */
function ReplaceSheet({ sessionId, exercise, onClose, onConfirm }) {
  const { t } = useLanguage()
  const [options, setOptions] = useState(null)
  const [picked, setPicked] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let live = true
    fetchWorkoutAlternatives(sessionId, exercise.position)
      .then((list) => {
        if (!live) return
        setOptions(list)
        setPicked(list[0]?.key ?? null)
      })
      .catch(() => {
        if (live) setOptions([])
      })
    return () => {
      live = false
    }
  }, [sessionId, exercise.position])

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('workout.replaceTitle', exercise.name)}
      className="fixed inset-0 z-30 mx-auto flex max-w-md items-end bg-slate-900/50"
    >
      <div className="max-h-[85vh] w-full overflow-y-auto rounded-t-3xl bg-white px-4 pb-6 pt-5 dark:bg-slate-800">
        <div aria-hidden="true" className="mx-auto mb-3.5 h-1 w-9 rounded-full bg-slate-200 dark:bg-slate-600" />
        <h3 className="text-lg font-black tracking-tight text-slate-900 dark:text-slate-100">
          {t('workout.replaceTitle', exercise.name)}
        </h3>
        <p className="mt-1 text-xs leading-relaxed text-slate-500 dark:text-slate-400">
          {t('workout.replaceBody')}
        </p>

        {options === null ? (
          <div role="status" className="py-8 text-center text-sm text-slate-500 dark:text-slate-400">
            …
          </div>
        ) : options.length === 0 ? (
          <p className="py-6 text-sm text-slate-500 dark:text-slate-400">{t('workout.replaceEmpty')}</p>
        ) : (
          <div className="mt-3.5 space-y-2.5">
            {options.map((option) => (
              <button
                key={option.key}
                type="button"
                aria-pressed={picked === option.key}
                onClick={() => setPicked(option.key)}
                className={`w-full rounded-2xl border-2 p-3.5 text-left ${
                  picked === option.key
                    ? 'border-grade-aplus bg-green-50 dark:border-green-400 dark:bg-green-950/40'
                    : 'border-slate-200 dark:border-slate-700'
                }`}
              >
                <div className="flex items-center justify-between gap-2.5">
                  <span
                    className={`text-base font-extrabold ${
                      picked === option.key
                        ? 'text-grade-aplus dark:text-green-400'
                        : 'text-slate-900 dark:text-slate-100'
                    }`}
                  >
                    {option.name}
                  </span>
                  {picked === option.key && (
                    <span aria-hidden="true" className="text-sm font-extrabold text-grade-aplus dark:text-green-400">
                      ✓
                    </span>
                  )}
                </div>
                <p className="mt-1 text-xs leading-relaxed text-slate-500 dark:text-slate-400">{option.why}</p>
              </button>
            ))}
          </div>
        )}

        {options?.length > 0 && (
          <button
            type="button"
            disabled={!picked || busy}
            onClick={async () => {
              setBusy(true)
              try {
                await onConfirm(picked)
              } finally {
                setBusy(false)
              }
            }}
            className="mt-4 min-h-[3.25rem] w-full rounded-2xl bg-grade-aplus py-4 text-base font-extrabold text-white disabled:opacity-60"
          >
            {t('workout.replaceUse')}
          </button>
        )}
        <button
          type="button"
          onClick={onClose}
          className="w-full py-3 text-xs font-semibold text-slate-500 dark:text-slate-400"
        >
          {t('workout.replaceKeep')}
        </button>
      </div>
    </div>
  )
}
