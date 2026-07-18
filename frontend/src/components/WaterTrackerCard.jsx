import { useEffect, useRef, useState } from 'react'
import { adjustWater, fetchWaterToday, resetWater, setWaterTarget } from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

const GLASS_ML = 250
const BOTTLE_ML = 500
const MAX_ML = 8000
const TARGET_PRESETS = [1500, 2000, 2500, 3000, 3500]
const DEFAULT_TARGET_ML = 2000
const CELEBRATION_MS = 3000
const CONFIRM_RESET_MS = 2500
const VISITOR_WATER_KEY = 'dbt_water_today'
const VISITOR_TARGET_KEY = 'dbt_water_target'

function todayKey() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// Visitor water is kept in localStorage (keyed by local date) instead of only
// component state — the card unmounts on every tab switch, so state-only
// would silently wipe the count each time the user leaves the Analysis tab.
function loadVisitorTotal() {
  try {
    const saved = JSON.parse(localStorage.getItem(VISITOR_WATER_KEY))
    if (saved && saved.date === todayKey() && typeof saved.totalMl === 'number') {
      return saved.totalMl
    }
  } catch {
    /* ignore malformed/missing entry — starts the day at 0 */
  }
  return 0
}

function saveVisitorTotal(totalMl) {
  try {
    localStorage.setItem(VISITOR_WATER_KEY, JSON.stringify({ date: todayKey(), totalMl }))
  } catch {
    /* private mode / quota errors — losing this just means the count won't survive navigation */
  }
}

function loadVisitorTarget() {
  const saved = Number(localStorage.getItem(VISITOR_TARGET_KEY))
  return saved > 0 ? saved : DEFAULT_TARGET_ML
}

/**
 * A small daily water counter — tap-to-add chips, not a stepper, since people
 * think in "a glass" or "a bottle," not in increments. Visitors get the same
 * card backed by localStorage instead of the API (same split as dailyBudget).
 */
