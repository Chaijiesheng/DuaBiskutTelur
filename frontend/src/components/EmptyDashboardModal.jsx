import { useMemo } from 'react'
import { EMPTY_STATE_JOKES } from '../emptyStateJokes.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import Dialog from './Dialog.jsx'

/** One-time nudge shown right after login when today's dashboard has no meals yet. */
export default function EmptyDashboardModal({ onClose }) {
  const { lang } = useLanguage()
  const jokes = EMPTY_STATE_JOKES[lang] || EMPTY_STATE_JOKES.en
  // Picked once per mount so it doesn't change while the modal is open.
  const entry = useMemo(() => jokes[Math.floor(Math.random() * jokes.length)], [jokes])
  const message = entry.message.replace(/^🤔\s*/, '')

  return (
    <Dialog
      onClose={onClose}
      ariaLabel={message}
      overlayClassName="fixed inset-0 z-30 flex items-center justify-center bg-black/40 px-6"
      panelClassName="w-full max-w-xs rounded-3xl bg-white p-6 text-center shadow-xl dark:bg-slate-800"
    >
      <span className="text-4xl">🤔</span>
      <p className="mt-3 text-sm font-medium leading-relaxed text-slate-700 dark:text-slate-300">{message}</p>
      <button
        onClick={onClose}
        className="mt-5 w-full rounded-2xl bg-grade-aplus py-3 text-sm font-bold text-white shadow-md active:scale-[0.98]"
      >
        {entry.cta}
      </button>
    </Dialog>
  )
}
