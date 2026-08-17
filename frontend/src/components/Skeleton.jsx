/**
 * Loading placeholders shaped like the content that is coming.
 *
 * Every async surface used to render one centred line of grey text, so on a
 * slow connection the History tab was a blank screen with "Loading…" in the
 * middle of it for several seconds. A placeholder that matches the real layout
 * makes the same wait read as "this is nearly here" rather than "nothing is
 * happening", and it stops the page jumping when the content lands.
 *
 * Two things these deliberately keep from the text they replace:
 *
 * - **The announcement.** The old "Loading…" line was real information for a
 *   screen reader. Grey boxes are not, so every skeleton carries the same
 *   string in an `sr-only` element under `role="status"`. Swapping visible text
 *   for decoration would have quietly made the app *worse* for the people least
 *   able to tell that something was still in flight.
 * - **A stable count.** Row counts are fixed, not random, so a skeleton never
 *   animates its own shape while waiting.
 *
 * `motion-safe:` gates the pulse: with reduced motion requested the blocks are
 * plain static grey, which still communicates "content goes here".
 */

const BLOCK = 'rounded bg-slate-200 motion-safe:animate-pulse dark:bg-slate-700'

/** One grey rectangle. `className` supplies the size and any shape override. */
export function SkeletonBlock({ className = '' }) {
  return <div aria-hidden="true" className={`${BLOCK} ${className}`} />
}

/**
 * Wraps a set of blocks with the status role and the label that used to be the
 * visible loading text.
 */
function SkeletonRegion({ label, className = '', children }) {
  return (
    <div role="status" aria-busy="true" className={className}>
      <span className="sr-only">{label}</span>
      {children}
    </div>
  )
}

/** One row of the meal or menu history list: thumbnail, two lines, grade chip. */
function ListRowSkeleton() {
  return (
    <li className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white p-3 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <SkeletonBlock className="h-12 w-12 shrink-0 rounded-lg" />
      <div className="min-w-0 flex-1 space-y-2">
        <SkeletonBlock className="h-3 w-3/4" />
        <SkeletonBlock className="h-2.5 w-1/2" />
      </div>
      <SkeletonBlock className="h-7 w-8 shrink-0 rounded-lg" />
    </li>
  )
}

/**
 * The meal history tab: the weekly chart sits above the list, so the chart's
 * space is held too — otherwise the list renders, then jumps down when the
 * chart arrives.
 */
export function HistoryListSkeleton({ label, rows = 5 }) {
  return (
    <SkeletonRegion label={label} className="space-y-4">
      <SkeletonBlock className="h-40 w-full rounded-2xl" />
      <ul className="space-y-2">
        {Array.from({ length: rows }, (_, i) => <ListRowSkeleton key={i} />)}
      </ul>
    </SkeletonRegion>
  )
}

/** The menu history tab — same rows, no chart above them. */
export function MenuListSkeleton({ label, rows = 4 }) {
  return (
    <SkeletonRegion label={label} className="space-y-4">
      <ul className="space-y-2">
        {Array.from({ length: rows }, (_, i) => <ListRowSkeleton key={i} />)}
      </ul>
    </SkeletonRegion>
  )
}

/** The Analysis tab: a section heading, a 2×2 stat grid, then the chart. */
export function AnalysisSkeleton({ label }) {
  return (
    <SkeletonRegion label={label} className="space-y-4 pt-2">
      <SkeletonBlock className="h-3 w-40" />
      <div className="grid grid-cols-2 gap-3">
        {Array.from({ length: 4 }, (_, i) => (
          <div
            key={i}
            className="space-y-2 rounded-2xl border border-slate-200 bg-white p-3 shadow-sm dark:border-slate-700 dark:bg-slate-800"
          >
            <SkeletonBlock className="h-6 w-6 rounded-full" />
            <SkeletonBlock className="h-2.5 w-2/3" />
            <SkeletonBlock className="h-5 w-1/2" />
          </div>
        ))}
      </div>
      <SkeletonBlock className="h-40 w-full rounded-2xl" />
    </SkeletonRegion>
  )
}

/**
 * The Workout tab while today's plan is being built.
 *
 * <p>Shaped like the plan card that is coming — kicker, big title, meta line,
 * primary button — because building a session is the one wait in this app that
 * is genuinely doing work rather than fetching a row, and a placeholder that
 * matches the answer makes it read as "nearly here".
 *
 * <p>Takes an optional visible title and phase line, unlike the other skeletons.
 * On a first-ever setup the wait follows six questions and deserves an
 * explanation; on a plain tab open it does not, and passing neither leaves just
 * the grey blocks.
 */
export function WorkoutPlanSkeleton({ label, title, phase }) {
  return (
    <SkeletonRegion label={label} className="space-y-3 pt-2">
      {title && (
        <div className="pb-2 pt-4 text-center">
          <span aria-hidden="true" className="text-4xl motion-safe:animate-pulse">🏋️</span>
          <h2 className="mt-3 text-base font-extrabold text-slate-900 dark:text-slate-100">{title}</h2>
          {phase && (
            <p className="mx-auto mt-1.5 max-w-[15rem] text-xs leading-relaxed text-slate-500 dark:text-slate-400">
              {phase}
            </p>
          )}
        </div>
      )}
      <div className="space-y-2.5 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <SkeletonBlock className="h-3 w-2/5" />
        <SkeletonBlock className="h-7 w-3/5 rounded-lg" />
        <SkeletonBlock className="h-2.5 w-1/2" />
        <SkeletonBlock className="h-12 w-full rounded-2xl" />
      </div>
      <SkeletonBlock className="h-20 w-full rounded-2xl" />
      <div className="grid grid-cols-3 gap-2">
        {Array.from({ length: 3 }, (_, i) => (
          <SkeletonBlock key={i} className="h-16 w-full rounded-2xl" />
        ))}
      </div>
    </SkeletonRegion>
  )
}

/** The achievements grid inside the Profile accordion. */
export function BadgeGridSkeleton({ label, count = 6 }) {
  return (
    <SkeletonRegion label={label}>
      <div className="grid grid-cols-3 gap-3">
        {Array.from({ length: count }, (_, i) => (
          <div key={i} className="flex flex-col items-center gap-2">
            <SkeletonBlock className="h-14 w-14 rounded-full" />
            <SkeletonBlock className="h-2.5 w-full" />
          </div>
        ))}
      </div>
    </SkeletonRegion>
  )
}

/**
 * Reopening a saved meal or menu. Deliberately coarse: the real screen varies a
 * lot by result, so a placeholder that guessed its exact structure would be
 * wrong more often than right. Holding the broad shape is the honest version.
 */
export function DetailSkeleton({ label }) {
  return (
    <SkeletonRegion label={label} className="space-y-4 pt-2">
      <SkeletonBlock className="h-28 w-28 rounded-full mx-auto" />
      <SkeletonBlock className="h-4 w-2/3 mx-auto" />
      <SkeletonBlock className="h-32 w-full rounded-2xl" />
      <SkeletonBlock className="h-24 w-full rounded-2xl" />
    </SkeletonRegion>
  )
}
