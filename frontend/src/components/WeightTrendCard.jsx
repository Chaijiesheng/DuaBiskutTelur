import { useEffect, useState } from 'react'
import { fetchWeightHistory, logWeight } from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { useTheme } from '../theme/ThemeContext.jsx'

// +1 = "more is on track" (muscle gain), -1 = "less is on track" (weight loss), 0 = "flat is on track" (maintenance).
const GOAL_DIRECTION = { weight_loss: -1, muscle_gain: 1, maintenance: 0 }
const LINE_COLOR = {
  weight_loss: { light: '#3b82f6', dark: '#60a5fa' },
  muscle_gain: { light: '#d97706', dark: '#fbbf24' },
  maintenance: { light: '#16a34a', dark: '#4ade80' },
}

/**
 * Weekly-averaged weight trend, read against the goal's direction. Fully
 * optional — an empty state just invites logging, nothing is required and
 * nothing else in the app depends on this being filled in.
 */
export default function WeightTrendCard({ goal }) {
  const { t } = useLanguage()
  const { theme } = useTheme()
  const dark = theme === 'dark'
  const [weeks, setWeeks] = useState(null)
  const [loadError, setLoadError] = useState(false)
  const [input, setInput] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    fetchWeightHistory()
      .then((data) => setWeeks(data.weeks))
      .catch(() => setLoadError(true))
  }, [])

  const submit = async (e) => {
    e.preventDefault()
    const value = Number(input)
    if (!(value > 0)) return
    setSaving(true)
    try {
      const data = await logWeight(value)
      setWeeks(data.weeks)
      setLoadError(false)
      setInput('')
    } catch {
      /* leave the input as typed so the user can retry */
    } finally {
      setSaving(false)
    }
  }

  // Still loading — render nothing. A failed fetch has to stay distinguished
  // from "no weigh-ins yet": both used to collapse into the same empty
  // array, so someone with months of history saw "log your first weight"
  // instead of an error whenever the request merely failed.
  if (weeks === null && !loadError) return null

  const first = weeks?.[0]
  const last = weeks && weeks.length > 0 ? weeks[weeks.length - 1] : null
  const delta = weeks && weeks.length >= 2 ? Math.round((last.avgWeightKg - first.avgWeightKg) * 10) / 10 : null
  const direction = GOAL_DIRECTION[goal] ?? 0
  // A delta under ~0.1kg is measurement noise, not evidence of drifting off
  // goal — without this, a near-zero (or -0 from rounding) delta failed the
  // sign check for weight_loss/muscle_gain goals and showed "off track" for
  // what's really just a flat week.
  const onTrack =
    delta == null ? null : Math.abs(delta) < 0.1 ? true : direction === 0 ? Math.abs(delta) <= 1 : Math.sign(delta) === direction
  const color = (LINE_COLOR[goal] ?? LINE_COLOR.maintenance)[dark ? 'dark' : 'light']

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <h2 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('weightTrend.title')}
      </h2>

      {loadError ? (
        <p className="mt-2 text-sm text-red-500 dark:text-red-400">{t('weightTrend.loadError')}</p>
      ) : weeks.length === 0 ? (
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">{t('weightTrend.empty')}</p>
      ) : (
        <div className="mt-2 flex items-center gap-4">
          <Sparkline weeks={weeks} color={color} />
          <div>
            {delta != null ? (
              <>
                <p className="text-xl font-black" style={{ color }}>
                  {delta > 0 ? '+' : ''}
                  {delta} kg
                </p>
                <p
                  className={`text-[11px] font-semibold ${
                    onTrack ? 'text-grade-aplus dark:text-green-400' : 'text-amber-600 dark:text-amber-400'
                  }`}
                >
                  {onTrack ? t('weightTrend.onTrack') : t('weightTrend.offTrack')}
                </p>
              </>
            ) : (
              <p className="text-sm font-semibold text-slate-600 dark:text-slate-300">
                {last.avgWeightKg} kg
              </p>
            )}
            <p className="mt-0.5 text-[11px] text-slate-500 dark:text-slate-400">{t('weightTrend.window', weeks.length)}</p>
          </div>
        </div>
      )}

      <form onSubmit={submit} className="mt-3 flex gap-2">
        <label htmlFor="weight-trend-input" className="sr-only">
          {t('weightTrend.inputLabel')}
        </label>
        <input
          id="weight-trend-input"
          type="number"
          step="0.1"
          min="30"
          max="250"
          placeholder={t('weightTrend.placeholder')}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          className="input flex-1"
        />
        <button
          type="submit"
          disabled={saving}
          className="rounded-xl bg-grade-aplus px-4 text-sm font-bold text-white shadow-md disabled:opacity-40"
        >
          {t('weightTrend.log')}
        </button>
      </form>
    </section>
  )
}

function Sparkline({ weeks, color }) {
  if (weeks.length < 2) return null
  const values = weeks.map((w) => w.avgWeightKg)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  const points = weeks.map((w, i) => {
    const x = 4 + (i / (weeks.length - 1)) * 112
    const y = 4 + (1 - (w.avgWeightKg - min) / range) * 52
    return [x, y]
  })
  const [lastX, lastY] = points[points.length - 1]

  return (
    <svg viewBox="0 0 120 60" width="120" height="60" className="shrink-0">
      <polyline
        points={points.map(([x, y]) => `${x},${y}`).join(' ')}
        fill="none"
        stroke={color}
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={lastX} cy={lastY} r="3" fill={color} />
    </svg>
  )
}
