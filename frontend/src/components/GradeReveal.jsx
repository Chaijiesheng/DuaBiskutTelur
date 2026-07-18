import { useEffect, useRef, useState } from 'react'
import confetti from 'canvas-confetti'
import { prefersReducedMotion } from '../motionPreference.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { useTheme } from '../theme/ThemeContext.jsx'

// Per-theme so both pass WCAG AA: light values are ≥4.5:1 on white (the
// history chips render them at 14px bold), dark values are checked against
// the slate-800 cards. Same light/dark split as WeightTrendCard's LINE_COLOR.
// Keep the light set in sync with the grade.* tokens in tailwind.config.js.
export const GRADE_COLORS = {
  light: { 'A+': '#15803d', A: '#166534', B: '#4d7c0f', C: '#b45309', D: '#b91c1c' },
  dark: { 'A+': '#4ade80', A: '#22c55e', B: '#a3e635', C: '#fbbf24', D: '#f87171' },
}

const COUNT_MS = 1200

/**
 * Score counts up from 0 to the final number, then the letter grade stamps in.
 * A and A+ earn a confetti burst.
 */
export default function GradeReveal({ score, grade, encouragement }) {
  const { t } = useLanguage()
  const { theme } = useTheme()
  const [displayScore, setDisplayScore] = useState(0)
  const [showGrade, setShowGrade] = useState(false)
  const fired = useRef(false)

  useEffect(() => {
    if (prefersReducedMotion()) {
      setDisplayScore(score)
      setShowGrade(true)
      return
    }
    let raf
    const start = performance.now()
    const tick = (now) => {
      const progress = Math.min(1, (now - start) / COUNT_MS)
      const eased = 1 - Math.pow(1 - progress, 3)
      setDisplayScore(Math.round(eased * score))
      if (progress < 1) {
        raf = requestAnimationFrame(tick)
      } else {
        setShowGrade(true)
      }
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [score])

  useEffect(() => {
    if (showGrade && !fired.current && (grade === 'A' || grade === 'A+') && !prefersReducedMotion()) {
      fired.current = true
      confetti({ particleCount: grade === 'A+' ? 180 : 100, spread: 75, origin: { y: 0.35 } })
      if (grade === 'A+') {
        setTimeout(
          () => confetti({ particleCount: 90, angle: 120, spread: 55, origin: { x: 1, y: 0.4 } }),
          250,
        )
      }
    }
  }, [showGrade, grade])

  const color = GRADE_COLORS[theme][grade] ?? '#64748b'

  return (
    <div className="flex flex-col items-center gap-2 py-6">
      {/* The visual reveal is a per-frame count-up — screen readers would hear
          digit noise and might never catch the grade stamp, so the animated
          row is hidden from them and this one line announces the final result. */}
      {showGrade && (
        <p className="sr-only" role="status">
          {t('results.scoreAnnouncement', score, grade)}
        </p>
      )}
      <div className="flex items-end gap-3" aria-hidden="true">
        <span className="text-6xl font-black tabular-nums text-slate-900 dark:text-slate-100">{displayScore}</span>
        <span className="pb-2 text-sm text-slate-500 dark:text-slate-400">/ 100</span>
        {showGrade && (
          <span
            className="animate-stamp rounded-2xl border-4 px-4 pb-1 pt-2 text-5xl font-black leading-none"
            style={{ color, borderColor: color }}
          >
            {grade}
          </span>
        )}
      </div>
      {showGrade && (
        <p className="mt-1 max-w-xs text-center text-sm font-medium text-slate-600 dark:text-slate-300">
          {(grade === 'A' || grade === 'A+') && '🎉 '}
          {encouragement}
        </p>
      )}
    </div>
  )
}
