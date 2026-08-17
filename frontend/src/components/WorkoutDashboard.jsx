import { useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { fullWeekdayName } from '../workout/weekday.js'

/**
 * The Workout tab's landing screen — layout variant A, "action first".
 *
 * <p>The plan card is the first thing on the screen and the start button is
 * inside it. The alternative the prototype offered opened with a combined
 * nutrition-and-workout progress panel, which reads well as a dashboard and
 * answers the wrong question: somebody opening this tab is deciding whether to
 * train in the next thirty seconds, not reviewing their week. Everything that
 * summarises rather than prompts sits below the fold.
 *
 * <p>The coach note comes directly under the card, not floating elsewhere,
 * because its whole job is to justify that card.
 */
export default function WorkoutDashboard({
  data, online, onStart, onOpenDetail, onSkip, onUnskip, pendingSets,
}) {
  const { t, lang } = useLanguage()
  const [whyOpen, setWhyOpen] = useState(false)

  const { session, coach, coachSource, week, stats } = data
  const isSkipped = session.status === 'skipped'
  const started = session.status === 'in_progress'
  const doneThisWeek = week.filter((d) => d.state === 'done').length
  const plannedThisWeek = week.filter((d) => d.state !== 'rest').length

  return (
    <div className="pt-2">
      {!online && (
        <section className="mb-3 flex items-start gap-2.5 rounded-2xl border border-amber-300 bg-amber-50 px-3 py-2.5 dark:border-amber-700 dark:bg-amber-950/40">
          <span aria-hidden="true" className="text-base leading-tight">📡</span>
          <div>
            <p className="text-xs font-extrabold text-amber-800 dark:text-amber-300">
              {t('workout.offlineTitle')}
            </p>
            <p className="mt-0.5 text-xs leading-relaxed text-amber-800 dark:text-amber-300">
              {t('workout.offlineBody')}
            </p>
          </div>
        </section>
      )}

      <h2 className="mb-3 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('workout.headingToday')}
      </h2>

      {/* ---- the action, first ---- */}
      {isSkipped ? (
        <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <div className="flex items-center gap-2.5">
            <span
              aria-hidden="true"
              className="flex h-9 w-9 items-center justify-center rounded-full bg-slate-100 text-base dark:bg-slate-700"
            >
              ⏭️
            </span>
            <div>
              <h3 className="text-base font-extrabold text-slate-900 dark:text-slate-100">
                {t('workout.skippedTitle')}
              </h3>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                {session.title} · {t('workout.minutesChip', session.minutes)}
              </p>
            </div>
          </div>
          <p className="mt-3 text-sm leading-relaxed text-slate-600 dark:text-slate-300">
            {t('workout.skippedBody')}
          </p>
          <button
            type="button"
            onClick={onUnskip}
            className="mt-3 min-h-[2.75rem] w-full rounded-xl border border-slate-200 py-3 text-sm font-bold text-slate-600 dark:border-slate-700 dark:text-slate-300"
          >
            {t('workout.actuallyGo')}
          </button>
        </section>
      ) : (
        <section
          data-testid="workout-plan-card"
          className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800"
        >
          {/* Stacked, not a title-and-chip flex row. The chip used to sit top
              right with `shrink-0`, which meant a long muscle list refused to
              shrink, forced the card wider than the phone, collapsed the title
              to one word per line, and pushed the fixed bottom navigation
              off-screen. A column cannot do that whatever the server sends. */}
          <div>
            <p className="text-xs font-bold uppercase tracking-wide text-grade-aplus dark:text-green-400">
              {t('workout.kickerToday')}
            </p>
            <h3 className="mt-1 text-2xl font-black tracking-tight text-slate-900 dark:text-slate-100">
              {session.title}
            </h3>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              {t(
                'workout.sessionMeta',
                session.minutes,
                t(`workout.options.${session.level}`),
                session.exercises.length,
              )}
            </p>
            {session.targetSummary && (
              <p className="mt-2">
                <span className="inline-block max-w-full break-words rounded-full bg-green-50 px-2.5 py-1.5 text-[0.7rem] font-extrabold text-grade-aplus dark:bg-green-950/40 dark:text-green-400">
                  {session.targetSummary}
                </span>
              </p>
            )}
          </div>

          <button
            type="button"
            onClick={onStart}
            className="mt-4 min-h-[3.25rem] w-full rounded-2xl bg-grade-aplus py-4 text-base font-extrabold text-white shadow-sm"
          >
            {started ? t('workout.resumeWorkout') : t('workout.startWorkout')}
          </button>
          <button
            type="button"
            onClick={onOpenDetail}
            className="w-full pt-2.5 text-xs font-semibold text-slate-500 underline-offset-2 hover:underline dark:text-slate-400"
          >
            {t('workout.todaysWorkout')}
          </button>
          {!started && (
            <button
              type="button"
              onClick={onSkip}
              className="w-full py-2 text-xs font-semibold text-slate-500 dark:text-slate-400"
            >
              {t('workout.notToday')}
            </button>
          )}
        </section>
      )}

      {/* ---- why, directly under the card it justifies ---- */}
      {coach?.summary && (
        <section className="mt-3 rounded-2xl bg-green-50 px-4 py-3.5 dark:bg-green-950/40">
          <div className="flex items-center gap-2">
            <span aria-hidden="true" className="text-sm">💬</span>
            <p className="text-[0.7rem] font-extrabold uppercase tracking-wider text-grade-aplus dark:text-green-400">
              {coachSource === 'rules' ? t('workout.standardTitle') : t('workout.whyTitle')}
            </p>
          </div>
          <p className="mt-2 text-sm leading-relaxed text-slate-700 dark:text-slate-200">
            {coach.summary}
          </p>
          {coach.factors?.length > 0 && (
            <>
              <button
                type="button"
                aria-expanded={whyOpen}
                onClick={() => setWhyOpen((open) => !open)}
                className="mt-2 text-xs font-bold text-grade-aplus dark:text-green-400"
              >
                {whyOpen ? t('workout.whyClose') : t('workout.whyOpen')}
              </button>
              {whyOpen && (
                <ul className="mt-2.5 space-y-1.5 border-t border-green-200 pt-2.5 dark:border-green-900">
                  {coach.factors.map((factor) => (
                    <li
                      key={factor}
                      className="flex gap-2 text-xs leading-relaxed text-slate-700 dark:text-slate-200"
                    >
                      <span aria-hidden="true" className="font-extrabold text-grade-aplus dark:text-green-400">·</span>
                      <span>{factor}</span>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </section>
      )}

      {pendingSets > 0 && (
        <p className="mt-3 px-1 text-xs text-slate-500 dark:text-slate-400">
          {t('workout.unsynced', pendingSets)}
        </p>
      )}

      {/* ---- everything that summarises rather than prompts ---- */}
      <section
        data-testid="workout-week-strip"
        className="mt-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800"
      >
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {t('workout.thisWeek')}
          </p>
          <span className="text-xs font-bold text-slate-600 dark:text-slate-300">
            {t('workout.weekDone', doneThisWeek, plannedThisWeek)}
          </span>
        </div>
        <ul className="mt-3 flex gap-1.5">
          {week.map((day) => (
            <li key={day.date} className="flex flex-1 flex-col items-center gap-1.5">
              <span
                aria-hidden="true"
                className={`flex aspect-square w-full max-w-[2.25rem] items-center justify-center rounded-xl border-2 text-sm font-extrabold ${dayClass(day.state)}`}
              >
                {DAY_MARK[day.state]}
              </span>
              <span className="text-[0.65rem] font-bold text-slate-500 dark:text-slate-400">
                {day.label}
              </span>
              {/* The circles are decoration; this is what a screen reader gets.
                  The full weekday name rather than the narrow letter above it:
                  "T" is both Tuesday and Thursday. */}
              <span className="sr-only">
                {fullWeekdayName(day.date, lang)} — {t(`workout.dayStates.${day.state}`)}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section className="mt-3 grid grid-cols-3 gap-2">
        <StatTile label={t('workout.statWeight')} value={stats.weightKg ?? '—'} unit={stats.weightKg ? 'kg' : ''} />
        <StatTile label={t('workout.statWorkouts')} value={stats.workoutsThisMonth} unit="" />
        <StatTile
          label={t('workout.statStreak')}
          value={stats.streakDays}
          unit={t('workout.statStreakUnit')}
        />
      </section>

      <p className="mt-4 px-1 text-center text-xs leading-relaxed text-slate-500 dark:text-slate-400">
        {t('workout.historyNote')}
      </p>
    </div>
  )
}

const DAY_MARK = { done: '✓', today: '●', planned: '○', rest: '–' }

function dayClass(state) {
  switch (state) {
    case 'done':
      return 'border-grade-aplus bg-green-50 text-grade-aplus dark:border-green-400 dark:bg-green-950/40 dark:text-green-400'
    case 'today':
      return 'border-grade-aplus bg-white text-grade-aplus dark:border-green-400 dark:bg-slate-800 dark:text-green-400'
    case 'planned':
      return 'border-slate-200 bg-white text-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-500'
    default:
      return 'border-transparent text-slate-300 dark:text-slate-600'
  }
}

function StatTile({ label, value, unit }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-3 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <p className="text-[0.65rem] font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {label}
      </p>
      <p className="mt-1 text-lg font-black tracking-tight text-slate-900 dark:text-slate-100">
        {value}
        {unit && <span className="ml-0.5 text-[0.65rem] font-bold text-slate-500 dark:text-slate-400">{unit}</span>}
      </p>
    </div>
  )
}
