import { useCallback, useState } from 'react'
import { downloadBlob } from '../shareCard.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * The share affordances for a result screen: an icon beside the headline result
 * and a full-width button at the end of the report.
 *
 * Users reported having to scroll the whole report to find the share button —
 * measured at 1,354px below the grade on a phone, nearly two screens from the
 * moment anyone actually wants to show a friend. The icon fixes that; the
 * button stays for people who read first and decide after.
 *
 * Shared by the meal and menu screens rather than duplicated, because the two
 * differ only in which card they build.
 */

/**
 * One share flow, however many controls render it.
 *
 * Both controls must agree about whether a share is in flight — building the
 * card draws onto a canvas and takes long enough to see, so two independent
 * copies of this state would let the icon look idle while the button says it is
 * working.
 *
 * @param build async () => ({ blob, shareText }); wrap it in useCallback
 */
export function useShareCard(build, filename = 'duabiskuttelur-report.png') {
  const { t } = useLanguage()
  const [state, setState] = useState('idle') // idle | preparing | error

  const share = useCallback(async () => {
    setState('preparing')
    try {
      const { blob, shareText } = await build()
      const file = new File([blob], filename, { type: 'image/png' })
      const shareData = { files: [file], title: `${t('app.title1')}${t('app.title2')}`, text: shareText }
      if (navigator.canShare?.(shareData)) {
        await navigator.share(shareData)
      } else {
        downloadBlob(blob, filename)
      }
      setState('idle')
    } catch (e) {
      // The user backing out of the native share sheet isn't a failure.
      if (e?.name === 'AbortError') {
        setState('idle')
        return
      }
      setState('error')
    }
  }, [build, filename, t])

  return { state, share }
}

/**
 * The share glyph, drawn rather than typed.
 *
 * An emoji would be shorter, but this is a control rather than decoration:
 * emoji render differently on every platform, cannot inherit the disabled
 * colour, and are exactly what had to be hidden from screen readers elsewhere
 * in this app. `currentColor` lets one glyph serve any placement.
 */
export function ShareGlyph({ className = '' }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={className}>
      <circle cx="18" cy="5" r="3" />
      <circle cx="6" cy="12" r="3" />
      <circle cx="18" cy="19" r="3" />
      <path d="M8.6 13.5l6.8 4M15.4 6.5l-6.8 4" />
    </svg>
  )
}

function Spinner({ className = '' }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
         aria-hidden="true" className={`motion-safe:animate-spin ${className}`}>
      <circle cx="12" cy="12" r="9" opacity="0.25" />
      <path d="M21 12a9 9 0 0 0-9-9" strokeLinecap="round" />
    </svg>
  )
}

/**
 * Icon-only, so it carries its own name — and 44px of tappable area around a
 * 20px glyph, which is the minimum a thumb can reliably hit. Positioned by the
 * caller, which supplies the `relative` container.
 */
export function ShareIconButton({ state, onShare }) {
  const { t } = useLanguage()
  const preparing = state === 'preparing'

  return (
    <button
      type="button"
      onClick={onShare}
      disabled={preparing}
      aria-label={t('results.shareAria')}
      aria-busy={preparing}
      className="absolute right-0 top-2 flex h-11 w-11 items-center justify-center rounded-full text-slate-600 transition active:scale-95 disabled:opacity-50 dark:text-slate-300"
    >
      {preparing ? <Spinner className="h-5 w-5" /> : <ShareGlyph className="h-5 w-5" />}
    </button>
  )
}

/**
 * The full-width control at the end of the report. Not a duplicate of the icon
 * so much as the other half of the same intent — and it carries the error line,
 * which has room here and does not in a 44px circle.
 *
 * No ShareGlyph inside it: `results.share` already carries a 📤 in all three
 * languages, and the two together read as a stutter.
 */
export function ShareButton({ state, onShare }) {
  const { t } = useLanguage()

  return (
    <div>
      <button
        onClick={onShare}
        disabled={state === 'preparing'}
        className="w-full rounded-2xl border border-slate-300 py-3 text-sm font-semibold text-slate-700 disabled:opacity-60 dark:border-slate-600 dark:text-slate-300"
      >
        {state === 'preparing' ? t('results.preparingShare') : t('results.share')}
      </button>
      {state === 'error' && (
        <p role="alert" className="mt-1.5 text-center text-xs text-red-500 dark:text-red-400">
          {t('results.shareError')}
        </p>
      )}
    </div>
  )
}
