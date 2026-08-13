import { useCallback, useState } from 'react'
import AccordionSection from './AccordionSection.jsx'
import FoodCard from './FoodCard.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { TIER_ORDER, TIER_LABELS, TIER_COLORS, TIER_CELL_BG } from '../tierMeta.js'
import { buildMenuShareCard } from '../shareCard.js'
import { ShareButton, ShareIconButton, useShareCard } from './ShareControls.jsx'


export default function MenuResultsScreen({ result, onScanAnother, actionLabel, banner, shareImageSource }) {
  const { t } = useLanguage()
  const [howItWorksOpen, setHowItWorksOpen] = useState(false)
  // { key, dish } — which dish's nutrition is open below the table.
  const [selected, setSelected] = useState(null)
  const { tiers, addOns, dishCount, truncated, relative } = result

  // tiers[].label comes from the backend (TierMapping), but the frontend's own
  // TIER_LABELS is the source of truth for display — keeping both in sync is
  // the same convention GRADE_COLORS/tailwind.config.js already uses.
  const byTier = Object.fromEntries(tiers.map((group) => [group.tier, group]))

  // The card is drawn from the tier list rather than a score, so the colours and
  // labels come from tierMeta — the same source the screen itself renders from,
  // so the shared image and the screen can never disagree about a tier.
  const buildCard = useCallback(() => buildMenuShareCard({
    tierRows: TIER_ORDER.map((tier) => ({
      label: TIER_LABELS[tier],
      colour: TIER_COLORS.light[tier],
      count: byTier[tier]?.dishes?.length ?? 0,
      dishes: (byTier[tier]?.dishes ?? []).map((d) => d.name),
    })),
    dishCount,
    imageSource: shareImageSource,
    brandTitle: `${t('app.title1')}${t('app.title2')}`,
    shareText: t('menuResults.shareText', dishCount),
    dishCountLabel: t('menuResults.dishCount', dishCount),
  }), [byTier, dishCount, shareImageSource, t])
  const { state: shareState, share } = useShareCard(buildCard, 'duabiskuttelur-menu.png')

  return (
    <div className="space-y-5">
      {/* Caller decides when this is warranted (a genuinely-expired session),
          same as ResultsScreen's banner prop — result.persisted === false is
          ALSO true for a plain first-time visitor who was never signed in,
          who shouldn't see a "your sign-in expired" message. */}
      {banner && (
        <div className="rounded-xl bg-amber-50 px-4 py-3 text-center text-sm text-amber-700 dark:bg-amber-900/20 dark:text-amber-400">
          {banner}
        </div>
      )}

      {/* Same placement as the meal report: beside the headline result, in view
          the moment the scan lands, rather than past every tier. */}
      <section className="relative rounded-2xl border border-slate-200 bg-white p-4 pr-14 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <ShareIconButton state={shareState} onShare={share} />
        <p className="text-sm font-semibold text-slate-800 dark:text-slate-100">{t('menuResults.dishCount', dishCount)}</p>
        {truncated && (
          <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{t('menuResults.truncatedNotice')}</p>
        )}
        {relative && (
          <p className="mt-2 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-700 dark:bg-amber-900/20 dark:text-amber-400">
            {t('menuResults.relativeNotice')}
          </p>
        )}
      </section>

      {/* The tier list proper: every dish name is visible in its own tier row
          without tapping anything, matching the reference tier-list layout.
          Tapping a name only opens that dish's nutrition below the table, so
          the rows never reflow underneath the reader's finger. */}
      <div className="overflow-hidden rounded-2xl border border-slate-200 shadow-sm dark:border-slate-700">
        {TIER_ORDER.map((tier) => {
          const dishes = byTier[tier]?.dishes ?? []
          return (
            <div key={tier} className="flex border-b border-slate-200 last:border-b-0 dark:border-slate-700">
              <div
                className="flex w-[72px] shrink-0 items-center justify-center px-1 py-3"
                style={{ backgroundColor: TIER_CELL_BG[tier] }}
              >
                <span className="text-center text-base font-black leading-tight text-white">
                  {TIER_LABELS[tier]}
                </span>
              </div>
              <div className="flex min-h-[60px] flex-1 flex-wrap content-center gap-1.5 bg-white p-2 dark:bg-slate-800">
                {dishes.length === 0 ? (
                  <span className="self-center px-1 text-xs italic text-slate-400 dark:text-slate-500">
                    {t('menuResults.emptyTier')}
                  </span>
                ) : (
                  dishes.map((dish, i) => {
                    const key = `${tier}-${i}`
                    const isSelected = selected?.key === key
                    return (
                      <button
                        key={key}
                        onClick={() => setSelected(isSelected ? null : { key, dish })}
                        className={`rounded-lg border px-2.5 py-1.5 text-left text-xs font-semibold transition active:scale-[0.97] ${
                          isSelected
                            ? 'border-grade-aplus bg-green-50 text-grade-aplus dark:border-green-400 dark:bg-green-900/20 dark:text-green-400'
                            : 'border-slate-200 bg-slate-50 text-slate-700 dark:border-slate-600 dark:bg-slate-700/50 dark:text-slate-200'
                        }`}
                      >
                        {/* Dishes are still ordered healthiest-first within a tier
                            (see MenuDish.rank); the position carries that without
                            numbering every chip. */}
                        {dish.name}
                      </button>
                    )
                  })
                )}
              </div>
            </div>
          )
        })}
      </div>

      {selected ? (
        // Keyed so each pick remounts the card and opens fresh on its macros
        // rather than inheriting the previous dish's expanded/collapsed state.
        <FoodCard key={selected.key} food={selected.dish.nutrition} defaultOpen />
      ) : (
        <p className="px-1 text-center text-xs text-slate-500 dark:text-slate-400">{t('menuResults.tapHint')}</p>
      )}

      {/* Sides, condiments and drinks: shown for reference but deliberately
          left out of the tiers above, since they aren't alternatives to a
          main — "sambal vs nasi lemak" isn't a choice anyone is making. */}
      {addOns?.length > 0 && (
        <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <h2 className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {t('menuResults.addOnsTitle')}
          </h2>
          <p className="mt-1 text-xs text-slate-400 dark:text-slate-500">{t('menuResults.addOnsNote')}</p>
          <div className="mt-3 flex flex-wrap gap-1.5">
            {addOns.map((dish, i) => {
              const key = `addon-${i}`
              const isSelected = selected?.key === key
              return (
                <button
                  key={key}
                  onClick={() => setSelected(isSelected ? null : { key, dish })}
                  className={`rounded-lg border px-2.5 py-1.5 text-left text-xs font-semibold transition active:scale-[0.97] ${
                    isSelected
                      ? 'border-grade-aplus bg-green-50 text-grade-aplus dark:border-green-400 dark:bg-green-900/20 dark:text-green-400'
                      : 'border-slate-200 bg-slate-50 text-slate-600 dark:border-slate-600 dark:bg-slate-700/50 dark:text-slate-300'
                  }`}
                >
                  {dish.name}
                </button>
              )
            })}
          </div>
        </section>
      )}

      <AccordionSection
        title={t('menuResults.howTiersWork.title')}
        isOpen={howItWorksOpen}
        onToggle={() => setHowItWorksOpen((v) => !v)}
      >
        <p className="text-xs leading-relaxed text-slate-500 dark:text-slate-400">
          {t('menuResults.howTiersWork.body')}
        </p>
      </AccordionSection>

      <ShareButton state={shareState} onShare={share} />

      <button
        onClick={onScanAnother}
        // Flex so an actionLabel carrying a drawn icon lines up with its text
        // instead of sitting on the baseline.
        className="flex w-full items-center justify-center gap-2 rounded-2xl bg-grade-aplus py-3.5 text-sm font-bold text-white shadow-md active:scale-[0.98]"
      >
        {actionLabel || `📋 ${t('menuResults.scanAnother')}`}
      </button>
    </div>
  )
}
