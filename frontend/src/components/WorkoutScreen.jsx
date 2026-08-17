import { useState } from 'react'
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import SignInBanner from './SignInBanner.jsx'
import WorkoutComplete from './WorkoutComplete.jsx'
import WorkoutDashboard from './WorkoutDashboard.jsx'
import WorkoutDetail from './WorkoutDetail.jsx'
import WorkoutOnboarding from './WorkoutOnboarding.jsx'
import WorkoutSession from './WorkoutSession.jsx'
import { WorkoutPlanSkeleton } from './Skeleton.jsx'
import { useWorkout } from '../workout/WorkoutContext.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * The Workout tab.
 *
 * <p>Nested routes rather than a phase state machine, matching
 * {@code HistoryScreen}: opening a session and pressing back is a common back
 * press, and holding it in component state is exactly how that ends up exiting
 * the app on Android.
 *
 * <p>Onboarding and completion are the two exceptions and are held in state on
 * purpose. Neither is a place you can be sent to — "your setup, question four"
 * and "the summary of a workout you have not done" are not linkable — and a
 * route for either would be a URL that breaks when opened cold.
 */
export default function WorkoutScreen({ isVisitor, online }) {
  return (
    <Routes>
      <Route index element={<WorkoutHome isVisitor={isVisitor} online={online} />} />
      <Route path="detail" element={<DetailRoute />} />
      <Route path="session" element={<SessionRoute online={online} />} />
    </Routes>
  )
}

function WorkoutHome({ isVisitor, online }) {
  const { t } = useLanguage()
  const navigate = useNavigate()
  const { data, loading, error, reload, saveProfile, start, setSkipped, pendingSets } = useWorkout()
  const [onboarding, setOnboarding] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)

  if (isVisitor) {
    return (
      <div className="space-y-4 pt-10 text-center">
        <span aria-hidden="true" className="text-5xl">🏋️</span>
        <h2 className="text-lg font-extrabold text-slate-900 dark:text-slate-100">
          {t('workout.visitorTitle')}
        </h2>
        <p className="mx-auto max-w-[17rem] text-sm leading-relaxed text-slate-500 dark:text-slate-400">
          {t('workout.visitorBody')}
        </p>
        <SignInBanner />
      </div>
    )
  }

  if (saving) {
    return <WorkoutPlanSkeleton label={t('workout.buildingSr')} title={t('workout.buildingTitle')} phase={t('workout.buildingPhase')} />
  }

  if (onboarding) {
    return (
      <WorkoutOnboarding
        saving={saving}
        error={saveError}
        onCancel={() => setOnboarding(false)}
        onSave={async (profile) => {
          setSaving(true)
          setSaveError(null)
          try {
            await saveProfile(profile)
            setOnboarding(false)
          } catch (e) {
            setSaveError(e.message)
          } finally {
            setSaving(false)
          }
        }}
      />
    )
  }

  if (loading) {
    return <WorkoutPlanSkeleton label={t('workout.buildingSr')} />
  }

  if (error || !data) {
    return (
      <div className="pt-10 text-center">
        <span aria-hidden="true" className="text-5xl">😅</span>
        <h2 className="mt-3 text-base font-extrabold text-slate-900 dark:text-slate-100">
          {t('workout.loadError')}
        </h2>
        <p className="mx-auto mt-2 max-w-[17rem] text-sm leading-relaxed text-slate-500 dark:text-slate-400">
          {t('workout.loadErrorBody')}
        </p>
        <button
          type="button"
          onClick={reload}
          className="mt-4 min-h-[2.75rem] rounded-xl bg-grade-aplus px-5 py-3 text-sm font-bold text-white"
        >
          {t('workout.tryAgain')}
        </button>
      </div>
    )
  }

  if (!data.hasProfile) {
    return <EmptyWorkoutState onStart={() => setOnboarding(true)} />
  }

  return (
    <WorkoutDashboard
      data={data}
      online={online}
      pendingSets={pendingSets}
      onOpenDetail={() => navigate('/workout/detail')}
      onSkip={() => setSkipped(true).catch(() => {})}
      onUnskip={() => setSkipped(false).catch(() => {})}
      onStart={() => {
        start()
        navigate('/workout/session')
      }}
    />
  )
}

/** Before onboarding: what the app already knows, and one thing to do about it. */
function EmptyWorkoutState({ onStart }) {
  const { t } = useLanguage()
  return (
    <div className="pt-2">
      <div className="pt-6 text-center">
        <span aria-hidden="true" className="text-5xl">🏋️</span>
        <h2 className="mt-3 text-lg font-extrabold text-slate-900 dark:text-slate-100">
          {t('workout.emptyTitle')}
        </h2>
        <p className="mx-auto mt-2 max-w-[17rem] text-sm leading-relaxed text-slate-500 dark:text-slate-400">
          {t('workout.emptyBody')}
        </p>
      </div>
      <button
        type="button"
        onClick={onStart}
        className="mt-5 min-h-[3.25rem] w-full rounded-2xl bg-grade-aplus py-4 text-base font-extrabold text-white shadow-sm"
      >
        {t('workout.setUp')}
      </button>
    </div>
  )
}

function DetailRoute() {
  const navigate = useNavigate()
  const { session, start } = useWorkout()
  // A cold load of /workout/detail has no session yet; the home route owns the
  // fetch, so send them there rather than rendering an empty shell.
  if (!session) return <RedirectHome />
  return (
    <WorkoutDetail
      session={session}
      onBack={() => navigate('/workout')}
      onStart={() => {
        start()
        navigate('/workout/session')
      }}
    />
  )
}

function SessionRoute({ online }) {
  const navigate = useNavigate()
  const { session, logSet, replaceExercise, complete, pendingSets } = useWorkout()
  const [summary, setSummary] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  if (!session) return <RedirectHome />

  const finish = async (answers = {}) => {
    setSubmitting(true)
    try {
      const result = await complete({ ...answers, actualMinutes: null })
      setSummary(result)
    } catch {
      // The workout happened; the sets are logged or queued. Falling back to a
      // local summary means a flaky connection at the last step costs the coach's
      // sentence rather than the whole completion screen.
      setSummary({
        minutes: session.minutes,
        exercises: session.exercises.length,
        sets: session.completedSets,
        coachReply: '',
      })
    } finally {
      setSubmitting(false)
    }
  }

  if (summary) {
    return (
      <WorkoutComplete
        summary={summary}
        submitting={submitting}
        onSubmit={(answers) => finish(answers)}
        onDone={() => navigate('/workout')}
      />
    )
  }

  return (
    <WorkoutSession
      session={session}
      online={online}
      pendingSets={pendingSets}
      onExit={() => navigate('/workout')}
      onLogSet={logSet}
      onReplace={replaceExercise}
      onFinish={() => finish()}
    />
  )
}

/**
 * A cold load of a sub-route has no session — the home route owns the fetch.
 * Replace rather than push, so back does not bounce between the two.
 */
function RedirectHome() {
  return <Navigate to="/workout" replace />
}
