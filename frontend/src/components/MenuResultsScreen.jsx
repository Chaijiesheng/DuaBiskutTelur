import { useCallback, useState } from 'react'
import AccordionSection from './AccordionSection.jsx'
import FoodCard from './FoodCard.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { useTheme } from '../theme/ThemeContext.jsx'
import { TIER_ORDER, TIER_LABELS, TIER_COLORS } from '../tierMeta.js'
import { buildMenuShareCard } from '../shareCard.js'
import { ShareButton, ShareIconButton, useShareCard } from './ShareControls.jsx'

// Top 2 tiers open by default so a scan immediately shows something without
// every tier needing a tap; the rest start collapsed like a long menu would.
const DEFAULT_OPEN = Object.fromEntries(TIER_ORDER.map((tier, i) => [tier, i < 2]))

export default function MenuResultsScreen({ result, onScanAnother, actionLabel, banner, shareImageSource }) {
  const { t } = useLanguage()
  const { theme } = useTheme()
  const [openTiers, setOpenTiers] = useState(DEFAULT_OPEN)
  const [howItWorksOpen, setHowItWorksOpen] = useState(false)
  const { tiers, dishCount, truncated } = result

  const toggle = (tier) => setOpenTiers((prev) => ({ ...prev, [tier]: !prev[tier] }))

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
      </section>

      {TIER_ORDER.map((tier) => {
        const group = byTier[tier]
        const dishes = group?.dishes ?? []
        return (
          <AccordionSection
            key={tier}
            title={
              <span style={{ color: TIER_COLORS[theme][tier] }} className="text-lg font-black normal-case tracking-normal">
                {TIER_LABELS[tier]}
              </span>
            }
            badge={<span className="text-xs font-semibold text-slate-500 dark:text-slate-400">{dishes.length}</span>}
            isOpen={openTiers[tier]}
            onToggle={() => toggle(tier)}
          >
            {dishes.length === 0 ? (
              <p className="text-xs italic text-slate-400 dark:text-slate-500">{t('menuResults.emptyTier')}</p>
            ) : (
              <div className="space-y-2">
                {dishes.map((dish, i) => (
                  <FoodCard key={`${dish.name}-${i}`} food={dish.nutrition} />
                ))}
              </div>
            )}
          </AccordionSection>
        )
      })}

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
