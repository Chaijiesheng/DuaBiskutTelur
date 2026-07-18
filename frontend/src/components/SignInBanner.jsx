import { googleLoginUrl } from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/** Reusable guest CTA — copy varies slightly per page it's used on. */
export default function SignInBanner({ text }) {
  const { t } = useLanguage()
  return (
    <a
      href={googleLoginUrl()}
      className="flex items-center justify-center gap-2 rounded-xl bg-green-50 px-4 py-3 text-center text-xs font-medium text-green-700 dark:bg-green-900/30 dark:text-green-400"
    >
      {text || t('signInBanner.default')}
    </a>
  )
}
