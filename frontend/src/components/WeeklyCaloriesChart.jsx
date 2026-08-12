import { useMemo, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { useTheme } from '../theme/ThemeContext.jsx'

const LOCALE_TAG = { en: 'en-US', zh: 'zh-CN', ms: 'ms-MY' }
/** A day with no meals still gets a sliver, so the column reads as "logged nothing". */
const MIN_BAR_PCT = 3

/** Buckets meal history entries into the last 7 calendar days (oldest first). */
export function getWeeklyDays(entries, localeTag) {
  const now = Date.now()
  return [...Array(7)].map((_, i) => {
    const dayStart = new Date(now - (6 - i) * 86400000)
    dayStart.setHours(0, 0, 0, 0)
    const dayEnd = dayStart.getTime() + 86400000
    const dayEntries = entries.filter((e) => {
      const t = new Date(e.createdAt).getTime()
      return t >= dayStart.getTime() && t < dayEnd
    })
    return {
      label: dayStart.toLocaleDateString(localeTag, { weekday: 'narrow' }),
      dateLabel: dayStart.toLocaleDateString(localeTag, { day: 'numeric', month: 'short' }),
      totalCalories: dayEntries.reduce((sum, e) => sum + e.calories, 0),
      mealCount: dayEntries.length,
    }
  })
}

/**
 * Interactive 7-day calories bar chart — tap a bar to see that day's total.
 *
 * Laid out with flex rather than an SVG viewBox, which fixed three things at
 * once:
 *
 * - **Width.** The old `viewBox="0 0 100 54"` inside `h-24 w-full` scaled
 *   uniformly under the default `xMidYMid meet`, so the chart drew 178px wide
 *   inside a 415px card — 43% of it — and sat letterboxed in the middle. Flex
 *   columns fill whatever width they are given, at any card size.
 * - **Focus.** Focus lived on a `<g role="button" tabIndex={0}>`, where
 *   `:focus-visible` never matches a pointer press, so the styled green ring
 *   never applied and the browser's own two-tone ring showed instead — drawn
 *   around the group's bounding box, which included the full-height hit target
 *   and the day letter, so it towered over the bar it was meant to mark. These
 *   are real buttons now, so focus-visible behaves and the ring follows the
 *   element.
 * - **Selection.** The selected bar was marked with a #15803d stroke on a
 *   #22c55e fill — dark green on green, effectively invisible. Selection is now
 *   a filled column track, which does not depend on telling two greens apart.
 */
export default function WeeklyCaloriesChart({ entries, dailyBudget, title }) {
  const { t, lang } = useLanguage()
  const { theme } = useTheme()
  const dark = theme === 'dark'
  const localeTag = LOCALE_TAG[lang]
  const [selected, setSelected] = useState(null)
  const budget = dailyBudget || 2000
  const days = useMemo(() => getWeeklyDays(entries, localeTag), [entries, localeTag])

  const maxValue = Math.max(budget, ...days.map((d) => d.totalCalories))
  // Divided by days actually logged, not by 7 — see AnalysisScreen's avgDaily
  // for why dividing by the full week reads as broken for a new user.
  const loggedDayCount = days.filter((d) => d.mealCount > 0).length
  const weekAvg = loggedDayCount > 0
    ? Math.round(days.reduce((sum, d) => sum + d.totalCalories, 0) / loggedDayCount)
    : 0
  const activeDay = selected != null ? days[selected] : null

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          {title || t('weeklyChart.title')}
        </h2>
        <span className="shrink-0 text-xs font-semibold text-slate-600 dark:text-slate-300">
          {activeDay
            ? `${activeDay.dateLabel} · ${Math.round(activeDay.totalCalories)} kcal`
            : t('analysis.weekAvg', weekAvg)}
        </span>
      </div>

      <div className="mt-3 flex items-stretch gap-1">
        {days.map((d, i) => {
          const isSelected = selected === i
          const dimmed = selected != null && !isSelected
          const isOver = d.totalCalories > budget
          const pct = maxValue > 0
            ? Math.max((d.totalCalories / maxValue) * 100, MIN_BAR_PCT)
            : MIN_BAR_PCT
          const barColor = d.totalCalories === 0
            ? (dark ? '#334155' : '#e2e8f0')
            : isOver ? '#ef4444' : '#22c55e'

          return (
            <button
              key={i}
              type="button"
              onClick={() => setSelected(isSelected ? null : i)}
              aria-label={`${d.dateLabel}: ${Math.round(d.totalCalories)} kcal`}
              aria-pressed={isSelected}
              className={`flex flex-1 flex-col items-center gap-1 rounded-lg px-0.5 pb-1 pt-1 transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-grade-aplus dark:focus-visible:ring-green-400 ${
                isSelected ? 'bg-slate-100 dark:bg-slate-700/70' : ''
              } ${dimmed ? 'opacity-40' : ''}`}
            >
              {/* Shape-based cue for "over budget" that doesn't depend on
                  distinguishing red from green (see A7). Reserved even when
                  absent so every column keeps the same baseline. */}
              <span
                aria-hidden="true"
                className="h-3 text-[10px] font-bold leading-3"
                style={{ color: dark ? '#fca5a5' : '#b91c1c' }}
              >
                {isOver ? '!' : ''}
              </span>

              <span className="flex h-14 w-full items-end">
                <span
                  className="w-full rounded-t-[3px]"
                  style={{ height: `${pct}%`, backgroundColor: barColor }}
                />
              </span>

              <span
                className={`text-[10px] leading-none ${
                  isSelected
                    ? 'font-bold text-grade-aplus dark:text-green-400'
                    : 'text-slate-500 dark:text-slate-400'
                }`}
              >
                {d.label}
              </span>
            </button>
          )
        })}
      </div>
    </section>
  )
}
