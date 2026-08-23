import { useCallback, useEffect, useState } from 'react'
import { exportTrendPdf, fetchTrendReport } from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { buildTrendShareCard } from '../shareCard.js'
import { AnalysisSkeleton } from './Skeleton.jsx'
import { ShareGlyph, useShareCard } from './ShareControls.jsx'

const PERIODS = ['week', 'month']

/**
 * The weekly and monthly trend report.
 *
 * <p>Every figure shown here arrives computed from the backend. This component
 * formats and arranges; it never averages, totals or compares, because a number
 * the user checks against last week has to come from one place. The one thing
 * it does decide is what to <em>hide</em>: a null from the server means "not
 * enough to say", and a tile with nothing honest in it is not rendered rather
 * than rendered as a zero.
 */
export default function TrendReport() {
  const { t, lang } = useLanguage()
  const [period, setPeriod] = useState('week')
  const [report, setReport] = useState(null)
  const [state, setState] = useState('loading')

  useEffect(() => {
    let live = true
    setState('loading')
    fetchTrendReport(period, lang)
      .then((data) => {
        if (live) {
          setReport(data)
          setState('ready')
        }
      })
      .catch(() => {
        if (live) setState('error')
      })
    // Cancelled on unmount and on a period switch, so a slow month request
    // cannot land after the user has flipped back to the week and overwrite it.
    return () => {
      live = false
    }
    // lang is a dependency: the written summary is generated in the user's
    // language, so switching language has to refetch rather than leave a
    // paragraph from the previous one sitting under translated labels.
  }, [period, lang])

  return (
    <section className="flex flex-col gap-3">
      <div
        role="tablist"
        aria-label={t('trends.title')}
        className="flex gap-1 rounded-2xl border border-slate-200 bg-slate-50 p-1 dark:border-slate-700 dark:bg-slate-900/60"
      >
        {PERIODS.map((p) => (
          <button
            key={p}
            type="button"
            role="tab"
            aria-selected={period === p}
            onClick={() => setPeriod(p)}
            className={`flex-1 rounded-xl py-2 text-sm font-bold transition ${
              period === p
                ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-700 dark:text-slate-100'
                : 'text-slate-500 dark:text-slate-400'
            }`}
          >
            {t(`trends.${p}`)}
          </button>
        ))}
      </div>

      {state === 'loading' && <AnalysisSkeleton label={t('trends.loading')} />}
      {state === 'error' && (
        <p className="px-1 text-center text-sm text-slate-500 dark:text-slate-400">{t('trends.couldntLoad')}</p>
      )}
      {state === 'ready' && report && <Report report={report} period={period} />}
    </section>
  )
}

function Report({ report, period }) {
  const { t, lang } = useLanguage()
  const { totals, previous } = report

  return (
    <div className="flex flex-col gap-3">
      <p className="px-1 font-mono text-[0.7rem] text-slate-500 dark:text-slate-400">
        {rangeLabel(report, lang)} &middot; {t('trends.daysLogged', totals.daysLogged, report.daysInWindow)}
      </p>

      {!report.enoughData && (
        <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-900/60">
          <p className="text-sm font-bold text-slate-900 dark:text-slate-100">{t('trends.thinTitle')}</p>
          <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{t('trends.thinBody')}</p>
        </div>
      )}

      <div className="grid grid-cols-2 gap-2">
        <Tile
          label={t('trends.avgDaily')}
          value={totals.avgDailyCalories}
          unit={t('trends.kcal')}
          delta={delta(totals.avgDailyCalories, previous?.avgDailyCalories)}
          lowerIsBetter
        />
        <Tile label={t('trends.avgGrade')} value={totals.avgGrade} gradeDelta={previous?.avgGrade} />
        <Tile
          label={t('trends.protein')}
          value={totals.avgDailyProtein}
          unit={t('trends.gramsPerDay')}
          delta={delta(totals.avgDailyProtein, previous?.avgDailyProtein)}
        />
        <Tile
          label={t('trends.meals')}
          value={totals.mealCount}
          delta={delta(totals.mealCount, previous?.mealCount)}
        />
      </div>

      <CaloriesChart report={report} />

      <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <h3 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          {t('trends.habits')}
        </h3>
        <dl className="mt-2 flex flex-col gap-1.5">
          <Row
            label={t('trends.vegetables')}
            value={totals.vegetableServings != null ? t('trends.servings', totals.vegetableServings) : null}
          />
          <Row
            label={t('trends.fruitDays')}
            value={totals.fruitDays != null ? t('trends.ofDays', totals.fruitDays, totals.daysLogged) : null}
          />
          <Row
            label={t('trends.water')}
            value={
              totals.avgDailyWaterMl != null
                ? t('trends.waterValue', (totals.avgDailyWaterMl / 1000).toFixed(1), totals.waterDaysOnTarget)
                : null
            }
          />
          <Row
            label={t('trends.workouts')}
            value={totals.workoutsDone != null ? t('trends.workoutValue', totals.workoutsDone, totals.workoutMinutes) : null}
          />
          <Row
            label={t('trends.weight')}
            value={
              totals.latestWeightKg != null
                ? t('trends.weightValue', totals.latestWeightKg.toFixed(1), formatChange(totals.weightChangeKg))
                : null
            }
          />
        </dl>
      </div>

      <GradeMix mix={report.gradeMix} />

      {report.narrative && (
        <p className="rounded-2xl border border-slate-200 bg-white p-4 text-sm leading-relaxed text-slate-700 shadow-sm dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200">
          {report.narrative}
        </p>
      )}

      <ReportActions report={report} period={period} />
    </div>
  )
}

