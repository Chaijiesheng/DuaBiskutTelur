import { useEffect, useRef, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'

// When each step lights up, in ms from the start of the request. Calibrated to
// measured menu-scan stages (upload, then the model reading the page, then the
// nutrition lookups) — see the "Menu scan finished" timing log in
// MenuRankingService. A single blocking request can't report real progress, so
// these are honest estimates: the last step stays pending until the response
// actually arrives, and no step ever claims to be done before it is.
const STEP_START_MS = [0, 3_000, 36_000, 41_000]

function pickInsight(count, excludeIndex) {
  if (count <= 1) return 0
  let next
  do {
    next = Math.floor(Math.random() * count)
  } while (next === excludeIndex)
  return next
}

export default function AnalyzingScreen({ titleKey = 'analyzing.title', steps }) {
  const { t } = useLanguage()
  const insights = t('analyzing.insights')
  const [index, setIndex] = useState(() => Math.floor(Math.random() * insights.length))
  const [visible, setVisible] = useState(true)
  const [tapping, setTapping] = useState(false)
  const [stepIndex, setStepIndex] = useState(0)
  const indexRef = useRef(index)
  indexRef.current = index

  useEffect(() => {
    if (!steps?.length) return undefined
    const timers = steps
      .slice(1)
      .map((_, i) => setTimeout(() => setStepIndex(i + 1), STEP_START_MS[i + 1] ?? STEP_START_MS.at(-1)))
    return () => timers.forEach(clearTimeout)
  }, [steps])

  const handleTap = () => {
    setTapping(true)
    setTimeout(() => setTapping(false), 450)
    setVisible(false)
    setTimeout(() => {
      const next = pickInsight(insights.length, indexRef.current)
      setIndex(next)
      setVisible(true)
    }, 220)
  }

  return (
    <div className="flex flex-col items-center gap-6 pt-16">
      <p className="text-sm text-slate-500 dark:text-slate-400">{t(titleKey)}…</p>

      <div className="relative flex h-28 w-28 items-center justify-center">
        <div className="absolute h-full w-full rounded-full bg-grade-aplus/10 motion-safe:animate-soft-pulse" />
        <div
          className="absolute h-[78%] w-[78%] rounded-full bg-grade-aplus/15 motion-safe:animate-soft-pulse"
          style={{ animationDelay: '0.7s' }}
        />
        <button
          type="button"
          onClick={handleTap}
          aria-label={t(titleKey)}
          className={`select-none border-0 bg-transparent p-0 text-5xl leading-none [-webkit-tap-highlight-color:transparent] ${
            tapping ? 'motion-safe:animate-chopstick-tap' : 'motion-safe:animate-chopstick-float'
          }`}
        >
          🥢
        </button>
      </div>

      {/* A long wait with no signal reads as "stuck" — this at least says which
          part is slow. aria-live so it's announced rather than silently changing. */}
      {steps?.length > 0 && (
        <ol className="w-full max-w-xs space-y-2" aria-live="polite">
          {steps.map((step, i) => {
            const done = i < stepIndex
            const current = i === stepIndex
            return (
              <li
                key={step}
                className={`flex items-center gap-2.5 text-sm transition-colors ${
                  done
                    ? 'text-slate-500 dark:text-slate-400'
                    : current
                      ? 'font-semibold text-slate-800 dark:text-slate-100'
                      : 'text-slate-300 dark:text-slate-600'
                }`}
              >
                <span
                  className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[11px] ${
                    done
                      ? 'bg-grade-aplus text-white'
                      : current
                        ? 'border-2 border-grade-aplus dark:border-green-400'
                        : 'border-2 border-slate-200 dark:border-slate-700'
                  }`}
                >
                  {done ? '✓' : ''}
                </span>
                {step}
              </li>
            )
          })}
        </ol>
      )}

      <p
        className={`min-h-[3.5rem] max-w-xs text-center text-sm leading-relaxed text-slate-600 transition-opacity duration-200 dark:text-slate-300 ${
          visible ? 'opacity-100' : 'opacity-0'
        }`}
      >
        {insights[index]}
      </p>
    </div>
  )
}
