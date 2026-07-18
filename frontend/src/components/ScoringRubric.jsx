import { useState } from 'react'
import AccordionSection from './AccordionSection.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'

const FACTORS = ['balance', 'quality', 'portion', 'variety']
const ICONS = { balance: '⚖️', quality: '🥗', portion: '🍽️', variety: '🌈' }

/**
 * "How grading works" disclosure — the grade used to be a bare letter with no
 * explanation of the four components behind it (see audit finding U5).
 * `breakdown` is null for meals logged before this field existed; the
 * factors and their descriptions still show, just without this meal's split.
 */
export default function ScoringRubric({ breakdown }) {
  const { t } = useLanguage()
  const [isOpen, setIsOpen] = useState(false)

  return (
    <AccordionSection title={t('scoringRubric.title')} isOpen={isOpen} onToggle={() => setIsOpen((v) => !v)}>
      <div className="space-y-3">
        {FACTORS.map((key) => {
          const points = breakdown?.[key]
          const max = breakdown?.[`${key}Max`]
          const fraction = max > 0 ? Math.min(1, Math.max(0, points / max)) : 0
          return (
            <div key={key}>
              <div className="flex items-center justify-between gap-2 text-sm">
                <span className="font-semibold text-slate-700 dark:text-slate-300">
                  {ICONS[key]} {t(`scoringRubric.${key}.label`)}
                </span>
                {breakdown && (
                  <span className="shrink-0 text-xs font-semibold text-slate-500 dark:text-slate-400">
                    {Math.round(points)} / {max}
                  </span>
                )}
              </div>
              <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">{t(`scoringRubric.${key}.body`)}</p>
              {breakdown && (
                <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700">
                  <div
                    className="h-full rounded-full bg-grade-aplus transition-all duration-500"
                    style={{ width: `${fraction * 100}%` }}
                  />
                </div>
              )}
            </div>
          )
        })}
      </div>
    </AccordionSection>
  )
}
