import { useLanguage } from '../i18n/LanguageContext.jsx'

/** Polite Add-to-Home-Screen sheet, shown once after the first successful analysis. */
export default function InstallPrompt({ installEvent, onDone }) {
  const { t } = useLanguage()
  const install = async () => {
    installEvent.prompt()
    await installEvent.userChoice.catch(() => {})
    onDone()
  }

  return (
    <div className="fixed inset-x-0 bottom-[calc(4rem+env(safe-area-inset-bottom))] z-20 mx-auto max-w-md px-4">
      <div className="flex items-center gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-xl dark:border-slate-700 dark:bg-slate-800">
        <span className="text-3xl">📲</span>
        <div className="flex-1">
          <p className="text-sm font-semibold text-slate-900 dark:text-slate-100">{t('install.title')}</p>
          <p className="text-xs text-slate-500 dark:text-slate-400">{t('install.body')}</p>
        </div>
        <button onClick={onDone} className="px-2 py-1 text-xs font-medium text-slate-500 dark:text-slate-400">
          {t('install.later')}
        </button>
        <button
          onClick={install}
          className="rounded-lg bg-grade-aplus px-3 py-1.5 text-xs font-bold text-white"
        >
          {t('install.add')}
        </button>
      </div>
    </div>
  )
}
