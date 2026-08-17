import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * The session preview: what you are about to do, before you start doing it.
 *
 * <p>Sets and reps are visible here; the form cue is not. A cue is an
 * instruction for the moment the movement is in front of you, and six of them
 * on a preview screen is a wall of text nobody reads and everybody scrolls past.
 * {@code WorkoutSession} shows each one at the point it applies.
 */
export default function WorkoutDetail({ session, onBack, onStart }) {
  const { t } = useLanguage()

  const scheme = (exercise) =>
    t('workout.scheme', exercise.sets, exercise.reps, t(`workout.units.${exercise.unit}`))

  return (
    <div className="pt-2">
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={onBack}
          aria-label={t('workout.back')}
          className="h-9 w-9 shrink-0 rounded-xl border border-slate-200 text-slate-600 dark:border-slate-700 dark:text-slate-300"
        >
          ←
        </button>
        <p className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          {t('workout.todaysWorkout')}
        </p>
      </div>

      <div className="pt-4">
        <h2 className="text-3xl font-black tracking-tight text-slate-900 dark:text-slate-100">
          {session.title}
        </h2>
        <div className="mt-2.5 flex flex-wrap gap-1.5">
          <span className="rounded-full bg-green-50 px-2.5 py-1.5 text-xs font-extrabold text-grade-aplus dark:bg-green-950/40 dark:text-green-400">
            {t('workout.minutesChip', session.minutes)}
          </span>
          <span className="rounded-full bg-slate-100 px-2.5 py-1.5 text-xs font-extrabold text-slate-600 dark:bg-slate-700 dark:text-slate-200">
            {t(`workout.options.${session.level}`)}
          </span>
        </div>
        <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">
          {t('workout.detailMeta', session.targetSummary, session.exercises.length, session.totalSets)}
        </p>
      </div>

      <p className="mb-2.5 mt-5 px-0.5 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('workout.exercisesHeading')}
      </p>
      <ul className="space-y-2">
        {session.exercises.map((exercise) => (
          <li
            key={exercise.position}
            className="flex items-center gap-3 rounded-2xl border border-slate-200 bg-white p-3.5 shadow-sm dark:border-slate-700 dark:bg-slate-800"
          >
            <span
              aria-hidden="true"
              className="shrink-0 font-mono text-xs font-extrabold text-slate-400 dark:text-slate-500"
            >
              {String(exercise.position + 1).padStart(2, '0')}
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-bold text-slate-900 dark:text-slate-100">{exercise.name}</p>
              <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">{exercise.target}</p>
            </div>
            <span className="shrink-0 text-sm font-extrabold text-slate-600 dark:text-slate-300">
              {scheme(exercise)}
            </span>
          </li>
        ))}
      </ul>

      <button
        type="button"
        onClick={onStart}
        className="mt-5 min-h-[3.375rem] w-full rounded-2xl bg-grade-aplus py-4 text-base font-extrabold text-white shadow-sm"
      >
        {session.status === 'in_progress' ? t('workout.resumeWorkout') : t('workout.startWorkout')}
      </button>
    </div>
  )
}
