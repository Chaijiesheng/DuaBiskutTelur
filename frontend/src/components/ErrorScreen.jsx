import { useLanguage } from '../i18n/LanguageContext.jsx'

const EMOJI = { NO_FOOD_DETECTED: '🔍', ANALYZER_BUSY: '⏳', NETWORK: '📡', BARCODE_NOT_FOUND: '🔖', DEFAULT: '😅' }

export default function ErrorScreen({ error, onRetry, onBack }) {
  const { t } = useLanguage()
  const code = EMOJI[error?.code] ? error.code : 'DEFAULT'

  return (
    <div className="flex flex-col items-center gap-4 pt-20 text-center">
      <span className="text-6xl">{EMOJI[code]}</span>
      <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">{t(`error.${code}.title`)}</h2>
      <p className="max-w-xs text-sm text-slate-500 dark:text-slate-400">{t(`error.${code}.body`)}</p>
      <div className="mt-2 flex gap-3">
        <button
          onClick={onBack}
          className="rounded-xl border border-slate-300 px-5 py-2.5 text-sm font-semibold text-slate-600 dark:border-slate-600 dark:text-slate-300"
        >
          {t('error.back')}
        </button>
        <button
          onClick={onRetry}
          className="rounded-xl bg-grade-aplus px-5 py-2.5 text-sm font-semibold text-white shadow"
        >
          {t('error.tryAgain')}
        </button>
      </div>
    </div>
  )
}