/**
 * Share and Export, side by side.
 *
 * <p>Both are hidden until there is a trend worth carrying anywhere: a document
 * covering three days is not something anyone takes to a doctor, and offering
 * it invites the user to produce one and conclude the feature is thin.
 *
 * <p>They are two different acts, which is why they carry different figures.
 * The PDF is saved and handed to somebody the user chose; the card goes into a
 * group chat. See buildTrendShareCard for what the card leaves off.
 */
function ReportActions({ report, period }) {
  const { t, lang } = useLanguage()
  const [exportState, setExportState] = useState('idle')
  const { totals } = report

  const buildCard = useCallback(() => buildTrendShareCard({
    brandTitle: `${t('app.title1')}${t('app.title2')}`,
    periodLabel: t(`trends.${period}`),
    rangeLabel: rangeLabel(report, lang),
    period,
    grade: totals.avgGrade,
    daysLogged: totals.daysLogged,
    daysInWindow: report.daysInWindow,
    mealCount: totals.mealCount,
    // Same "not enough to say" rule as the tiles: a row the server withheld is
    // dropped rather than drawn as a zero. Weight is absent by choice, not by
    // omission -- the card is the one export that goes somewhere public.
    rows: [
      { label: t('trends.shareDays'), value: `${totals.daysLogged}/${report.daysInWindow}` },
      { label: t('trends.avgDaily'), value: totals.avgDailyCalories },
      { label: t('trends.protein'), value: totals.avgDailyProtein == null ? null : `${totals.avgDailyProtein}g` },
      { label: t('trends.meals'), value: totals.mealCount },
      { label: t('trends.vegetables'), value: totals.vegetableServings },
    ],
    days: report.days,
    calorieBudget: report.calorieBudget,
    chartLabel: t('trends.dailyCalories'),
    shareText: t('trends.shareText', totals.daysLogged, report.daysInWindow),
  }), [report, period, totals, t, lang])

  const { state: shareState, share } = useShareCard(
    buildCard, `duabiskuttelur-${period}-${report.to}.png`)

  if (!report.enoughData) {
    return null
  }

  const button = 'flex min-h-[3rem] flex-1 items-center justify-center gap-2 rounded-2xl border border-slate-200 bg-white text-sm font-bold text-slate-700 shadow-sm disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200'

  return (
    <div className="flex flex-col gap-1">
      <div className="flex gap-2">
        <button
          type="button"
          onClick={share}
          disabled={shareState === 'preparing'}
          aria-busy={shareState === 'preparing'}
          className={button}
        >
          <ShareGlyph className="h-4 w-4" />
          {shareState === 'preparing' ? t('trends.sharing') : t('trends.share')}
        </button>
        <button
          type="button"
          disabled={exportState === 'working'}
          onClick={() => {
            setExportState('working')
            exportTrendPdf(period)
              .then(() => setExportState('idle'))
              .catch(() => setExportState('error'))
          }}
          className={button}
        >
          {exportState === 'working' ? t('trends.exporting') : t('trends.exportPdf')}
        </button>
      </div>
      {shareState === 'error' && (
        <p role="alert" className="px-1 text-center text-xs text-amber-600 dark:text-amber-400">
          {t('results.shareError')}
        </p>
      )}
      {exportState === 'error' && (
        <p className="px-1 text-center text-xs text-amber-600 dark:text-amber-400">{t('trends.exportFailed')}</p>
      )}
      <p className="px-1 text-center text-[0.7rem] text-slate-500 dark:text-slate-400">{t('trends.exportNote')}</p>
    </div>
  )
}

/**
 * A headline number with its change against the previous period.
 *
 * <p>Renders nothing at all when the value is null. The server sends null for
 * "not enough to say" -- a protein average over meals that predate the column,
 * a weight change from a single weigh-in -- and printing a zero there would
 * turn missing data into a reported shortfall.
 */
function Tile({ label, value, unit, delta: change, gradeDelta, lowerIsBetter = false }) {
  const { t } = useLanguage()
  if (value == null) {
    return null
  }
  const better = change == null ? null : lowerIsBetter ? change < 0 : change > 0
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-3 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <p className="text-[0.65rem] font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">{label}</p>
      <p className="mt-0.5 text-2xl font-black leading-tight text-slate-900 dark:text-slate-100">
        {typeof value === 'number' ? value.toLocaleString() : value}
        {unit && <span className="ml-1 text-xs font-bold text-slate-500 dark:text-slate-400">{unit}</span>}
      </p>
      {change != null && change !== 0 && (
        <p className={`mt-0.5 text-[0.7rem] font-bold ${better ? 'text-grade-aplus dark:text-green-400' : 'text-amber-600 dark:text-amber-400'}`}>
          {change > 0 ? '▲' : '▼'} {Math.abs(change).toLocaleString()} {t('trends.vsPrevious')}
        </p>
      )}
      {change === 0 && (
        <p className="mt-0.5 text-[0.7rem] font-bold text-slate-400 dark:text-slate-500">{t('trends.unchanged')}</p>
      )}
      {gradeDelta && gradeDelta !== value && (
        <p className="mt-0.5 text-[0.7rem] font-bold text-slate-500 dark:text-slate-400">
          {t('trends.fromGrade', gradeDelta)}
        </p>
      )}
    </div>
  )
}

