import { useState } from 'react'
import GradeReveal from './GradeReveal.jsx'
import MacroDonut from './MacroDonut.jsx'
import CalorieBar from './CalorieBar.jsx'
import FoodCard from './FoodCard.jsx'
import ScoringRubric from './ScoringRubric.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'

export default function ResultsScreen({
  result,
  dailyBudget,
  goal,
  onSnapAnother,
  actionLabel,
  onExportPdf,
  banner,
}) {
  const { t } = useLanguage()
  const { foods, totals, score, grade, highlights, concerns, suggestions, encouragement, source, scoreBreakdown } = result

  return (
    <div className="space-y-5">
      {banner && (
        <div className="rounded-xl bg-amber-50 px-4 py-3 text-center text-sm text-amber-700 dark:bg-amber-900/20 dark:text-amber-400">
          {banner}
        </div>
      )}
      {source === 'barcode' && (
        <div className="inline-flex items-center gap-1.5 rounded-full border border-green-200 bg-green-50 px-3 py-1 text-xs font-bold text-green-800 dark:border-green-900/40 dark:bg-green-900/10 dark:text-green-400">
          🔖 {t('results.verifiedFromBarcode')}
        </div>
      )}
      <GradeReveal score={score} grade={grade} encouragement={encouragement} />

      <ScoringRubric breakdown={scoreBreakdown} />

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <h2 className="mb-3 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">{t('results.totals')}</h2>
        <CalorieBar calories={totals.calories} dailyBudget={dailyBudget} />
        <div className="mt-4">
          <MacroDonut totals={totals} goal={goal} />
        </div>
      </section>

      <section className="space-y-2">
        <h2 className="px-1 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          {t('results.whatsOnPlate')}
        </h2>
        {foods.map((food, i) => (
          <FoodCard key={`${food.name}-${i}`} food={food} />
        ))}
      </section>

      <div className="grid grid-cols-1 gap-3">
        <FeedbackList title={t('results.highlights')} icon="✅" items={highlights} tone="text-green-700 dark:text-green-400" />
        <FeedbackList title={t('results.concerns')} icon="⚠️" items={concerns} tone="text-amber-700 dark:text-amber-400" />
        <FeedbackList title={t('results.nextTime')} icon="💡" items={suggestions} tone="text-sky-700 dark:text-sky-400" />
      </div>

      {onExportPdf && <ExportPdfButton onExportPdf={onExportPdf} />}

      <button
        onClick={onSnapAnother}
        className="w-full rounded-2xl bg-grade-aplus py-3.5 text-sm font-bold text-white shadow-md active:scale-[0.98]"
      >
        {actionLabel || t('results.snapAnother')}
      </button>
    </div>
  )
}

function ExportPdfButton({ onExportPdf }) {
  const { t } = useLanguage()
  const [state, setState] = useState('idle') // idle | exporting | error

  const handleClick = async () => {
    setState('exporting')
    try {
      await onExportPdf()
      setState('idle')
    } catch {
      setState('error')
    }
  }

  return (
    <div>
      <button
        onClick={handleClick}
        disabled={state === 'exporting'}
        className="w-full rounded-2xl border border-slate-300 py-3 text-sm font-semibold text-slate-700 disabled:opacity-60 dark:border-slate-600 dark:text-slate-300"
      >
        {state === 'exporting' ? t('results.preparingPdf') : t('results.exportPdf')}
      </button>
      {state === 'error' && (
        <p className="mt-1.5 text-center text-xs text-red-500 dark:text-red-400">{t('results.exportError')}</p>
      )}
    </div>
  )
}

function FeedbackList({ title, icon, items, tone }) {
  if (!items?.length) return null
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <h3 className={`mb-2 flex items-center gap-1.5 text-sm font-bold ${tone}`}>
        <span>{icon}</span> {title}
      </h3>
      <ul className="space-y-1.5">
        {items.map((item, i) => (
          <li key={i} className="flex gap-2 text-sm text-slate-600 dark:text-slate-300">
            <span className="text-slate-600 dark:text-slate-300">•</span>
            {item}
          </li>
        ))}
      </ul>
    </section>
  )
}
