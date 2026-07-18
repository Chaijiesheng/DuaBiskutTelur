/**
 * A single collapsible MD3-style card section. The whole header (text or
 * chevron) toggles it; content height animates via a CSS grid-rows trick
 * (0fr -> 1fr) so it doesn't need JS to measure content height.
 */
export default function AccordionSection({ title, badge, isOpen, onToggle, children }) {
  return (
    <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={isOpen}
        className="flex w-full items-center justify-between gap-3 p-4 text-left"
      >
        <span className="text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">{title}</span>
        <span className="flex items-center gap-2">
          {badge}
          <svg
            viewBox="0 0 20 20"
            fill="currentColor"
            className={`h-4 w-4 shrink-0 text-slate-500 dark:text-slate-400 transition-transform duration-300 ${
              isOpen ? 'rotate-180' : ''
            }`}
          >
            <path
              fillRule="evenodd"
              d="M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.168l3.71-3.938a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z"
              clipRule="evenodd"
            />
          </svg>
        </span>
      </button>
      {/* The 0fr collapse is visual-only — without inert/aria-hidden the closed
          content stays in the accessibility tree and its buttons stay in the
          tab order (focus lands on invisible controls). inert='' is the
          React-18 spelling for a bare boolean attribute. */}
      <div
        inert={isOpen ? undefined : ''}
        aria-hidden={!isOpen}
        className={`grid transition-[grid-template-rows] duration-300 ease-in-out ${
          isOpen ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]'
        }`}
      >
        <div className="overflow-hidden">
          <div className="px-4 pb-4">{children}</div>
        </div>
      </div>
    </section>
  )
}
