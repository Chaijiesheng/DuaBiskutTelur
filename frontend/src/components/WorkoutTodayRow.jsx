import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchWorkoutGlance } from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * One row on the Snap tab's Today card, under a divider.
 *
 * <p>One row, and that is the whole design. The workout should be visible from
 * the food screen without the food screen becoming a fitness screen — so this
 * gets a line and a button, not a card, not a progress ring, and not the
 * exercise list.
 *
 * <p>Reads {@code /api/workout/glance}, which is read-only by construction.
 * This is the app's home screen: pointing it at {@code /api/workout/today}
 * would plan a session — and fire a Gemini call for the coach note — on every
 * user's first open of the day, for a sentence two taps away that most of them
 * would never see.
 */
export default function WorkoutTodayRow() {
  const { t } = useLanguage()
  const navigate = useNavigate()
  const [glance, setGlance] = useState(null)

  useEffect(() => {
    let live = true
    fetchWorkoutGlance()
      .then((next) => live && setGlance(next))
      // Silent on purpose. This is a row on somebody's food screen; a workout
      // fetch failing must not put an error banner in front of the thing they
      // actually opened the app to do.
      .catch(() => {})
    return () => {
      live = false
    }
  }, [])

  // Nothing before workout setup. A prompt here would be an advert on the
  // screen the user opens most, for a tab they have already walked past.
  if (!glance || !glance.hasProfile) return null

  const { session, trainingDay } = glance
  const { title, status } = describe(session, trainingDay, t)

  return (
    <div className="mt-3 flex items-center justify-between gap-2.5 border-t border-slate-200 pt-3 dark:border-slate-700">
      <div className="flex min-w-0 items-center gap-2.5">
        <span
          aria-hidden="true"
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-green-50 text-sm dark:bg-green-950/40"
        >
          🏋️
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-bold text-slate-900 dark:text-slate-100">{title}</p>
          <p className="truncate text-xs text-slate-500 dark:text-slate-400">{status}</p>
        </div>
      </div>
      <button
        type="button"
        onClick={() => navigate('/workout')}
        className="min-h-[2.5rem] shrink-0 rounded-xl border border-slate-200 px-3.5 py-2 text-xs font-bold text-slate-600 dark:border-slate-700 dark:text-slate-300"
      >
        {t('workout.glanceOpen')}
      </button>
    </div>
  )
}

/**
 * What the two lines say.
 *
 * <p>The case worth naming is the last one: with no stored session, "you have
 * not opened the Workout tab yet" and "today is a rest day" are the same absence
 * in the database. {@code trainingDay} is what tells them apart, and conflating
 * them would either nag somebody on their rest day or let a training day pass
 * silently.
 */
function describe(session, trainingDay, t) {
  if (!session) {
    return trainingDay
      ? { title: t('workout.glanceNotPlannedTitle'), status: t('workout.glanceNotPlanned') }
      : { title: t('workout.glanceRestDayTitle'), status: t('workout.glanceRestDay') }
  }
  const title = `${session.title} · ${t('workout.minutesChip', session.minutes)}`
  switch (session.status) {
    case 'completed':
      return { title, status: t('workout.glanceDone') }
    case 'skipped':
      return { title, status: t('workout.glanceSkipped') }
    case 'in_progress':
      return {
        title,
        status: t('workout.glanceInProgress', session.completedSets, session.totalSets),
      }
    default:
      return { title, status: t('workout.glancePlanned') }
  }
}
