import { useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { UNSURE } from '../confidence.js'
import { PORTION_STEPS } from '../portionCorrection.js'

function ConfidenceDot({ confidence }) {
  const color = confidence >= 0.8 ? 'bg-green-500' : confidence >= 0.5 ? 'bg-amber-400' : 'bg-red-400'
  return (
    <span className="flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400">
      <span className={`h-2 w-2 rounded-full ${color}`} />
      {Math.round(confidence * 100)}%
    </span>
  )
}

/**
 * A percentage is a number, not a meaning — 55% and 95% used to render with
 * identical weight next to an identical-looking calorie figure. Below the
 * threshold the card says so in words, so the doubt is legible without having
 * to know what a good confidence looks like.
 */
function UnsureChip() {
  const { t } = useLanguage()
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-xs font-semibold text-amber-700 dark:bg-amber-900/20 dark:text-amber-400">
      🤔 {t('food.notSure')}
    </span>
  )
}

/**
 * Collapsed: name, portion, kcal, confidence. Tap to expand full macros.
 *
 * When `onPortionChange` is supplied the expanded panel also offers portion
 * correction. Portion is the largest error source in the pipeline — a single
 * number guessed from a 2D photo with no depth and no reference object — and
 * until this existed the only way to fix a meal logged at double its real size
 * was to delete it.
 *
 * `onRemove` covers the case a multiplier cannot: the model listed something
 * that is not on the plate. Shrinking a hallucinated dish toward its 0.25×
 * floor still leaves it counting toward variety and the food-group mix, so a
 * phantom vegetable would keep earning its bonus however small it got.
 */
export default function FoodCard({ food, onPortionChange, onRemove, multiplier = 1, busy = false }) {
  const { t } = useLanguage()
  const [open, setOpen] = useState(false)
  const unsure = typeof food.confidence === 'number' && food.confidence < UNSURE
  const range =
    food.caloriesHigh > food.caloriesLow
      ? `${Math.round(food.caloriesLow)}–${Math.round(food.caloriesHigh)}`
      : null

  return (
    <button
      onClick={() => setOpen((v) => !v)}
      className="w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-left shadow-sm transition active:scale-[0.99] dark:border-slate-700 dark:bg-slate-800"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-slate-900 dark:text-slate-100">{food.name}</p>
          <p className="text-xs text-slate-500 dark:text-slate-400">{food.estimatedPortion}</p>
          {unsure && (
            <p className="mt-1">
              <UnsureChip />
            </p>
          )}
        </div>
        <div className="flex shrink-0 flex-col items-end gap-0.5">
          <span className="text-sm font-bold text-slate-900 dark:text-slate-100">{Math.round(food.calories)} kcal</span>
          {/* The portion bracket the model gave for this item. Shown next to the
              point estimate rather than instead of it — the single number is
              still what the meal is scored on. */}
          {range && <span className="text-xs text-slate-500 dark:text-slate-400">{range} kcal</span>}
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
          <p className="col-span-3 pt-1 text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {sourceLabel(food.source, t)}
            {cookingLabel(food, t)}
          </p>
          {onPortionChange && (
            <PortionPicker multiplier={multiplier} busy={busy} onChange={onPortionChange} />
          )}
          {onRemove && <RemoveFood name={food.name} busy={busy} onRemove={onRemove} />}
        </div>
      )}
    </button>
  )
}

/**
 * Where the numbers came from, in descending order of trust.
 *
 * "local" is the curated Malaysian composition table and outranks USDA: a USDA
 * match for a local dish is the nearest generic it could find ("coconut rice"
 * for the nasi lemak base), while a local row is the dish itself, transcribed
 * from a published table and carrying a citation.
 */
function sourceLabel(source, t) {
  if (source === 'local') return t('food.localDatabase')
  if (source === 'usda') return t('food.usda')
  if (source === 'barcode') return t('food.barcode')
  return t('food.aiEstimate')
}

/**
 * The model used to answer a single "fried" boolean; it now picks from a
 * cooking-method vocabulary, so the card can say "stir-fried" instead of
 * flattening it to the same word as deep-fried. Falls back to the old boolean
 * for barcode scans and for meals logged before the vocabulary existed.
 */
function cookingLabel(food, t) {
  if (food.cookingMethod) {
    const label = t(`food.cooking.${food.cookingMethod}`)
    return label && !label.startsWith('food.cooking.') ? ` · ${label}` : ''
  }
  return food.fried ? t('food.deepFried') : ''
}

/**
 * Four coarse steps rather than a free slider: a user knows "about half", not
 * "0.62x", and each tap is a server round trip that re-grades the meal.
 *
 * The buttons stop click propagation because the whole card is itself a button
 * that toggles the panel — without it, correcting a portion would also collapse
 * the panel you corrected it from.
 */
function PortionPicker({ multiplier, busy, onChange }) {
  const { t } = useLanguage()
  return (
    <div className="col-span-3 border-t border-slate-100 pt-3 dark:border-slate-700">
      <p className="mb-1.5 text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('food.portionWasActually')}
      </p>
      <div className="flex justify-center gap-1.5">
        {PORTION_STEPS.map((step) => {
          const selected = step === multiplier
          return (
            <span
              key={step}
              role="radio"
              tabIndex={0}
              aria-checked={selected}
              aria-label={t('food.portionStepLabel', step)}
              aria-disabled={busy}
              onClick={(e) => {
                e.stopPropagation()
                if (!busy) onChange(step)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  e.stopPropagation()
                  if (!busy) onChange(step)
                }
              }}
              className={`cursor-pointer rounded-full px-3 py-1 text-xs font-semibold transition ${
                selected
                  ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900'
                  : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
              } ${busy ? 'opacity-50' : 'active:scale-95'}`}
            >
              {step === 1 ? t('food.portionAsShown') : `${step}×`}
            </span>
          )
        })}
      </div>
    </div>
  )
}

/**
 * "This isn't in the photo."
 *
 * Lives inside the expanded panel rather than as a ✕ on the collapsed row, which
 * is the friction this needs instead of a confirmation dialog: removal cannot be
 * undone, but a modal on every tap would blunt the one correction path the app
 * has. Opening the card is already a deliberate act, and the label says what
 * will go rather than just showing a glyph.
 *
 * A span with role="button" for the same reason PortionPicker uses one — the
 * whole card is a button, and a nested one is invalid HTML.
 */
function RemoveFood({ name, busy, onRemove }) {
  const { t } = useLanguage()
  const activate = (e) => {
    e.preventDefault()
    e.stopPropagation()
    if (!busy) onRemove()
  }
  return (
    <div className="col-span-3 border-t border-slate-100 pt-3 dark:border-slate-700">
      <span
        role="button"
        tabIndex={0}
        aria-disabled={busy}
        aria-label={t('food.removeItemLabel', name)}
        onClick={activate}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') activate(e)
        }}
        className={`inline-flex cursor-pointer items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold text-red-600 transition dark:text-red-400 ${
          busy ? 'opacity-50' : 'active:scale-95'
        }`}
      >
        <span aria-hidden="true">✕</span>
        {t('food.removeItem')}
      </span>
    </div>
  )
}

function Macro({ label, value }) {
  return (
    <div>
      <p className="text-xs font-semibold text-slate-800 dark:text-slate-200">{value}</p>
      <p className="text-xs text-slate-500 dark:text-slate-400">{label}</p>
    </div>
  )
}
