import { useEffect, useState } from 'react'
import { fetchWorkoutHistory } from '../api.js'
import SignInBanner from './SignInBanner.jsx'
import { MenuListSkeleton } from './Skeleton.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { fullWeekdayName } from '../workout/weekday.js'

/**
 * Past sessions, as a third tab inside the existing History screen.
 *
 * <p>A third tab rather than a Workouts page of its own: "what did I do" is one
 * question, and answering it in two places means checking two places. The meal
 * and menu tabs beside this one are the same question about food.
 *
 * <p>Reads {@code /api/workout/history}, never {@code /api/workout/today} —
 * that one plans a session when there isn't one, and looking at last week must
 * not create this morning's workout on the way past.
 */
export default function WorkoutHistory({ isVisitor }) {
  const { t } = useLanguage()
  const [data, setData] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (isVisitor) return undefined
    let live = true
    fetchWorkoutHistory()
      .then((next) => live && setData(next))
      .catch(() => live && setError(true))
    return () => {
      live = false
    }
  }, [isVisitor])

  if (isVisitor) {
    return (
      <div className="pt-10 text-center">
        <span aria-hidden="true" className="text-5xl">🏋️</span>
        <p className="mx-auto mt-3 max-w-[17rem] text-sm leading-relaxed text-slate-500 dark:text-slate-400">
          {t('workout.visitorBody')}
        </p>
        <SignInBanner />
      </div>
    )
  }

  if (error) {
    return (
      <p className="pt-16 text-center text-sm text-slate-500 dark:text-slate-400">
        {t('workout.loadError')}
      </p>
    )
  }

  if (!data) {
    return <MenuListSkeleton label={t('workout.historyTab')} />
  }

  return (
    <div className="space-y-3">
      <MinutesChart week={data.week} weekMinutes={data.weekMinutes} />

      {data.entries.length === 0 ? (
        <div className="pt-8 text-center">
          <span aria-hidden="true" className="text-5xl">🏋️</span>
          <p className="mt-3 text-sm font-semibold text-slate-600 dark:text-slate-300">
            {t('workout.historyEmpty')}
          </p>
          <p className="mx-auto mt-1 max-w-[17rem] text-xs leading-relaxed text-slate-500 dark:text-slate-400">
            {t('workout.historyEmptyHint')}
          </p>
        </div>
      ) : (
        <ul className="space-y-2">
          {data.entries.map((entry) => (
            <HistoryRow key={entry.id} entry={entry} />
          ))}
        </ul>
      )}
    </div>
  )
}

/**
 * Minutes trained, Monday to Sunday.
 *
 * <p>Minutes rather than sessions, because a 15-minute mobility day and an hour
 * of legs are not the same amount of training, and a bar chart counting both as
 * "1" would say they were.
 */
function MinutesChart({ week, weekMinutes }) {
  const { t, lang } = useLanguage()
  const peak = Math.max(...week.map((d) => d.minutes), 1)

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          {t('workout.minutesTrained')}
        </h3>
        <span className="text-xs font-bold text-slate-600 dark:text-slate-300">
          {t('workout.minutesThisWeek', weekMinutes)}
        </span>
      </div>
      <ul className="mt-3 flex items-stretch gap-1">
        {week.map((day) => (
          <li key={day.date} className="flex flex-1 flex-col items-center gap-1.5">
            <span aria-hidden="true" className="h-3 text-[0.6rem] font-extrabold leading-3 text-grade-aplus dark:text-green-400">
              {day.minutes > 0 ? '✓' : ''}
            </span>
            <span aria-hidden="true" className="flex h-14 w-full items-end">
              <span
                className={`w-full rounded-t ${
                  day.minutes > 0 ? 'bg-green-500' : 'bg-slate-200 dark:bg-slate-700'
                }`}
                style={{ height: day.minutes > 0 ? `${Math.max(8, (day.minutes / peak) * 100)}%` : '4px' }}
              />
            </span>
            <span className="text-[0.6rem] text-slate-500 dark:text-slate-400">{day.label}</span>
            {/* The bars are decoration; this is what a screen reader gets. The
                full weekday name, not the narrow letter shown above: "T" is
                both Tuesday and Thursday, so two silent columns a week would
                be indistinguishable. */}
            <span className="sr-only">
              {fullWeekdayName(day.date, lang)} — {t('workout.minutesChip', day.minutes)}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * The status chip.
 *
 * <p>"Partial" exists because a session abandoned after two sets is neither
 * done nor skipped, and calling it either would be the record lying about what
 * happened.
 */
function chipFor(entry) {
  if (entry.status === 'completed') return { key: 'workout.chipDone', tone: 'done' }
  if (entry.status === 'skipped') return { key: 'workout.chipSkipped', tone: 'muted' }
  if (entry.completedSets > 0) return { key: 'workout.chipPartial', tone: 'partial' }
  return { key: 'workout.chipPlanned', tone: 'muted' }
}

const CHIP_CLASS = {
  done: 'bg-green-50 text-grade-aplus dark:bg-green-950/40 dark:text-green-400',
  partial: 'bg-amber-50 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300',
  muted: 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400',
}

const ICON = { completed: '🏋️', skipped: '⏭️' }

function HistoryRow({ entry }) {
  const { t, lang } = useLanguage()
  const chip = chipFor(entry)
  const dateLabel = new Date(`${entry.date}T00:00:00`).toLocaleDateString(
    { en: 'en-US', zh: 'zh-CN', ms: 'ms-MY' }[lang] ?? 'en-US',
    { month: 'short', day: 'numeric' },
  )

  return (
    <li className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white p-3 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <span
        aria-hidden="true"
        className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-lg dark:bg-slate-700"
      >
        {ICON[entry.status] ?? '🏋️'}
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-bold text-slate-900 dark:text-slate-100">{entry.title}</p>
        <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
          {dateLabel} · {t('workout.entryMeta', entry.minutes, t(`workout.options.${entry.level}`))}
        </p>
        {entry.totalSets > 0 && entry.status !== 'skipped' && (
          <p className="mt-0.5 text-xs text-slate-400 dark:text-slate-500">
            {t('workout.entrySets', entry.completedSets, entry.totalSets)}
          </p>
        )}
      </div>
      <span className={`shrink-0 rounded-lg px-2 py-1 text-[0.65rem] font-extrabold ${CHIP_CLASS[chip.tone]}`}>
        {t(chip.key)}
      </span>
    </li>
  )
}
