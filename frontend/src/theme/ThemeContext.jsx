import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

function detectDefaultTheme() {
  const saved = localStorage.getItem('theme')
  if (saved === 'light' || saved === 'dark') return saved
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const ThemeContext = createContext(null)

export function ThemeProvider({ children }) {
  const [theme, setThemeState] = useState(detectDefaultTheme)

  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
    // Keep the browser/PWA chrome (status bar in standalone mode) in step with
    // the in-app theme toggle — the static meta in index.html only covers the
    // pre-React first paint. Light matches the brand green, dark matches the
    // slate-900 shell.
    document
      .querySelector('meta[name="theme-color"]')
      ?.setAttribute('content', theme === 'dark' ? '#0f172a' : '#15803d')
  }, [theme])

  const setTheme = useCallback((next) => {
    if (next !== 'light' && next !== 'dark') return
    localStorage.setItem('theme', next)
    setThemeState(next)
  }, [])

  const toggleTheme = useCallback(() => {
    setThemeState((prev) => {
      const next = prev === 'dark' ? 'light' : 'dark'
      localStorage.setItem('theme', next)
      return next
    })
  }, [])

  const value = useMemo(() => ({ theme, setTheme, toggleTheme }), [theme, setTheme, toggleTheme])

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  const ctx = useContext(ThemeContext)
  if (!ctx) throw new Error('useTheme must be used within a ThemeProvider')
  return ctx
}
