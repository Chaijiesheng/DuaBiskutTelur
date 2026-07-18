import { useEffect } from 'react'
import confetti from 'canvas-confetti'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { prefersReducedMotion } from '../motionPreference.js'
import Dialog from './Dialog.jsx'

/**
 * Full-screen celebration shown once per newly-unlocked badge, queued one at
 * a time by the caller. Tapping anywhere dismisses and advances the queue.
 */
export default function AchievementUnlockOverlay({ badge, onDismiss }) {
  const { t } = useLanguage()
  useEffect(() => {
    if (prefersReducedMotion()) return
    confetti({ particleCount: 120, spread: 80, origin: { y: 0.35 } })
  }, [badge.id])

  return (
    <Dialog
      onClose={onDismiss}
      ariaLabel={`${t('achievementUnlock.unlocked')} ${badge.label}`}
      closeOnBackdrop
      overlayClassName="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-6"
      panelClassName="animate-stamp w-full max-w-xs rounded-3xl border-4 border-grade-aplus bg-white p-6 text-center shadow-xl dark:bg-slate-800"
    >
      <p className="text-xs font-bold uppercase tracking-wide text-grade-aplus dark:text-green-400">
        {t('achievementUnlock.unlocked')}
      </p>
      <span className="mt-3 block text-6xl">{badge.icon}</span>
      <p className="mt-2 text-lg font-black text-slate-900 dark:text-slate-100">{badge.label}</p>
      {badge.description && (
        <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">{badge.description}</p>
      )}
      <button
        type="button"
        onClick={onDismiss}
        className="mt-5 w-full rounded-2xl bg-grade-aplus py-2.5 text-sm font-semibold text-white"
      >
        {t('achievementUnlock.nice')}
      </button>
    </Dialog>
  )
}
