import { foodEquivalents } from '../foodEquivalents.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/** Turns a raw kcal number into relatable "~7 plates of nasi lemak"-style examples. */
export default function FoodEquivalents({ calories }) {
  const { t } = useLanguage()
  const equivalents = foodEquivalents(calories)
  if (equivalents.length === 0) return null

  return (
    <div className="mt-2 flex flex-wrap justify-center gap-1.5">
      {equivalents.map((food) => (
        <span
          key={food.labelKey}
          className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-600 dark:bg-slate-700 dark:text-slate-300"
        >
          {food.emoji} ~{food.count} {t(`foodEquivalents.${food.labelKey}`)}
        </span>
      ))}
    </div>
  )
}
