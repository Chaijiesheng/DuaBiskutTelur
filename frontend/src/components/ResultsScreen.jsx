import { useCallback, useEffect, useState } from 'react'
import GradeReveal from './GradeReveal.jsx'
import MacroDonut from './MacroDonut.jsx'
import CalorieBar from './CalorieBar.jsx'
import FoodCard from './FoodCard.jsx'
import ScoringRubric from './ScoringRubric.jsx'
import SignInBanner from './SignInBanner.jsx'
import FoodEquivalents from './FoodEquivalents.jsx'
import { buildShareCard } from '../shareCard.js'
import { ShareButton, ShareIconButton, useShareCard } from './ShareControls.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { lowConfidenceItems } from '../confidence.js'
import { multipliersFrom, shouldSubmit, withMultiplierAt } from '../portionCorrection.js'
import { correctPortions, removeFood } from '../api.js'

export default function ResultsScreen({
  result,
  dailyBudget,
  goal,
  onSnapAnother,
  actionLabel,
  onExportPdf,
  shareImageSource,
  banner,
  isVisitor = false,
  onResultCorrected,
}) {
  const { t } = useLanguage()
  const { foods, totals, score, grade, highlights, concerns, suggestions, encouragement, source, scoreBreakdown } = result
  const { multipliers, correcting, correctionFailed, correctPortion, removeFoodAt } =
    usePortionCorrection(result, onResultCorrected)
  // One share flow, two controls — see ShareControls.
  const buildCard = useCallback(() => buildShareCard({
    result,
    imageSource: shareImageSource,
    brandTitle: `${t('app.title1')}${t('app.title2')}`,
    shareText: t('results.shareText', result.grade),
    barcodeLabel: t('results.verifiedFromBarcode'),
  }), [result, shareImageSource, t])
  const { state: shareState, share } = useShareCard(buildCard)

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
      <div className="relative">
        <GradeReveal score={score} grade={grade} encouragement={encouragement} foods={foods} />
        <ShareIconButton state={shareState} onShare={share} />
      </div>

      {/* Before the refresh, not after it. The History tab explained this to
          visitors who had already logged meals they were about to lose. */}
      {isVisitor && <SignInBanner text={t('results.visitorWontBeSaved')} />}

      <LowConfidenceNotice foods={foods} />

      <ScoringRubric breakdown={scoreBreakdown} />

      <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <h2 className="mb-3 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">{t('results.totals')}</h2>
        <CalorieBar calories={totals.calories} dailyBudget={dailyBudget} totals={totals} />
        {/* "1,050 kcal" means little on its own. This was only reachable from
            the account budget popover, which is not where anyone reads a
            calorie number. */}
        <div className="mt-3">
          <FoodEquivalents calories={totals.calories} />
        </div>
        <div className="mt-4">
          <MacroDonut totals={totals} goal={goal} />
        </div>
      </section>

      <section className="space-y-2">
        <h2 className="px-1 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          {t('results.whatsOnPlate')}
        </h2>
        {foods.map((food, i) => (
          <FoodCard
            key={`${food.name}-${i}`}
            food={food}
            multiplier={multipliers[i]}
            busy={correcting}
            // Only a saved meal can be corrected: the server re-grades from the
            // stored row rather than from anything the client sends, so there is
            // nothing to correct for a visitor's session-only result.
            onPortionChange={correctPortion ? (value) => correctPortion(i, value) : undefined}
            // Hidden on a one-item meal rather than shown and then refused: the
            // server rejects emptying a meal, and offering a control that can
            // only fail is worse than not offering it.
            onRemove={removeFoodAt && foods.length > 1 ? () => removeFoodAt(i) : undefined}
          />
        ))}
        {correctionFailed && (
          <p role="alert" className="px-1 text-xs text-red-600 dark:text-red-400">
            {t(correctionFailed === 'LAST_FOOD' ? 'results.cantRemoveLastFood' : 'results.correctionFailed')}
          </p>
        )}
      </section>

      <div className="grid grid-cols-1 gap-3">
        <FeedbackList title={t('results.highlights')} icon="✅" items={highlights} tone="text-green-700 dark:text-green-400" />
        <FeedbackList title={t('results.concerns')} icon="⚠️" items={concerns} tone="text-amber-700 dark:text-amber-400" />
        <FeedbackList title={t('results.nextTime')} icon="💡" items={suggestions} tone="text-sky-700 dark:text-sky-400" />
      </div>

      <div className={onExportPdf ? 'grid grid-cols-2 gap-3' : ''}>
        <ShareButton state={shareState} onShare={share} />
        {onExportPdf && <ExportPdfButton onExportPdf={onExportPdf} />}
      </div>

      <button
        onClick={onSnapAnother}
        // Flex so an actionLabel carrying a drawn icon lines up with its text
        // instead of sitting on the baseline.
        className="flex w-full items-center justify-center gap-2 rounded-2xl bg-grade-aplus py-3.5 text-sm font-bold text-white shadow-md active:scale-[0.98]"
      >
        {actionLabel || t('results.snapAnother')}
      </button>
    </div>
  )
}

