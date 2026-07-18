import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import en from './en.js'
import zh from './zh.js'
import ms from './ms.js'

export const LANGUAGES = [
  { code: 'en', label: 'English' },
  { code: 'zh', label: '中文' },
  { code: 'ms', label: 'Bahasa Melayu' },
]

const DICTS = { en, zh, ms }

function detectDefaultLanguage() {
  const saved = localStorage.getItem('language')
  if (saved && DICTS[saved]) return saved
  const nav = (navigator.language || 'en').toLowerCase()
  if (nav.startsWith('zh')) return 'zh'
  if (nav.startsWith('ms')) return 'ms'
  return 'en'
}

/** Reads a dotted path ("profile.settings") out of a dictionary object. */
function resolve(dict, path) {
  return path.split('.').reduce((node, key) => node?.[key], dict)
}

// Exported so the class-based ErrorBoundary (which can't use the useLanguage
// hook) can still render translated text via LanguageContext.Consumer.
export const LanguageContext = createContext(null)

export function LanguageProvider({ children }) {
  const [lang, setLangState] = useState(detectDefaultLanguage)

  const setLang = useCallback((code) => {
    if (!DICTS[code]) return
    localStorage.setItem('language', code)
    setLangState(code)
  }, [])

  const t = useCallback(
    (path, ...args) => {
      const value = resolve(DICTS[lang], path) ?? resolve(DICTS.en, path)
      if (typeof value === 'function') return value(...args)
      return value ?? path
    },
    [lang],
  )

  // Screen readers pick voice/pronunciation from <html lang> — without this it
  // stays "en" forever and Chinese/Malay content gets read with English phonemes.
  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])

  const value = useMemo(() => ({ lang, setLang, t }), [lang, setLang, t])

  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>
}

export function useLanguage() {
  const ctx = useContext(LanguageContext)
  if (!ctx) throw new Error('useLanguage must be used within a LanguageProvider')
  return ctx
}
