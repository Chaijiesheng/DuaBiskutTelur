import { useLanguage } from '../i18n/LanguageContext.jsx'

/** Meal calories vs the (editable) daily budget. */
export default function CalorieBar({ calories, dailyBudget }) {
  const { t } = useLanguage()
  const fraction = Math.min(1, calories / dailyBudget)
  const percent = Math.round((calories / dailyBudget) * 100)
  const barColor = percent > 50 ? '#ef4444' : percent > 35 ? '#f59e0b' : '#22c55e'

  return (
    <div>
      <div className="mb-1 flex items-baseline justify-between text-sm">
        <span className="font-semibold text-slate-900 dark:text-slate-100">{Math.round(calories)} kcal</span>
        <span className="text-xs text-slate-500 dark:text-slate-400">{t('calorieBar.ofYourDay', percent, dailyBudget)}</span>
      </div>
      <div className="relative h-3 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700">
        <div
          className="h-full rounded-full transition-all duration-700"
          style={{ width: `${fraction * 100}%`, background: barColor }}
        />
        {/* Non-color cue for where the amber/red zones start (see A7) — a
            hue-only bar is unreadable to color-blind users. */}
        <div
          className="absolute inset-y-0 w-px bg-slate-400/70 dark:bg-slate-400/50"
          style={{ left: '35%' }}
          aria-hidden="true"
        />
        <div
          className="absolute inset-y-0 w-px bg-slate-400/70 dark:bg-slate-400/50"
          style={{ left: '50%' }}
          aria-hidden="true"
        />
      </div>
    </div>
  )
}
