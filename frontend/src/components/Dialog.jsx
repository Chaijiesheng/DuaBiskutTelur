/* eslint-disable jsx-a11y/click-events-have-key-events,
                  jsx-a11y/no-static-element-interactions,
                  jsx-a11y/no-noninteractive-element-interactions --
   Two click handlers below trip these rules, and both are deliberate:

   - the overlay closes on a backdrop click. That is a convenience, not the only
     way out — Escape is bound in the effect below and focus is trapped in the
     panel, so keyboard users already have a close path that doesn't involve
     clicking a div. Wrapping the backdrop in a <button> to satisfy the rule
     would mean an absolutely-positioned element behind the panel in every
     caller's custom overlayClassName, which is more to get wrong than it fixes.
   - the panel's handler is not an interaction at all. It stops a click inside
     the dialog from bubbling to the backdrop and closing it.

   Scoped to this file, which exists only to be the modal shell. */
import { useEffect, useRef } from 'react'

const FOCUSABLE = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])'

/**
 * Shared modal shell: role=dialog/aria-modal, focuses the first control on
 * open, traps Tab inside the panel, closes on Escape, and restores focus to
 * whatever triggered it on close. Callers keep full control of visual style
 * via overlayClassName/panelClassName so each modal's existing look (z-index,
 * backdrop color, backdrop-click behavior) doesn't change — only the
 * previously-missing keyboard/screen-reader semantics are added.
 */
export default function Dialog({
  onClose,
  children,
  ariaLabel,
  closeOnBackdrop = false,
  overlayClassName,
  panelClassName,
}) {
  const panelRef = useRef(null)
  const previouslyFocused = useRef(null)

  useEffect(() => {
    previouslyFocused.current = document.activeElement
    const panel = panelRef.current
    const focusable = panel?.querySelectorAll(FOCUSABLE)
    ;(focusable?.[0] ?? panel)?.focus()

    function handleKeyDown(e) {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose?.()
        return
      }
      if (e.key !== 'Tab' || !panel) return
      const items = panel.querySelectorAll(FOCUSABLE)
      if (items.length === 0) return
      const first = items[0]
      const last = items[items.length - 1]
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      previouslyFocused.current?.focus?.()
    }
  }, [onClose])

  return (
    <div
      className={overlayClassName}
      onClick={closeOnBackdrop ? onClose : undefined}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className={panelClassName}
      >
        {children}
      </div>
    </div>
  )
}
