import { useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'

function ConfidenceDot({ confidence }) {
  const color = confidence >= 0.8 ? 'bg-green-500' : confidence >= 0.5 ? 'bg-amber-400' : 'bg-red-400'
  return (
    <span className="flex items-center gap-1 text-[10px] text-slate-500 dark:text-slate-400">
      <span className={`h-2 w-2 rounded-full ${color}`} />
      {Math.round(confidence * 100)}%
    </span>
  )
}

/** Collapsed: name, portion, kcal, confidence. Tap to expand full macros. */
export default function FoodCard({ food }) {
  const { t } = useLanguage()
  const [open, setOpen] = useState(false)

  return (
    <button
      onClick={() => setOpen((v) => !v)}
      className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-left shadow-sm transition active:scale-[0.99] dark:border-slate-700 dark:bg-slate-800"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-slate-900 dark:text-slate-100">{food.name}</p>
          <p className="text-xs text-slate-500 dark:text-slate-400">{food.estimatedPortion}</p>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-0.5">
          <span className="text-sm font-bold text-slate-900 dark:text-slate-100">{Math.round(food.calories)} kcal</span>
          <ConfidenceDot confidence={food.confidence} />
        </div>
      </div>

      {open && (
        <div className="mt-3 grid grid-cols-3 gap-2 border-t border-slate-100 pt-3 text-center dark:border-slate-700">
          <Macro label={t('food.protein')} value={`${food.protein}g`} />
          <Macro label={t('food.carbs')} value={`${food.carbs}g`} />
          <Macro label={t('food.fat')} value={`${food.fat}g`} />
          <Macro label={t('food.fiber')} value={`${food.fiber}g`} />
          <Macro label={t('food.sugar')} value={`${food.sugar}g`} />
          <Macro label={t('food.sodium')} value={`${Math.round(food.sodium)}mg`} />
          <p className="col-span-3 pt-1 text-[10px] uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {food.source === 'usda' ? t('food.usda') : food.source === 'barcode' ? t('food.barcode') : t('food.aiEstimate')}
            {food.fried ? t('food.deepFried') : ''}
          </p>
        </div>
      )}
    </button>
  )
}

function Macro({ label, value }) {
  return (
    <div>
      <p className="text-xs font-semibold text-slate-800 dark:text-slate-200">{value}</p>
      <p className="text-[10px] text-slate-500 dark:text-slate-400">{label}</p>
    </div>
  )
}