function Row({ label, value }) {
  if (value == null) {
    return null
  }
  return (
    <div className="flex items-baseline justify-between gap-3 text-sm">
      <dt className="text-slate-600 dark:text-slate-300">{label}</dt>
      <dd className="font-bold text-slate-900 dark:text-slate-100">{value}</dd>
    </div>
  )
}

/**
 * Daily calories against the budget.
 *
 * <p>Bars are a percentage of the budget, so the dashed line at 100% is the
 * budget itself and a bar crossing it is over -- which is the one thing this
 * chart exists to show. A month is 30 columns, too many to label, so the labels
 * thin out rather than the columns.
 */
function CaloriesChart({ report }) {
  const { t, lang } = useLanguage()
  const budget = report.calorieBudget
  const scale = budget > 0 ? budget : Math.max(...report.days.map((d) => d.calories), 1)
  const monthly = report.period === 'month'

  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-baseline justify-between">
        <h3 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          {t('trends.dailyCalories')}
        </h3>
        {budget > 0 && (
          <span className="text-[0.7rem] text-slate-500 dark:text-slate-400">
            {t('trends.budget', budget.toLocaleString())}
          </span>
        )}
      </div>
      <div className="relative mt-4 pt-3">
        {budget > 0 && (
          <div aria-hidden="true" className="absolute inset-x-0 top-3 border-t border-dashed border-slate-300 dark:border-slate-600" />
        )}
        <div className="flex h-24 items-end gap-[3px]">
          {report.days.map((day) => (
            <div key={day.date} className="flex h-full flex-1 flex-col justify-end">
              <div
                className={`w-full rounded-t-[3px] ${
                  day.overBudget ? 'bg-amber-500' : 'bg-grade-aplus'
                } ${day.logged ? '' : 'opacity-0'}`}
                style={{ height: `${Math.max(day.logged ? 3 : 0, (day.calories / scale) * 100)}%` }}
              />
            </div>
          ))}
        </div>
        <div className="mt-1 flex gap-[3px]">
          {report.days.map((day, i) => (
            <span key={day.date} className="flex-1 text-center text-[0.55rem] text-slate-400 dark:text-slate-500">
              {monthly ? (i % 7 === 0 ? dayNumber(day.date) : '') : weekdayInitial(day.date, lang)}
            </span>
          ))}
        </div>
      </div>
    </div>
  )
}

/**
 * Five bars, always, best to worst.
 *
 * <p>Dropping the empty grades would rescale the axis whenever the data moved,
 * so a user with no D meals would find their C bar sitting where D used to be
 * and read it as a decline.
 */
function GradeMix({ mix }) {
  const { t } = useLanguage()
  const entries = Object.entries(mix ?? {})
  const peak = Math.max(...entries.map(([, n]) => n), 1)
  if (!entries.length || peak === 0) {
    return null
  }
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <h3 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('trends.gradeMix')}
      </h3>
      <div className="mt-3 flex h-14 items-end gap-1.5">
        {entries.map(([grade, count]) => (
          <div key={grade} className="flex h-full flex-1 flex-col items-center justify-end gap-1">
            <div
              className="w-full rounded-t-[3px] bg-grade-aplus"
              style={{ height: `${(count / peak) * 100}%` }}
            />
            <span className="text-[0.6rem] font-bold text-slate-500 dark:text-slate-400">{grade}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

const LOCALE_TAG = { en: 'en-US', zh: 'zh-CN', ms: 'ms-MY' }

/** Parsed at noon: midnight plus a negative offset would name the wrong day. */
function atNoon(iso) {
  return new Date(`${iso}T12:00:00`)
}

function weekdayInitial(iso, lang) {
  return atNoon(iso).toLocaleDateString(LOCALE_TAG[lang] ?? 'en-US', { weekday: 'narrow' })
}

function dayNumber(iso) {
  return atNoon(iso).getDate()
}

function rangeLabel(report, lang) {
  const tag = LOCALE_TAG[lang] ?? 'en-US'
  const opts = { day: 'numeric', month: 'short' }
  return `${atNoon(report.from).toLocaleDateString(tag, opts)} – ${atNoon(report.to).toLocaleDateString(tag, opts)}`
}

function delta(now, before) {
  if (now == null || before == null) {
    return null
  }
  return now - before
}

function formatChange(kg) {
  if (kg == null) {
    return ''
  }
  return `${kg > 0 ? '+' : '−'}${Math.abs(kg).toFixed(1)}`
}
