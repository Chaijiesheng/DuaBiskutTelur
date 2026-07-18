import { useLanguage, LANGUAGES } from '../i18n/LanguageContext.jsx'
import { useTheme } from '../theme/ThemeContext.jsx'

const pillClass = (active) =>
  `flex-1 rounded-xl border py-2 text-xs font-semibold transition ${
    active
      ? 'border-grade-aplus bg-green-50 text-grade-aplus dark:bg-green-900/30 dark:text-green-400'
      : 'border-slate-200 text-slate-500 dark:border-slate-600 dark:text-slate-400'
  }`

/**
 * The language and theme controls used to be copy-pasted across the sign-in
 * screen, the visitor profile, and the settings accordion, each styled a
 * little differently (see audit finding U6). One shared control now backs
 * all three; `showLabels` adds the "Language"/"Appearance" captions used in
 * Settings but skipped in the more compact sign-in/visitor-profile contexts.
 */
export default function LanguageThemePicker({ showLabels = false }) {
  const { t, lang, setLang } = useLanguage()
  const { theme, setTheme } = useTheme()

  return (
    <div>
      {showLabels && (
        <p className="mb-1.5 text-xs font-semibold text-slate-700 dark:text-slate-300">{t('profile.language')}</p>
      )}
      <div className="flex gap-1.5">
        {LANGUAGES.map((l) => (
          <button key={l.code} onClick={() => setLang(l.code)} className={pillClass(lang === l.code)}>
            {l.label}
          </button>
        ))}
      </div>

      {showLabels && (
        <p className="mb-1.5 mt-4 text-xs font-semibold text-slate-700 dark:text-slate-300">{t('profile.appearance')}</p>
      )}
      <div className={`flex gap-1.5 ${showLabels ? '' : 'mt-2'}`}>
        <button onClick={() => setTheme('light')} className={pillClass(theme === 'light')}>
          ☀️ {t('profile.light')}
        </button>
        <button onClick={() => setTheme('dark')} className={pillClass(theme === 'dark')}>
          🌙 {t('profile.dark')}
        </button>
      </div>
    </div>
  )
}
