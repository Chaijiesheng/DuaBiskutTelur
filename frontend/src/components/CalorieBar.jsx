import { useLanguage } from '../i18n/LanguageContext.jsx'
import { calorieRange } from '../confidence.js'

/**
 * Meal calories vs the (editable) daily budget.
 *
 * When the model bracketed its portion estimate, the band is printed under the
 * point figure. Portion estimation is the largest error source in the whole
 * pipeline, and "700 kcal" set in bold was the app's most confident-looking
 * claim about its least certain number.
 */
export default function CalorieBar({ calories, dailyBudget, totals }) {
  const { t } = useLanguage()
  const range = calorieRange(totals)
  const fraction = Math.min(1, calories / dailyBudget)
  const percent = Math.round((calories / dailyBudget) * 100)
  const barColor = percent > 50 ? '#ef4444' : percent > 35 ? '#f59e0b' : '#22c55e'

  return (
    <div>
      <div className="mb-1 flex items-baseline justify-between text-sm">
        <span className="font-semibold text-slate-900 dark:text-slate-100">{Math.round(calories)} kcal</span>
        <span className="text-xs text-slate-500 dark:text-slate-400">{t('calorieBar.ofYourDay', percent, dailyBudget)}</span>
      </div>
      {range && (
        <p className="mb-1.5 text-xs text-slate-500 dark:text-slate-400">
          {t('calorieBar.range', range.low, range.high)}
        </p>
      )}
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
