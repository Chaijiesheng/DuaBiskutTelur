import { useLanguage } from '../i18n/LanguageContext.jsx'
import Dialog from './Dialog.jsx'

/** Enlarged detail view for an already-unlocked badge; description only ever reaches here once unlocked. */
export default function AchievementDetailModal({ badge, onClose }) {
  const { t } = useLanguage()
  return (
    <Dialog
      onClose={onClose}
      ariaLabel={badge.label}
      closeOnBackdrop
      overlayClassName="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-6"
      panelClassName="w-full max-w-xs rounded-3xl border border-slate-200 bg-white p-6 text-center shadow-xl dark:border-slate-700 dark:bg-slate-800"
    >
      {badge.secret && (
        <p className="text-[10px] font-bold uppercase tracking-wide text-purple-500 dark:text-purple-400">
          {t('achievementModal.secretBadge')}
        </p>
      )}
      <span className="mt-2 block text-6xl">{badge.icon}</span>
      <p className="mt-2 text-lg font-black text-slate-900 dark:text-slate-100">{badge.label}</p>
      <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">{badge.description}</p>
      {badge.xp != null && (
        <p className="mt-3 text-xs font-semibold text-slate-500 dark:text-slate-400">+{badge.xp} XP</p>
      )}
      <button
        type="button"
        onClick={onClose}
        className="mt-5 w-full rounded-2xl border border-slate-300 py-2.5 text-sm font-semibold text-slate-700 dark:border-slate-600 dark:text-slate-300"
      >
        {t('achievementModal.close')}
      </button>
    </Dialog>
  )
}
