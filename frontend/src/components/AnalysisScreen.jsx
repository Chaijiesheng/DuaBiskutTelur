import { useEffect, useMemo, useState } from 'react'
import { fetchHistory } from '../api.js'
import SignInBanner from './SignInBanner.jsx'
import WeeklyCaloriesChart, { getWeeklyDays } from './WeeklyCaloriesChart.jsx'
import WeightTrendCard from './WeightTrendCard.jsx'
import WaterTrackerCard from './WaterTrackerCard.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * Weekly analytics dashboard. Reuses the same 7-day bucketing as the History
 * tab's chart, just surfaced as headline stat cards too. Monthly reports and
 * achievements are a follow-up. Weight trend needs persistent history across
 * sessions, so it's signed-in only — visitors just don't see that card.
 */
export default function AnalysisScreen({ onAuthExpired, isVisitor, visitorEntries, dailyBudget, goal }) {
  const { t } = useLanguage()
  const [fetched, setFetched] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (isVisitor) return
    fetchHistory()
      .then(setFetched)
      .catch((e) => {
        if (e.code === 'UNAUTHENTICATED') onAuthExpired?.()
        else setError(true)
      })
  }, [isVisitor, onAuthExpired])

  const entries = isVisitor ? visitorEntries : fetched

  const stats = useMemo(() => {
    if (!entries) return null
    const days = getWeeklyDays(entries)
    const withMeals = days.filter((d) => d.mealCount > 0)
    const totalWeek = days.reduce((sum, d) => sum + d.totalCalories, 0)
    const highest = withMeals.length
      ? withMeals.reduce((a, b) => (b.totalCalories > a.totalCalories ? b : a))
      : null
    const lowest = withMeals.length
      ? withMeals.reduce((a, b) => (b.totalCalories < a.totalCalories ? b : a))
      : null
    return {
      totalWeek,
      // Divided by days actually logged, not by 7 — otherwise a brand-new
      // user who logged 1,800 kcal today sees a "257 kcal average" that
      // reads as broken.
      avgDaily: withMeals.length > 0 ? Math.round(totalWeek / withMeals.length) : 0,
      highest,
      lowest,
      totalMeals: days.reduce((sum, d) => sum + d.mealCount, 0),
    }
  }, [entries])

  if (!isVisitor && error) {
    return <p className="pt-16 text-center text-sm text-slate-500 dark:text-slate-400">{t('analysis.couldntLoad')}</p>
  }
  if (!entries) {
    return <p className="pt-16 text-center text-sm text-slate-500 dark:text-slate-400">{t('analysis.loading')}</p>
  }
  if (entries.length === 0) {
    return (
      <div className="space-y-4 pt-2">
        <div className="pt-10 text-center">
          <span className="text-5xl">📊</span>
          <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">
            {isVisitor ? t('analysis.emptyVisitor') : t('analysis.emptyUser')}
          </p>
          {isVisitor && <SignInBanner />}
        </div>
        <WaterTrackerCard isVisitor={isVisitor} />
      </div>
    )
  }

  return (
    <div className="space-y-4 pt-2">
      {isVisitor && <SignInBanner />}
      <h2 className="px-1 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('analysis.weeklyAnalytics')}
      </h2>
      <div className="grid grid-cols-2 gap-3">
        <StatCard icon="🔥" label={t('analysis.totalThisWeek')} value={Math.round(stats.totalWeek)} unit="kcal" />
        <StatCard icon="📅" label={t('analysis.avgDaily')} value={stats.avgDaily} unit="kcal" />
        <StatCard
          icon="⬆️"
          label={t('analysis.highestDay')}
          value={stats.highest ? Math.round(stats.highest.totalCalories) : '—'}
          unit={stats.highest ? 'kcal' : ''}
          sub={stats.highest?.dateLabel}
          tone="text-red-600 dark:text-red-400"
        />
        <StatCard
          icon="⬇️"
          label={t('analysis.lowestDay')}
          value={stats.lowest ? Math.round(stats.lowest.totalCalories) : '—'}
          unit={stats.lowest ? 'kcal' : ''}
          sub={stats.lowest?.dateLabel}
          tone="text-green-600 dark:text-green-400"
        />
      </div>

      <WeeklyCaloriesChart entries={entries} dailyBudget={dailyBudget} title={t('analysis.dailyTrend')} />

      {!isVisitor && <WeightTrendCard goal={goal} />}

      <WaterTrackerCard isVisitor={isVisitor} />

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <h2 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">{t('analysis.mealsLogged')}</h2>
        <p className="mt-1 text-2xl font-black text-slate-900 dark:text-slate-100">
          {stats.totalMeals} <span className="text-xs font-semibold text-slate-500 dark:text-slate-400">{t('analysis.thisWeek')}</span>
        </p>
      </section>

      <p className="px-1 text-center text-[11px] text-slate-600 dark:text-slate-300">{t('analysis.comingSoon')}</p>
    </div>
  )
}

function StatCard({ icon, label, value, unit, sub, tone }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center justify-between">
        <p className="text-[11px] font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{label}</p>
        <span className="text-base">{icon}</span>
      </div>
      <p className={`mt-1 text-xl font-black ${tone || 'text-slate-900 dark:text-slate-100'}`}>
        {value}
        {unit && <span className="ml-1 text-xs font-semibold text-slate-500 dark:text-slate-400">{unit}</span>}
      </p>
      {sub && <p className="text-[11px] text-slate-500 dark:text-slate-400">{sub}</p>}
    </div>
  )
}
