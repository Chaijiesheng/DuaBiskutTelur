import { useLanguage } from '../i18n/LanguageContext.jsx'
import Dialog from './Dialog.jsx'

/**
 * Today-at-a-glance summary, shown as a one-time pop-up right after login
 * (see App.jsx). The main Snap/History pages stay clean — this is only an
 * overlay for a quick daily insight, dismissed via its own close button.
 */
export default function DashboardSummary({ data, onClose }) {
  const { t } = useLanguage()
  if (!data || !data.hasData) return null

  const {
    totalCalories,
    calorieTarget,
    totalProtein,
    proteinTarget,
    mealCount,
    averageGrade,
  } = data

  return (
    <Dialog
      onClose={onClose}
      ariaLabel={t('dashboard.todaySoFar')}
      overlayClassName="fixed inset-0 z-30 flex items-center justify-center bg-black/40 px-6"
      panelClassName="w-full max-w-xs rounded-3xl bg-white p-6 shadow-xl dark:bg-slate-800"
    >
      <h2 className="mb-3 text-center text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('dashboard.todaySoFar')}
      </h2>
      <div className="grid grid-cols-2 gap-3">
        <Metric
          label={t('dashboard.calories')}
          value={`${Math.round(totalCalories)} / ${calorieTarget}`}
          unit="kcal"
        />
        <Metric
          label={t('dashboard.protein')}
          value={`${Math.round(totalProtein)} / ${proteinTarget}`}
          unit="g"
        />
        <Metric label={t('dashboard.mealsLogged')} value={mealCount} />
        <Metric label={t('dashboard.avgGrade')} value={averageGrade} />
      </div>
      <button
        onClick={onClose}
        className="mt-5 w-full rounded-2xl bg-grade-aplus py-3 text-sm font-bold text-white shadow-md active:scale-[0.98]"
      >
        {t('dashboard.gotIt')}
      </button>
    </Dialog>
  )
}

function Metric({ label, value, unit }) {
  return (
    <div className="rounded-xl bg-slate-50 px-3 py-2.5 dark:bg-slate-700">
      <p className="text-[11px] font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{label}</p>
      <p className="mt-0.5 text-base font-black text-slate-900 dark:text-slate-100">
        {value}
        {unit && <span className="ml-1 text-xs font-semibold text-slate-500 dark:text-slate-400">{unit}</span>}
      </p>
    </div>
  )
}
