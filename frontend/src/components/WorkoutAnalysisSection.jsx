import { useEffect, useState } from 'react'
import { fetchWorkoutStats } from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * Workout figures inside the existing Analysis tab.
 *
 * <p>Here rather than on a Progress page of its own, so activity and eating are
 * read together — which is the entire premise of putting a workout feature
 * inside a meal tracker. A separate page would make the two halves of the same
 * week two separate errands.
 *
 * <p>Renders nothing at all before onboarding. An empty stat grid on a tab the
 * user visits for their food would be four zeroes explaining a feature they
 * have not opted into.
 */
export default function WorkoutAnalysisSection({ isVisitor }) {
  const { t } = useLanguage()
  const [stats, setStats] = useState(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (isVisitor) return undefined
    let live = true
    fetchWorkoutStats()
      .then((next) => live && setStats(next))
      .catch(() => live && setFailed(true))
    return () => {
      live = false
    }
  }, [isVisitor])

  // Visitors have no workouts; a failed fetch shouldn't push an error into a
  // screen that is mostly about food and is otherwise working fine.
  if (isVisitor || failed || !stats || !stats.hasProfile) return null

  return (
    <>
      <h2 className="px-1 pt-2 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('workout.analysisHeading')}
      </h2>

      <div className="grid grid-cols-2 gap-3">
        <StatCard
          icon="🏋️"
          label={t('workout.statWorkouts')}
          value={stats.workoutsThisMonth}
          sub={t('workout.statThisMonth')}
        />
        <StatCard
          icon="📅"
          label={t('workout.statConsistency')}
          value={stats.consistencyPercent}
          unit="%"
          sub={t('workout.statConsistencySub', stats.workoutsThisMonth, stats.expectedThisMonth)}
        />
        <StatCard
          icon="⏱️"
          label={t('workout.statMinutesLabel')}
          value={stats.minutesThisMonth}
          sub={t('workout.statThisMonth')}
        />
        <StatCard
          icon="🔥"
          label={t('workout.statStreak')}
          value={stats.streakDays}
          unit={t('workout.statStreakUnit')}
          sub={t('workout.statBest', stats.bestStreakDays)}
        />
      </div>

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <h3 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          {t('workout.gettingStronger')}
        </h3>
        {stats.progressions.length === 0 ? (
          <p className="mt-2 text-xs leading-relaxed text-slate-500 dark:text-slate-400">
            {t('workout.gettingStrongerEmpty')}
          </p>
        ) : (
          <ul className="mt-3 space-y-2.5">
            {stats.progressions.map((p) => (
              <li key={p.key} className="flex items-center justify-between gap-2.5 text-sm">
                <span className="min-w-0 truncate font-semibold text-slate-600 dark:text-slate-300">
                  {p.name}
                </span>
                <span className="shrink-0 font-extrabold text-slate-900 dark:text-slate-100">
                  {p.from} <span className="font-semibold text-slate-400 dark:text-slate-500">→</span> {p.to}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}

/**
 * Deliberately the same shape as the StatCard already in AnalysisScreen rather
 * than a shared import: that one is private to its file and takes a `tone` these
 * don't use. Two small components that look alike beat one with a flag.
 */
function StatCard({ icon, label, value, unit, sub }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center justify-between">
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{label}</p>
        <span className="text-base">{icon}</span>
      </div>
      <p className="mt-1 text-xl font-black text-slate-900 dark:text-slate-100">
        {value}
        {unit && <span className="ml-1 text-xs font-semibold text-slate-500 dark:text-slate-400">{unit}</span>}
      </p>
      {sub && <p className="text-xs text-slate-500 dark:text-slate-400">{sub}</p>}
    </div>
  )
}