export default function WaterTrackerCard({ isVisitor }) {
  const { t } = useLanguage()
  const [totalMl, setTotalMl] = useState(() => (isVisitor ? loadVisitorTotal() : 0))
  const [targetMl, setTargetMl] = useState(() => (isVisitor ? loadVisitorTarget() : DEFAULT_TARGET_ML))
  const [loading, setLoading] = useState(!isVisitor)
  const [pickingTarget, setPickingTarget] = useState(false)
  const [celebration, setCelebration] = useState(null)
  const [confirmingReset, setConfirmingReset] = useState(false)
  // Source of truth for rapid taps: React batches same-tick state updates, so
  // reading `totalMl` from the closure would drop all but the last of several
  // quick taps. The ref is updated synchronously on every change instead.
  const totalRef = useRef(isVisitor ? loadVisitorTotal() : 0)
  const confirmResetTimerRef = useRef(null)

  useEffect(() => {
    if (isVisitor) {
      setLoading(false)
      return
    }
    fetchWaterToday()
      .then((d) => {
        totalRef.current = d.totalMl
        setTotalMl(d.totalMl)
        setTargetMl(d.targetMl)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [isVisitor])

  useEffect(() => {
    if (!celebration) return
    const timer = setTimeout(() => setCelebration(null), CELEBRATION_MS)
    return () => clearTimeout(timer)
  }, [celebration])

  useEffect(() => () => clearTimeout(confirmResetTimerRef.current), [])

  const messages = t('waterTracker.celebrations')

  const maybeCelebrate = (previousTotal, nextTotal, target) => {
    if (previousTotal < target && nextTotal >= target) {
      setCelebration(messages[Math.floor(Math.random() * messages.length)])
    }
  }

  const add = async (deltaMl) => {
    if (isVisitor) {
      const previous = totalRef.current
      const next = Math.max(0, Math.min(MAX_ML, previous + deltaMl))
      totalRef.current = next
      setTotalMl(next)
      saveVisitorTotal(next)
      maybeCelebrate(previous, next, targetMl)
      return
    }
    // Authed: the server holds the real total and applies deltaMl atomically,
    // so correctness doesn't depend on the client's copy — only the
    // before/after read for the celebration check does.
    const previous = totalRef.current
    try {
      const data = await adjustWater(deltaMl)
      totalRef.current = data.totalMl
      setTotalMl(data.totalMl)
      maybeCelebrate(previous, data.totalMl, data.targetMl)
    } catch {
      /* leave state as-is; the next tap will retry against the server's real total */
    }
  }

  const doReset = async () => {
    setCelebration(null)
    if (isVisitor) {
      totalRef.current = 0
      setTotalMl(0)
      saveVisitorTotal(0)
      return
    }
    try {
      const data = await resetWater()
      totalRef.current = data.totalMl
      setTotalMl(data.totalMl)
    } catch {
      /* ignore */
    }
  }

  // One stray tap shouldn't wipe the whole day's count — the first tap only
  // arms a short confirm window; a second tap within it actually resets.
  const handleResetTap = () => {
    if (!confirmingReset) {
      setConfirmingReset(true)
      confirmResetTimerRef.current = setTimeout(() => setConfirmingReset(false), CONFIRM_RESET_MS)
      return
    }
    clearTimeout(confirmResetTimerRef.current)
    setConfirmingReset(false)
    doReset()
  }

  const changeTarget = async (next) => {
    setPickingTarget(false)
    if (isVisitor) {
      setTargetMl(next)
      try {
        localStorage.setItem(VISITOR_TARGET_KEY, String(next))
      } catch {
        /* ignore */
      }
      return
    }
    try {
      const data = await setWaterTarget(next)
      totalRef.current = data.totalMl
      setTotalMl(data.totalMl)
      setTargetMl(data.targetMl)
    } catch {
      /* ignore */
    }
  }

  if (loading) return null

  const fraction = Math.min(1, totalMl / targetMl)
  const goalReached = totalMl >= targetMl
  const atCeiling = totalMl >= MAX_ML

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center justify-between">
        <h2 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          💧 {t('waterTracker.title')}
        </h2>
        <button
          onClick={() => setPickingTarget((v) => !v)}
          className="text-[11px] font-medium text-slate-500 dark:text-slate-400"
        >
          {targetMl}ml →
        </button>
      </div>

      {pickingTarget && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {TARGET_PRESETS.map((preset) => (
            <button
              key={preset}
              onClick={() => changeTarget(preset)}
              className={`rounded-lg px-2.5 py-1 text-[11px] font-semibold ${
                preset === targetMl
                  ? 'bg-grade-aplus text-white'
                  : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
              }`}
            >
              {preset}ml
            </button>
          ))}
        </div>
      )}

      <p className="mt-2 text-sm">
        <span className="font-semibold text-slate-900 dark:text-slate-100">{totalMl}</span>
        <span className="text-slate-500 dark:text-slate-400"> / {targetMl}ml</span>
      </p>
      <div className="mt-1.5 h-2.5 overflow-hidden rounded-full bg-sky-50 dark:bg-slate-700">
        <div
          className={`h-full rounded-full transition-all duration-500 ${goalReached ? 'bg-grade-aplus dark:bg-green-400' : 'bg-sky-500 dark:bg-sky-400'}`}
          style={{ width: `${fraction * 100}%` }}
        />
      </div>

      {celebration ? (
        <p className="mt-2.5 text-center text-sm font-semibold text-grade-aplus dark:text-green-400">{celebration}</p>
      ) : (
        goalReached && (
          <p className="mt-2.5 text-center text-[11px] font-semibold text-grade-aplus dark:text-green-400">
            {t('waterTracker.goalReached')}
          </p>
        )
      )}

      <div className="mt-3 flex items-center gap-2">
        <button
          onClick={() => add(GLASS_ML)}
          disabled={atCeiling}
          className="flex flex-1 items-center justify-center gap-1.5 rounded-xl border border-slate-200 py-2 text-xs font-semibold text-slate-700 active:scale-[0.98] disabled:opacity-40 dark:border-slate-600 dark:text-slate-200"
        >
          🥛 {t('waterTracker.glass')}
        </button>
        <button
          onClick={() => add(BOTTLE_ML)}
          disabled={atCeiling}
          className="flex flex-1 items-center justify-center gap-1.5 rounded-xl border border-slate-200 py-2 text-xs font-semibold text-slate-700 active:scale-[0.98] disabled:opacity-40 dark:border-slate-600 dark:text-slate-200"
        >
          🍼 {t('waterTracker.bottle')}
        </button>
        <button
          onClick={handleResetTap}
          className={`px-2 text-[11px] font-medium ${
            confirmingReset ? 'font-semibold text-red-500 dark:text-red-400' : 'text-slate-500 dark:text-slate-400'
          }`}
        >
          {confirmingReset ? t('waterTracker.confirmReset') : t('waterTracker.reset')}
        </button>
      </div>
    </section>
  )
}
