import { Component } from 'react'
import { LanguageContext } from './i18n/LanguageContext.jsx'

/**
 * Catches render-time exceptions anywhere below it so one broken screen
 * doesn't blank the whole app. Must be a class component — React has no
 * hook-based error boundary API — so translated text goes through
 * LanguageContext.Consumer rather than useLanguage.
 */
export default class ErrorBoundary extends Component {
  state = { hasError: false }

  static getDerivedStateFromError() {
    return { hasError: true }
  }

  componentDidCatch(error, info) {
    console.error('Unhandled render error:', error, info)
  }

  render() {
    if (!this.state.hasError) return this.props.children

    return (
      <LanguageContext.Consumer>
        {(ctx) => {
          const t = ctx?.t ?? ((path) => ({ 'errorBoundary.title': 'Something broke', 'errorBoundary.body': "This screen hit a snag and couldn't recover. Reloading usually fixes it.", 'errorBoundary.reload': 'Reload' })[path])
          return (
            <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-slate-50 px-6 text-center dark:bg-slate-900">
              <span className="text-5xl">🥲</span>
              <h1 className="text-lg font-bold text-slate-900 dark:text-slate-100">{t('errorBoundary.title')}</h1>
              <p className="max-w-xs text-sm text-slate-500 dark:text-slate-400">{t('errorBoundary.body')}</p>
              <button
                onClick={() => window.location.reload()}
                className="rounded-2xl bg-grade-aplus px-6 py-3 text-sm font-bold text-white shadow-md active:scale-[0.98]"
              >
                {t('errorBoundary.reload')}
              </button>
            </div>
          )
        }}
      </LanguageContext.Consumer>
    )
  }
}
