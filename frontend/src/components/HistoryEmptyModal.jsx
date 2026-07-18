import { useMemo } from 'react'
import { HISTORY_EMPTY_MESSAGES } from '../historyEmptyMessages.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import Dialog from './Dialog.jsx'

/** Pop-out shown over the History tab when there's nothing to show yet. */
export default function HistoryEmptyModal({ onClose }) {
  const { lang } = useLanguage()
  const messages = HISTORY_EMPTY_MESSAGES[lang] || HISTORY_EMPTY_MESSAGES.en
  // Picked once per mount so it doesn't change while the modal is open.
  const entry = useMemo(() => messages[Math.floor(Math.random() * messages.length)], [messages])

  return (
    <Dialog
      onClose={onClose}
      ariaLabel={entry.message}
      overlayClassName="fixed inset-0 z-30 flex items-center justify-center bg-black/40 px-6"
      panelClassName="w-full max-w-xs rounded-3xl bg-white p-6 text-center shadow-xl dark:bg-slate-800"
    >
      <p className="text-sm font-medium leading-relaxed text-slate-700 dark:text-slate-300">{entry.message}</p>
      <button
        onClick={onClose}
        className="mt-5 w-full rounded-2xl bg-grade-aplus py-3 text-sm font-bold text-white shadow-md active:scale-[0.98]"
      >
        {entry.cta}
      </button>
    </Dialog>
  )
}
