import { MACRO_TARGET_RATIO } from '../calorieCalculator.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

const SEGMENTS = [
  { key: 'protein', labelKey: 'food.protein', color: '#3b82f6', kcalPerGram: 4 },
  { key: 'carbs', labelKey: 'food.carbs', color: '#f59e0b', kcalPerGram: 4 },
  { key: 'fat', labelKey: 'food.fat', color: '#ef4444', kcalPerGram: 9 },
]

// How far actual can drift from target before it's worth a note — keeps the
// insight from firing on noise-level differences.
const PROTEIN_INSIGHT_TOLERANCE = 5

/**
 * SVG donut of calories by macro, with a legend in grams. When a goal is
 * known, each macro also gets a thin target-marker bar comparing the meal's
 * actual split against that goal's target split — framed as a comparison,
 * not a verdict, since there's no single correct diet.
 */
export default function MacroDonut({ totals, goal }) {
  const { t } = useLanguage()
  const parts = SEGMENTS.map((s) => ({ ...s, kcal: (totals[s.key] ?? 0) * s.kcalPerGram }))
  const totalKcal = parts.reduce((sum, p) => sum + p.kcal, 0) || 1
  const targets = MACRO_TARGET_RATIO[goal]

  const radius = 42
  const circumference = 2 * Math.PI * radius
  let offset = 0

  const proteinPart = parts.find((p) => p.key === 'protein')
  const proteinActualPct = Math.round((proteinPart.kcal / totalKcal) * 100)
  const proteinTargetPct = targets?.protein
  const proteinDelta = proteinTargetPct != null ? proteinActualPct - proteinTargetPct : null

  return (
    <div>
      <div className="flex items-center gap-5">
        <svg viewBox="0 0 100 100" className="h-28 w-28 -rotate-90 shrink-0">
          {parts.map((p) => {
            const fraction = p.kcal / totalKcal
            const dash = fraction * circumference
            const el = (
              <circle
                key={p.key}
                cx="50"
                cy="50"
                r={radius}
                fill="none"
                stroke={p.color}
                strokeWidth="14"
                strokeDasharray={`${dash} ${circumference - dash}`}
                strokeDashoffset={-offset}
              />
            )
            offset += dash
            return el
          })}
        </svg>
        <ul className="w-full space-y-2 text-sm">
          {targets && (
            <li className="text-[11px] font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
              {t('macroDonut.comparedWithGoal')}
            </li>
          )}
          {parts.map((p) => {
            const actualPct = Math.round((p.kcal / totalKcal) * 100)
            const targetPct = targets?.[p.key]
            return (
              <li key={p.key}>
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-2 text-slate-600 dark:text-slate-300">
                    <span className="h-3 w-3 rounded-full" style={{ background: p.color }} />
                    {t(p.labelKey)}
                  </span>
                  <span className="text-right">
                    <span className="font-semibold text-slate-900 dark:text-slate-100">
                      {Math.round(totals[p.key] ?? 0)}g
                    </span>{' '}
                    <span className="text-xs text-slate-500 dark:text-slate-400">
                      {actualPct}%{targetPct != null && ` · ${t('macroDonut.targetShort', targetPct)}`}
                    </span>
                  </span>
                </div>
                {targetPct != null && (
                  <div className="relative mt-1 h-1.5 rounded-full bg-slate-100 dark:bg-slate-700">
                    <div
                      className="h-full rounded-full"
                      style={{ width: `${Math.min(actualPct, 100)}%`, background: p.color }}
                    />
                    <div
                      className="absolute top-0 h-1.5 w-0.5 bg-slate-500 dark:bg-slate-300"
                      style={{ left: `${Math.min(targetPct, 100)}%` }}
                    />
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      </div>

      {proteinDelta != null && Math.abs(proteinDelta) >= PROTEIN_INSIGHT_TOLERANCE && (
        <p
          className={`mt-3 text-xs font-medium ${
            proteinDelta > 0 ? 'text-grade-aplus dark:text-green-400' : 'text-amber-600 dark:text-amber-400'
          }`}
        >
          {proteinDelta > 0
            ? `✓ ${t('macroDonut.highProtein', proteinActualPct, proteinTargetPct)}`
            : `⚠ ${t('macroDonut.lowerProtein', proteinActualPct, proteinTargetPct)}`}
        </p>
      )}
    </div>
  )
}