/**
 * When the model is genuinely unsure what it was looking at, the numbers below
 * should not be presented as fact.
 *
 * The review proposed a "tap to correct" affordance here. It stays a notice
 * rather than a control: the correction UX now exists, but it lives on the
 * cards below, and the unsure items are named here so the eye goes straight to
 * the right one. A second entry point to the same two controls would compete
 * with them rather than add anything.
 */
function LowConfidenceNotice({ foods }) {
  const { t } = useLanguage()
  const unsure = lowConfidenceItems(foods)
  if (unsure.length === 0) return null

  return (
    <div
      role="status"
      className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900/40 dark:bg-amber-900/20 dark:text-amber-300"
    >
      <p className="font-semibold">🤔 {t('results.lowConfidenceTitle')}</p>
      <p className="mt-1 text-xs leading-relaxed">
        {t('results.lowConfidenceBody', unsure.map((f) => f.name).join(', '))}
      </p>
    </div>
  )
}

/**
 * Holds the multipliers on screen and sends corrections to the server, which
 * owns both the rescaling and the re-grade — the scoring engine is deterministic
 * Java, and a second copy here would be free to drift from it.
 *
 * The multipliers are read back off the foods rather than kept independently, so
 * reopening an already-corrected meal shows the corrections it carries instead
 * of offering to undo them from a fresh 1x.
 */
function usePortionCorrection(result, onResultCorrected) {
  const { lang } = useLanguage()
  const [multipliers, setMultipliers] = useState(() => multipliersFrom(result.foods))
  const [correcting, setCorrecting] = useState(false)
  // Null, or the API error code — 'LAST_FOOD' gets its own message.
  const [correctionFailed, setCorrectionFailed] = useState(null)

  useEffect(() => {
    setMultipliers(multipliersFrom(result.foods))
  }, [result.foods])

  const entryId = result.entryId
  const correctPortion = useCallback(async (index, value) => {
    const next = withMultiplierAt(multipliersFrom(result.foods), index, value)
    if (!shouldSubmit(next, multipliersFrom(result.foods))) return
    // Optimistic only for the button state; every number on screen waits for the
    // server, because the server is what decides them.
    setMultipliers(next)
    setCorrecting(true)
    setCorrectionFailed(null)
    try {
      onResultCorrected(await correctPortions(entryId, next, lang))
    } catch (e) {
      setMultipliers(multipliersFrom(result.foods))
      setCorrectionFailed(e?.code ?? 'FAILED')
    } finally {
      setCorrecting(false)
    }
  }, [entryId, lang, onResultCorrected, result.foods])

  /**
   * Removing an item is not a multiplier, so it does not touch the multiplier
   * state at all — the server returns the surviving foods and the effect above
   * re-derives the list from them. Trying to keep a local copy in step through
   * an index shift is how the picker ends up showing one food's correction
   * against another's.
   */
  const removeFoodAt = useCallback(async (index) => {
    setCorrecting(true)
    setCorrectionFailed(null)
    try {
      onResultCorrected(await removeFood(entryId, index, lang))
    } catch (e) {
      setCorrectionFailed(e?.code ?? 'FAILED')
    } finally {
      setCorrecting(false)
    }
  }, [entryId, lang, onResultCorrected])

  return {
    multipliers,
    correcting,
    correctionFailed,
    correctPortion: entryId && onResultCorrected ? correctPortion : null,
    removeFoodAt: entryId && onResultCorrected ? removeFoodAt : null,
  }
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
