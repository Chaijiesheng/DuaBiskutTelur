import { googleLoginUrl } from '../api.js'
import { GoogleG } from './AccountMenu.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import LanguageThemePicker from './LanguageThemePicker.jsx'

/** First-run prompt shown before the profile screen — signing in is optional. */
export default function SignInScreen({ onSkip }) {
  const { t } = useLanguage()
  return (
    <div className="flex flex-col items-center gap-5 pt-16 text-center">
      <div className="w-full max-w-xs">
        <LanguageThemePicker />
      </div>
      <span className="text-5xl">🍽️</span>
      <div>
        <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">{t('signIn.heading')}</h2>
        <p className="mx-auto mt-1 max-w-xs text-xs text-slate-500 dark:text-slate-400">{t('signIn.body')}</p>
      </div>

      <a
        href={googleLoginUrl()}
        className="flex w-full max-w-xs items-center justify-center gap-2 rounded-2xl border border-slate-300 bg-white py-3 text-sm font-semibold text-slate-700 shadow-sm active:scale-[0.98] dark:border-slate-600 dark:bg-slate-800 dark:text-slate-300"
      >
        <GoogleG /> {t('signIn.cta')}
      </a>

      <button
        type="button"
        onClick={onSkip}
        className="text-[11px] font-normal text-slate-600 hover:text-slate-500 hover:underline dark:text-slate-300 dark:hover:text-slate-500"
      >
        {t('signIn.skip')}
      </button>
    </div>
  )
}
