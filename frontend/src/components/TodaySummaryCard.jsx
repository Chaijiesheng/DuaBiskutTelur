import { useMemo } from 'react'
import { estimateProteinTarget } from '../calorieCalculator.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/** Sums an in-memory visitor history down to today's (local calendar day) totals. */
function computeVisitorToday(entries) {
  const now = new Date()
  const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const endOfDay = startOfDay + 86400000
  let totalCalories = 0
  let totalProtein = 0
  for (const e of entries) {
    const t = new Date(e.createdAt).getTime()
    if (t < startOfDay || t >= endOfDay) continue
    totalCalories += e.result?.totals?.calories ?? e.calories ?? 0
    totalProtein += e.result?.totals?.protein ?? 0
  }
  return { totalCalories, totalProtein }
}

/**
 * Persistent "how much have I got left today" strip, shown at the top of the
 * Snap tab's home screen. Previously the only place to see today's running
 * total was a one-time popup right after login — gone the moment it's
 * dismissed, with nowhere else to check it for the rest of the session.
 */
export default function TodaySummaryCard({ isVisitor, dashboard, visitorEntries, dailyBudget, profile }) {
  const { t } = useLanguage()

  const stats = useMemo(() => {
    if (isVisitor) {
      const { totalCalories, totalProtein } = computeVisitorToday(visitorEntries)
      return {
        totalCalories,
        totalProtein,
        calorieTarget: dailyBudget,
        proteinTarget: estimateProteinTarget(profile, dailyBudget),
      }
    }
    if (!dashboard) return null
    return {
      totalCalories: dashboard.totalCalories,
      totalProtein: dashboard.totalProtein,
      calorieTarget: dashboard.calorieTarget,
      proteinTarget: dashboard.proteinTarget,
    }
  }, [isVisitor, visitorEntries, dailyBudget, profile, dashboard])

  if (!stats) return null

  const { totalCalories, totalProtein, calorieTarget, proteinTarget } = stats
  const percent = calorieTarget > 0 ? Math.round((totalCalories / calorieTarget) * 100) : 0
  // Day-scoped thresholds (comfortably under / approaching / over budget) —
  // deliberately different from CalorieBar's, which grades a single meal
  // against the day's budget, not the day's running total against itself.
  const barColor = percent > 100 ? '#ef4444' : percent > 85 ? '#f59e0b' : '#22c55e'

  return (
    <section className="mb-4 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <h2 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('dashboard.todaySoFar')}
      </h2>
      <div className="mt-2 flex items-baseline justify-between gap-2">
        <p className="text-sm">
          <span className="font-semibold text-slate-900 dark:text-slate-100">{Math.round(totalCalories)}</span>
          <span className="text-slate-500 dark:text-slate-400"> / {calorieTarget} kcal</span>
        </p>
        <p className="shrink-0 text-xs text-slate-500 dark:text-slate-400">
          {Math.round(totalProtein)}g / {proteinTarget}g {t('food.protein')}
        </p>
      </div>
      <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700">
        <div
          className="h-full rounded-full transition-all duration-500"
          style={{ width: `${Math.min(100, percent)}%`, background: barColor }}
        />
      </div>
    </section>
  )
}
