import { useCallback, useEffect, useRef, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { STAGE_COUNTS, isOverdue, stageAt, stageState } from '../analyzingPhases.js'

/**
 * The chopsticks, drawn rather than typed.
 *
 * They were an emoji (🥢) until this screen was redesigned. Drawing them means
 * they render identically on every platform, can be coloured, and can be
 * animated as one object.
 *
 * On the colour: red is the mark as drawn. Note that red elsewhere in this app
 * means a failing grade — `grade.d` is #b91c1c and TIER_COLORS.HANG is the same
 * family — so if the two ever start reading as the same signal, these two
 * constants are the only thing to change (grade-aplus green is the obvious
 * alternative).
 */
const STICK_SHAFT = '#e11d48'
const STICK_TIP = '#6b7280'

function pickInsight(count, excludeIndex) {
  if (count <= 1) return 0
  let next
  do {
    next = Math.floor(Math.random() * count)
  } while (next === excludeIndex)
  return next
}

export default function AnalyzingScreen({ titleKey = 'analyzing.title', flow = 'meal', onCancel }) {
  const { t } = useLanguage()
  const elapsed = useElapsed(flow)
  const stage = stageAt(elapsed, flow)
  const { insight, shuffle, tapping, visible } = useInsight()

  // "Usually about N seconds" stops being true at some point. When it does, say
  // so rather than letting a stale promise sit on screen — see isOverdue.
  const subtitle = isOverdue(elapsed, flow)
    ? t('analyzing.late')
    : t(flow === 'barcode' ? 'analyzing.takesBarcode' : 'analyzing.takes')

  return (
    <div className="flex flex-col items-center gap-5 pt-8">
      <Chopsticks tapping={tapping} onTap={shuffle} label={t('analyzing.anotherTip')} />

      <div className="space-y-1 text-center">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-50">{t(titleKey)}…</h1>
        <p className="text-sm text-slate-500 dark:text-slate-400">{subtitle}</p>
      </div>

      <StageStepper flow={flow} stage={stage} />

      <div className="w-full max-w-xs pt-2">
        <button
          type="button"
          onClick={shuffle}
          aria-label={t('analyzing.anotherTip')}
          className="flex w-full items-center gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-3.5 text-left transition active:scale-[0.99] dark:border-slate-700 dark:bg-slate-800/60"
        >
          <span
            className={`flex-1 text-sm leading-relaxed text-slate-700 transition-opacity duration-200 dark:text-slate-200 ${
              visible ? 'opacity-100' : 'opacity-0'
            }`}
          >
            {insight}
          </span>
          <ChevronDown />
        </button>
        <p className="mt-2.5 text-center text-xs text-slate-400 dark:text-slate-500">
          <span aria-hidden="true">ⓘ </span>
          {t('analyzing.tipLabel')}
        </p>
      </div>

      {onCancel && (
        <button
          type="button"
          onClick={onCancel}
          className="mt-1 w-full max-w-[16rem] rounded-full border border-slate-300 py-3 text-sm font-semibold text-slate-700 active:scale-[0.98] dark:border-slate-600 dark:text-slate-200"
        >
          {t('analyzing.cancel')}
        </button>
      )}
    </div>
  )
}

/**
 * Elapsed milliseconds since the request started.
 *
 * setInterval rather than requestAnimationFrame on purpose: rAF is suspended
 * entirely while the tab is hidden, so backgrounding the app mid-analysis would
 * freeze the screen and it would still be frozen when the user came back. A
 * timer keeps counting (throttled, which is fine at this resolution).
 */
function useElapsed(flow) {
  const [elapsed, setElapsed] = useState(0)

  useEffect(() => {
    setElapsed(0)
    const startedAt = Date.now()
    // Half a second is finer than any stage boundary, so a transition never
    // lands more than that late, and it is coarse enough to be free.
    const tick = setInterval(() => setElapsed(Date.now() - startedAt), 500)
    return () => clearInterval(tick)
  }, [flow])

  return elapsed
}

/** The rotating tip, and the one action that changes it. */
function useInsight() {
  const { t } = useLanguage()
  const insights = t('analyzing.insights')
  const [index, setIndex] = useState(() => Math.floor(Math.random() * insights.length))
  const [visible, setVisible] = useState(true)
  const [tapping, setTapping] = useState(false)
  const indexRef = useRef(index)
  indexRef.current = index

  const shuffle = useCallback(() => {
    setTapping(true)
    setTimeout(() => setTapping(false), 450)
    setVisible(false)
    setTimeout(() => {
      setIndex(pickInsight(insights.length, indexRef.current))
      setVisible(true)
    }, 220)
  }, [insights.length])

  return { insight: insights[index], shuffle, tapping, visible }
}

/**
 * Tappable, because tapping it swaps the tip.
 *
 * That gesture existed before the redesign and nothing on screen admitted it,
 * so the tip card below is wired to the same handler — a hidden gesture is only
 * a feature if something visible offers it too.
 */
function Chopsticks({ tapping, onTap, label }) {
  return (
    <button
      type="button"
      onClick={onTap}
      aria-label={label}
      className="relative flex h-28 w-28 items-center justify-center border-0 bg-transparent p-0 [-webkit-tap-highlight-color:transparent]"
    >
      <span
        aria-hidden="true"
        className="absolute h-20 w-20 rounded-full bg-rose-500/25 blur-2xl motion-safe:animate-soft-pulse"
      />
      <svg
        viewBox="0 0 100 100"
        aria-hidden="true"
        className={`relative h-full w-full ${
          tapping ? 'motion-safe:animate-chopstick-tap' : 'motion-safe:animate-chopstick-float'
        }`}
      >
        <g fill="none" strokeLinecap="round" strokeWidth="5.4">
          <line x1="63" y1="9" x2="38" y2="90" stroke={STICK_SHAFT} />
          <line x1="63" y1="9" x2="58" y2="26" stroke={STICK_TIP} />
          <line x1="75" y1="12" x2="47" y2="92" stroke={STICK_SHAFT} />
          <line x1="75" y1="12" x2="70" y2="29" stroke={STICK_TIP} />
        </g>
      </svg>
    </button>
  )
}

function ChevronDown() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4 flex-none text-slate-400 dark:text-slate-500"
    >
      <path d="M6 9l6 6 6-6" />
    </svg>
  )
}

