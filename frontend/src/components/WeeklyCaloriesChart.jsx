import { useMemo, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { useTheme } from '../theme/ThemeContext.jsx'

const CHART_HEIGHT = 40
const BAR_WIDTH = 10
const BAR_STEP = 14
const LOCALE_TAG = { en: 'en-US', zh: 'zh-CN', ms: 'ms-MY' }

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

/** Interactive 7-day calories bar chart — tap a bar to see that day's total. */
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
      <div className="flex items-center justify-between">
        <h2 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">{title || t('weeklyChart.title')}</h2>
        <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">
          {activeDay
            ? `${activeDay.dateLabel} · ${Math.round(activeDay.totalCalories)} kcal`
            : t('analysis.weekAvg', weekAvg)}
        </span>
      </div>
      <svg viewBox={`0 0 100 ${CHART_HEIGHT + 14}`} className="mt-2 h-24 w-full">
        <line
          x1="2"
          y1={CHART_HEIGHT}
          x2="98"
          y2={CHART_HEIGHT}
          stroke={dark ? '#334155' : '#e2e8f0'}
          strokeWidth="0.5"
        />
        {days.map((d, i) => {
          const x = 4 + i * BAR_STEP
          const barHeight = maxValue > 0 ? (d.totalCalories / maxValue) * CHART_HEIGHT : 0
          const isOver = d.totalCalories > budget
          const isSelected = selected === i
          const dimmed = selected != null && !isSelected
          const barColor = d.totalCalories === 0 ? (dark ? '#334155' : '#e2e8f0') : isOver ? '#ef4444' : '#22c55e'

          const toggle = () => setSelected(isSelected ? null : i)

          return (
            <g
              key={i}
              onClick={toggle}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  toggle()
                }
              }}
              role="button"
              tabIndex={0}
              aria-label={`${d.dateLabel}: ${Math.round(d.totalCalories)} kcal`}
              aria-pressed={isSelected}
              className="cursor-pointer focus-visible:opacity-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-grade-aplus"
              opacity={dimmed ? 0.35 : 1}
            >
              {/* wider transparent hit target for comfortable tapping on mobile */}
              <rect x={x - 2} y="0" width={BAR_WIDTH + 4} height={CHART_HEIGHT} fill="transparent" />
              <rect
                x={x}
                y={CHART_HEIGHT - Math.max(barHeight, 1)}
                width={BAR_WIDTH}
                height={Math.max(barHeight, 1)}
                rx="1.5"
                fill={barColor}
                stroke={isSelected ? '#15803d' : 'none'}
                strokeWidth={isSelected ? 1 : 0}
              />
              {isOver && (
                // Shape-based cue for "over budget" that doesn't depend on
                // distinguishing red from green (see A7).
                <text
                  x={x + BAR_WIDTH / 2}
                  y={Math.max(4, CHART_HEIGHT - Math.max(barHeight, 1) - 1.5)}
                  textAnchor="middle"
                  fontSize="5"
                  fontWeight="bold"
                  fill={dark ? '#fca5a5' : '#b91c1c'}
                >
                  !
                </text>
              )}
              <text
                x={x + BAR_WIDTH / 2}
                y={CHART_HEIGHT + 8}
                textAnchor="middle"
                fontSize="5"
                fontWeight={isSelected ? 'bold' : 'normal'}
                fill={isSelected ? (dark ? '#4ade80' : '#15803d') : (dark ? '#94a3b8' : '#64748b')}
              >
                {d.label}
              </text>
            </g>
          )
        })}
      </svg>
    </section>
  )
}