/**
 * The three real legs of the pipeline, as a stepper.
 *
 * Announced as a live region so the progression is available without sight —
 * the tip above is decorative and deliberately is not.
 *
 * The last step never becomes 'done': reaching it means the request is still in
 * flight, and this screen unmounts when it isn't. That is why there is no
 * percentage and no bar anywhere on this screen.
 */
function StageStepper({ flow, stage }) {
  const { t } = useLanguage()
  const labels = t(`analyzing.stages.${flow}`)
  const count = STAGE_COUNTS[flow] ?? labels.length

  return (
    <ol
      role="status"
      aria-live="polite"
      className="grid w-full max-w-xs"
      style={{ gridTemplateColumns: `repeat(${count}, minmax(0, 1fr))` }}
    >
      {Array.from({ length: count }, (_, i) => {
        const state = stageState(i, stage)
        return (
          <li key={i} className="relative flex flex-col items-center gap-2 px-1">
            {i > 0 && (
              <span
                aria-hidden="true"
                className={`absolute -left-1/2 right-1/2 top-[17px] h-0.5 ${
                  i <= stage
                    ? 'bg-grade-aplus dark:bg-green-400'
                    : 'bg-slate-200 dark:bg-slate-700'
                }`}
              />
            )}
            <span
              aria-hidden="true"
              className={`relative z-10 flex h-9 w-9 items-center justify-center rounded-full border-2 text-sm font-bold ${CIRCLE[state]}`}
            >
              {state === 'done' ? '✓' : i + 1}
            </span>
            <span className={`text-center text-xs leading-snug ${LABEL[state]}`}>{labels[i]}</span>
          </li>
        )
      })}
    </ol>
  )
}

const CIRCLE = {
  done: 'border-grade-aplus bg-slate-50 text-grade-aplus dark:border-green-400 dark:bg-slate-900 dark:text-green-400',
  active: 'border-grade-aplus bg-grade-aplus text-white dark:border-green-400 dark:bg-green-400 dark:text-slate-900',
  pending: 'border-slate-200 bg-slate-50 text-slate-400 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-500',
}

const LABEL = {
  done: 'text-slate-500 dark:text-slate-400',
  active: 'font-semibold text-slate-900 dark:text-slate-100',
  pending: 'text-slate-400 dark:text-slate-500',
}
