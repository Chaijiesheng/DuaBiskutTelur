import { useCallback, useEffect, useRef, useState } from 'react'
import { Route, Routes, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  deleteHistoryEntry, exportHistoryPdf, fetchHistoryDetail,
  deleteMenuHistoryEntry, fetchMenuHistory, fetchMenuHistoryDetail,
} from '../api.js'
import { useHistory } from '../history/HistoryContext.jsx'
import { GRADE_COLORS } from './GradeReveal.jsx'
import ResultsScreen from './ResultsScreen.jsx'
import MenuResultsScreen from './MenuResultsScreen.jsx'
import WorkoutHistory from './WorkoutHistory.jsx'
import { ShareGlyph, useShareCard } from './ShareControls.jsx'
import { buildShareCard } from '../shareCard.js'
import HistoryEmptyModal from './HistoryEmptyModal.jsx'
import Dialog from './Dialog.jsx'
import SignInBanner from './SignInBanner.jsx'
import WeeklyCaloriesChart from './WeeklyCaloriesChart.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { useTheme } from '../theme/ThemeContext.jsx'
import { HistoryListSkeleton, MenuListSkeleton, DetailSkeleton } from './Skeleton.jsx'

const LOCALE_TAG = { en: 'en-US', zh: 'zh-CN', ms: 'ms-MY' }

export default function HistoryScreen(props) {
  return (
    <Routes>
      <Route index element={<HistoryList {...props} />} />
      {/* Detail views are real destinations: opening a meal and pressing back
          is the most common back press in the app, and it used to exit it. */}
      <Route path="meal/:id" element={<MealDetail {...props} />} />
      <Route path="menu/:id" element={<MenuDetail />} />
    </Routes>
  )
}

function HistoryList(props) {
  const { t } = useLanguage()
  // In the URL so returning from a menu detail lands back on the Menus tab
  // rather than silently resetting to Meals.
  const [searchParams, setSearchParams] = useSearchParams()
  // "meals" stays the bare URL rather than ?view=meals, so every link and
  // bookmark to /history that predates the other tabs still lands on meals.
  const requested = searchParams.get('view')
  const mode = requested === 'menus' || requested === 'workouts' ? requested : 'meals'
  const setMode = (next) =>
    setSearchParams(next === 'meals' ? {} : { view: next }, { replace: true })

  const tabClass = (active) =>
    `rounded-lg py-2 text-sm font-semibold transition ${
      active
        ? 'bg-white text-slate-900 shadow-sm dark:bg-slate-700 dark:text-slate-100'
        : 'text-slate-500 dark:text-slate-400'
    }`

  return (
    <div className="space-y-3 pt-2">
      {/* Three tabs now: workouts are the same question as meals and menus —
          "what did I do" — and answering it in two places means checking two. */}
      <div className="grid grid-cols-3 gap-2 rounded-xl bg-slate-100 p-1 dark:bg-slate-800">
        <button onClick={() => setMode('meals')} className={tabClass(mode === 'meals')}>
          {t('history.mealsTab')}
        </button>
        <button onClick={() => setMode('menus')} className={tabClass(mode === 'menus')}>
          {t('history.menusTab')}
        </button>
        <button onClick={() => setMode('workouts')} className={tabClass(mode === 'workouts')}>
          {t('workout.historyTab')}
        </button>
      </div>
      {mode === 'meals' && <MealsHistory {...props} />}
      {mode === 'menus' && <MenuHistory isVisitor={props.isVisitor} />}
      {mode === 'workouts' && <WorkoutHistory isVisitor={props.isVisitor} />}
    </div>
  )
}

function MealsHistory({ isVisitor, onDeleteVisitorEntry, dailyBudget }) {
  const { t, lang } = useLanguage()
  const { theme } = useTheme()
  const navigate = useNavigate()
  const gradeColors = GRADE_COLORS[theme]
  // One shared, cached copy — this screen and the Analysis tab used to fetch it
  // separately and again on every tab switch.
  const { entries, recent, loading, error, removeEntry } = useHistory()
  // Pop-out shown first when there's nothing to display; clicking its CTA
  // reveals the plain empty-history view underneath. Re-rolled each time the
  // tab is opened (component remounts on tab switch).
  const [emptyPromptClosed, setEmptyPromptClosed] = useState(false)
  const [confirmDeleteId, setConfirmDeleteId] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState(false)
  // The entry whose "⋮" sheet is open, or null. Holding the whole entry rather
  // than an id keeps the visitor share path (which needs entry.result) from
  // having to look it up again.
  const [actionsFor, setActionsFor] = useState(null)
  const [pdfState, setPdfState] = useState('idle') // idle | working | error

  /**
   * Share a row without opening it first.
   *
   * The list only holds a HistoryEntry — score, grade, calories, a thumbnail —
   * and the card needs the full analysis, so this fetches it. Visitors already
   * have theirs in memory and have no session to fetch with, the same split
   * MealDetail makes.
   */
  const buildCard = useCallback(async () => {
    const entry = actionsFor
    const result = isVisitor ? entry.result : await fetchHistoryDetail(entry.id)
    return buildShareCard({
      result,
      // Null for visitors; buildShareCard falls back to its own tile.
      imageSource: entry.thumbnail,
      brandTitle: `${t('app.title1')}${t('app.title2')}`,
      shareText: t('results.shareText', result.grade),
      barcodeLabel: t('results.verifiedFromBarcode'),
    })
  }, [actionsFor, isVisitor, t])

  const { state: shareState, share } = useShareCard(buildCard)

  // Close the sheet once the share resolves — success or the user backing out
  // of the OS share sheet both land on 'idle'. A failure stays 'error' so the
  // message has somewhere to be read.
  const previousShareState = useRef(shareState)
  useEffect(() => {
    if (previousShareState.current === 'preparing' && shareState === 'idle') {
      setActionsFor(null)
    }
    previousShareState.current = shareState
  }, [shareState])

  const handleExportPdf = async (id) => {
    setPdfState('working')
    try {
      await exportHistoryPdf(id)
      setPdfState('idle')
      setActionsFor(null)
    } catch {
      setPdfState('error')
    }
  }

  const handleDelete = async (id) => {
    setDeleting(true)
    setDeleteError(false)
    try {
      if (isVisitor) {
        onDeleteVisitorEntry?.(id)
      } else {
        await deleteHistoryEntry(id)
        removeEntry(id)
      }
      setConfirmDeleteId(null)
    } catch {
      setDeleteError(true)
    } finally {
      setDeleting(false)
    }
  }

  if (error) {
    return <p className="pt-16 text-center text-sm text-slate-500 dark:text-slate-400">{t('history.couldntLoadHistory')}</p>
  }
  if (loading || !entries) {
    return <HistoryListSkeleton label={t('history.loading')} />
  }
  if (entries.length === 0) {
    return (
      <>
        <div className="pt-14 text-center">
          <span className="text-5xl">🍽️</span>
          <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">
            {isVisitor ? t('history.emptyVisitor') : t('history.emptyUser')}
          </p>
          {isVisitor && <SignInBanner text={t('history.visitorEphemeralNotice')} />}
        </div>
        {!emptyPromptClosed && (
          <HistoryEmptyModal onClose={() => setEmptyPromptClosed(true)} />
        )}
      </>
    )
  }

  return (
    <div className="space-y-4">
      {isVisitor && <SignInBanner text={t('history.visitorEphemeralNotice')} />}
      <WeeklyCaloriesChart entries={recent ?? entries} dailyBudget={dailyBudget} />
      <ul className="space-y-2">
        {entries.map((entry) => (
          <li key={entry.id} className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => navigate(`meal/${entry.id}`)}
              className="flex min-w-0 flex-1 items-center gap-3 rounded-xl border border-slate-200 bg-white p-3 text-left shadow-sm dark:border-slate-700 dark:bg-slate-800"
            >
              {entry.thumbnail ? (
                <img
                  src={entry.thumbnail}
                  alt=""
                  className="h-12 w-12 rounded-lg object-cover"
                />
              ) : (
                <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-slate-100 text-xl dark:bg-slate-700">
                  🍛
                </div>
              )}
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-slate-800 dark:text-slate-200">{entry.summary}</p>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  {new Date(entry.createdAt).toLocaleString(LOCALE_TAG[lang], {
                    day: 'numeric',
                    month: 'short',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                  {' · '}
                  {Math.round(entry.calories)} kcal
                </p>
              </div>
              <span className="flex shrink-0 items-center gap-1">
                {entry.source === 'barcode' && (
                  <span className="text-xs" title={t('history.verifiedFromBarcode')}>🔖</span>
                )}
                <span
                  className="rounded-lg border-2 px-2 py-0.5 text-sm font-black"
                  style={{ color: gradeColors[entry.grade], borderColor: gradeColors[entry.grade] }}
                >
                  {entry.grade}
                </span>
              </span>
            </button>
            {/* ⋮ now that there is a menu behind it. It was a 🗑️ while delete
                was the only thing a row could do — an overflow glyph that opens
                one destructive action is a trap. */}
            <button
              type="button"
              onClick={() => {
                setPdfState('idle')
                setActionsFor(entry)
              }}
              aria-label={t('history.moreOptions')}
              aria-haspopup="menu"
              className="shrink-0 rounded-full px-2 py-2 text-slate-500 active:bg-slate-100 dark:text-slate-400 dark:active:bg-slate-700"
            >
              <DotsIcon />
            </button>
          </li>
        ))}
      </ul>

      {actionsFor && (
        <Dialog
          onClose={() => {
            if (shareState === 'preparing' || pdfState === 'working') return
            setActionsFor(null)
          }}
          ariaLabel={t('history.mealOptions')}
          closeOnBackdrop
          overlayClassName="fixed inset-0 z-30 flex items-end justify-center bg-black/40 px-4 pb-4"
          panelClassName="w-full max-w-sm rounded-3xl bg-white p-2 shadow-xl dark:bg-slate-800"
        >
          <p className="truncate px-4 pb-1 pt-2 text-center text-xs text-slate-500 dark:text-slate-400">
            {actionsFor.summary}
          </p>
          {/* history.* labels, not results.* — the results strings carry their
              own emoji for the buttons on the detail screen, which is exactly
              what threw this menu's alignment out. */}
          <SheetAction
            onClick={share}
            disabled={shareState === 'preparing'}
            icon={<ShareGlyph className="h-5 w-5" />}
            label={shareState === 'preparing' ? t('results.preparingShare') : t('history.share')}
          />
          {/* Server-side, so it needs a saved row and a session — a visitor's
              meals are neither. Hidden rather than shown-and-refused. */}
          {!isVisitor && (
            <SheetAction
              onClick={() => handleExportPdf(actionsFor.id)}
              disabled={pdfState === 'working'}
              icon={<DocIcon />}
              label={pdfState === 'working' ? t('results.preparingPdf') : t('history.exportPdf')}
            />
          )}
          <SheetAction
            onClick={() => {
              const { id } = actionsFor
              setActionsFor(null)
              setConfirmDeleteId(id)
            }}
            label={t('history.deleteMeal')}
            icon={<TrashIcon />}
            destructive
          />
          {(shareState === 'error' || pdfState === 'error') && (
            <p role="alert" className="px-4 pb-1 pt-2 text-center text-xs text-red-500 dark:text-red-400">
              {shareState === 'error' ? t('results.shareError') : t('results.exportError')}
            </p>
          )}
          <button
            type="button"
            onClick={() => setActionsFor(null)}
            disabled={shareState === 'preparing' || pdfState === 'working'}
            className="mt-1 w-full rounded-2xl py-3 text-sm font-semibold text-slate-500 disabled:opacity-40 active:bg-slate-100 dark:text-slate-400 dark:active:bg-slate-700"
          >
            {t('history.cancel')}
          </button>
        </Dialog>
      )}

      {confirmDeleteId != null && (
        <Dialog
          onClose={() => {
            if (deleting) return
            setConfirmDeleteId(null)
            setDeleteError(false)
          }}
          ariaLabel={t('history.deleteThisMeal')}
          overlayClassName="fixed inset-0 z-30 flex items-center justify-center bg-black/40 px-6"
          panelClassName="w-full max-w-xs rounded-3xl bg-white p-6 text-center shadow-xl dark:bg-slate-800"
        >
          <span className="text-4xl">🗑️</span>
          <h3 className="mt-3 text-base font-bold text-slate-900 dark:text-slate-100">{t('history.deleteThisMeal')}</h3>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{t('history.cannotUndo')}</p>
          {deleteError && (
            <p className="mt-2 text-xs text-red-500 dark:text-red-400">{t('history.deleteError')}</p>
          )}
          <div className="mt-5 flex gap-3">
            <button
              onClick={() => {
                setConfirmDeleteId(null)
                setDeleteError(false)
              }}
              disabled={deleting}
              className="flex-1 rounded-2xl border border-slate-300 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-60 dark:border-slate-600 dark:text-slate-300"
            >
              {t('history.cancel')}
            </button>
            <button
              onClick={() => handleDelete(confirmDeleteId)}
              disabled={deleting}
              className="flex-1 rounded-2xl bg-red-600 py-2.5 text-sm font-bold text-white disabled:opacity-60"
            >
              {deleting ? t('history.deleting') : t('history.delete')}
            </button>
          </div>
        </Dialog>
      )}
    </div>
  )
}

/** Menu scans are only ever persisted for signed-in users (see MenuRankingService) — visitors get a sign-in prompt instead of an in-session list, since there's nothing ephemeral to show here. */
function MenuHistory({ isVisitor }) {
  const { t, lang } = useLanguage()
  const navigate = useNavigate()
  const [fetched, setFetched] = useState(null)
  const [error, setError] = useState(false)
  const [confirmDeleteId, setConfirmDeleteId] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState(false)

  useEffect(() => {
    if (isVisitor) return
    fetchMenuHistory()
      .then(setFetched)
      .catch(() => setError(true))
  }, [isVisitor])

  const handleDelete = async (id) => {
    setDeleting(true)
    setDeleteError(false)
    try {
      await deleteMenuHistoryEntry(id)
      setFetched((prev) => prev?.filter((e) => e.id !== id) ?? prev)
      setConfirmDeleteId(null)
    } catch {
      setDeleteError(true)
    } finally {
      setDeleting(false)
    }
  }

  if (isVisitor) {
    return (
      <div className="pt-14 text-center">
        <span className="text-5xl">📋</span>
        <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">{t('history.menusVisitorNotice')}</p>
      </div>
    )
  }

  if (error) {
    return <p className="pt-16 text-center text-sm text-slate-500 dark:text-slate-400">{t('history.couldntLoadHistory')}</p>
  }
  if (!fetched) {
    return <MenuListSkeleton label={t('history.loading')} />
  }
  if (fetched.length === 0) {
    return (
      <div className="pt-14 text-center">
        <span className="text-5xl">📋</span>
        <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">{t('history.emptyMenus')}</p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <ul className="space-y-2">
        {fetched.map((entry) => (
          <li key={entry.id} className="flex items-center gap-2">
            <button
              type="button"
              // Route-relative, exactly like the meal link above. It used to be
              // `../menu/:id` with relative:'path', which walks up a URL segment
              // rather than a route — from /history that resolved to /menu/:id,
              // matched App.jsx's catch-all, and redirected the user to the
              // capture screen instead of opening their scan.
              onClick={() => navigate(`menu/${entry.id}`)}
              className="flex min-w-0 flex-1 items-center gap-3 rounded-xl border border-slate-200 bg-white p-3 text-left shadow-sm dark:border-slate-700 dark:bg-slate-800"
            >
              {entry.thumbnail ? (
                <img src={entry.thumbnail} alt="" className="h-12 w-12 rounded-lg object-cover" />
              ) : (
                <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-slate-100 text-xl dark:bg-slate-700">
                  📋
                </div>
              )}
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-slate-800 dark:text-slate-200">{entry.summary}</p>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  {new Date(entry.createdAt).toLocaleString(LOCALE_TAG[lang], {
                    day: 'numeric',
                    month: 'short',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                  {' · '}
                  {t('history.dishesCount', entry.dishCount)}
                </p>
              </div>
            </button>
            <button
              type="button"
              onClick={() => setConfirmDeleteId(entry.id)}
              aria-label={t('history.deleteMeal')}
              className="shrink-0 rounded-full p-2 text-lg text-slate-500 active:bg-slate-100 dark:text-slate-400 dark:active:bg-slate-700"
            >
              🗑️
            </button>
          </li>
        ))}
      </ul>

      {confirmDeleteId != null && (
        <Dialog
          onClose={() => {
            if (deleting) return
            setConfirmDeleteId(null)
            setDeleteError(false)
          }}
          ariaLabel={t('history.deleteThisMeal')}
          overlayClassName="fixed inset-0 z-30 flex items-center justify-center bg-black/40 px-6"
          panelClassName="w-full max-w-xs rounded-3xl bg-white p-6 text-center shadow-xl dark:bg-slate-800"
        >
          <span className="text-4xl">🗑️</span>
          <h3 className="mt-3 text-base font-bold text-slate-900 dark:text-slate-100">{t('history.deleteThisMeal')}</h3>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{t('history.cannotUndo')}</p>
          {deleteError && (
            <p className="mt-2 text-xs text-red-500 dark:text-red-400">{t('history.deleteError')}</p>
          )}
          <div className="mt-5 flex gap-3">
            <button
              onClick={() => {
                setConfirmDeleteId(null)
                setDeleteError(false)
              }}
              disabled={deleting}
              className="flex-1 rounded-2xl border border-slate-300 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-60 dark:border-slate-600 dark:text-slate-300"
            >
              {t('history.cancel')}
            </button>
            <button
              onClick={() => handleDelete(confirmDeleteId)}
              disabled={deleting}
              className="flex-1 rounded-2xl bg-red-600 py-2.5 text-sm font-bold text-white disabled:opacity-60"
            >
              {deleting ? t('history.deleting') : t('history.delete')}
            </button>
          </div>
        </Dialog>
      )}
    </div>
  )
}

/**
 * A saved meal, reachable at /history/meal/:id — so it survives a refresh, can
 * be linked to, and above all answers the back button instead of exiting the
 * app, which is what it did when this was component state.
 */
function MealDetail({ isVisitor, dailyBudget, goal }) {
  const { t } = useLanguage()
  const navigate = useNavigate()
  const { id } = useParams()
  const { entries, updateEntry } = useHistory()
  const [detail, setDetail] = useState(null)
  const [detailError, setDetailError] = useState(false)

  // The thumbnail for the share card comes from the cached list rather than a
  // second request; the list is already loaded for this session.
  const listEntry = entries?.find((entry) => String(entry.id) === id)

  useEffect(() => {
    // Visitors already hold the whole result in memory, and have no session to
    // fetch it with anyway.
    if (isVisitor) return
    setDetail(null)
    setDetailError(false)
    fetchHistoryDetail(id)
      .then(setDetail)
      .catch(() => setDetailError(true))
  }, [id, isVisitor])

  const backToList = () => navigate('/history')

  if (isVisitor) {
    if (!listEntry?.result) {
      return <DetailMissing onBack={backToList} />
    }
    return (
      <ResultsScreen
        result={listEntry.result}
        dailyBudget={dailyBudget}
        goal={goal}
        onSnapAnother={backToList}
        actionLabel={<BackToHistoryLabel />}
        shareImageSource={listEntry.thumbnail}
      />
    )
  }
  if (detailError) {
    return <DetailMissing onBack={backToList} />
  }
  if (!detail) {
    return <DetailSkeleton label={t('history.loading')} />
  }
  return (
    <ResultsScreen
      result={detail}
      dailyBudget={dailyBudget}
      goal={goal}
      onSnapAnother={backToList}
      actionLabel={<BackToHistoryLabel />}
      onExportPdf={() => exportHistoryPdf(id)}
      shareImageSource={listEntry?.thumbnail}
      onResultCorrected={(corrected) => {
        setDetail(corrected)
        // The list row for this meal is now stale in three columns. Patching it
        // is cheaper and steadier than a refetch, which would re-download every
        // entry's inline thumbnail to learn what this response already said.
        updateEntry(Number(id), {
          calories: corrected.totals.calories,
          score: corrected.score,
          grade: corrected.grade,
        })
      }}
    />
  )
}

/** Same, for a saved menu scan. Menu scans are never kept for visitors. */
function MenuDetail() {
  const { t } = useLanguage()
  const navigate = useNavigate()
  const { id } = useParams()
  const [detail, setDetail] = useState(null)
  const [detailError, setDetailError] = useState(false)

  useEffect(() => {
    setDetail(null)
    setDetailError(false)
    fetchMenuHistoryDetail(id)
      .then(setDetail)
      .catch(() => setDetailError(true))
  }, [id])

  const backToList = () => navigate('/history?view=menus')

  if (detailError) {
    return <DetailMissing onBack={backToList} />
  }
  if (!detail) {
    return <DetailSkeleton label={t('history.loading')} />
  }
  return (
    <MenuResultsScreen
      result={detail}
      onScanAnother={backToList}
      actionLabel={<BackToHistoryLabel />}
      // No photo here on purpose: the menu list is fetched by MenuList, not by
      // this route, so there is no thumbnail in scope to hand over. The share
      // card falls back to its 📋 tile, which is a better outcome than
      // refetching the whole list to decorate one image.
    />
  )
}

/**
 * "Back to history", with the clock that names where it goes.
 *
 * The label used to carry a "⬅" in all three translation files. U+2B05 defaults
 * to *emoji* presentation, so Android drew a blue arrow that could not take the
 * button's white — and the same string is reused on a small green text link,
 * where an arrow suited it even less. Drawing it here means each call site gets
 * the icon at its own colour and size, and translators own only words.
 *
 * A clock rather than an arrow because it names the destination instead of the
 * direction: this is a jump to a tab, not a step back through the stack.
 */
export function BackToHistoryLabel() {
  const { t } = useLanguage()
  return (
    <>
      <HistoryClockIcon />
      <span>{t('results.backToHistory')}</span>
    </>
  )
}

function HistoryClockIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-[1.15em] w-[1.15em] flex-none"
    >
      {/* Counter-clockwise dial with its rewind tick, then the hands */}
      <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
      <path d="M3 4v4h4" />
      <path d="M12 8v4l3 2" />
    </svg>
  )
}

/**
 * The overflow glyph, drawn rather than typed.
 *
 * "⋮" as a text character is a hair-thin vertical ellipsis at this size and
 * lands on a different baseline in every font. Three circles are the same
 * everywhere, and inherit the button's colour.
 */
function DotsIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className="h-5 w-5">
      <circle cx="12" cy="5" r="1.9" />
      <circle cx="12" cy="12" r="1.9" />
      <circle cx="12" cy="19" r="1.9" />
    </svg>
  )
}

/**
 * One row of the meal action sheet.
 *
 * The icon sits in a fixed-width slot so every label starts at the same x. The
 * first version of this let two rows carry their icon inside the translated
 * string ("📤 Share", separated by a space character) while the third passed one
 * as a sibling with the flex `gap` between them — so the labels started ~8px
 * apart, and the row shifted sideways when it swapped to "Preparing image…",
 * which has no emoji at all. Icons belong to the layout, not the copy.
 */
function SheetAction({ onClick, label, icon, disabled, destructive }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`flex w-full items-center gap-3 rounded-2xl px-4 py-3.5 text-left text-sm font-semibold disabled:opacity-50 active:bg-slate-100 dark:active:bg-slate-700 ${
        destructive ? 'text-red-600 dark:text-red-400' : 'text-slate-800 dark:text-slate-100'
      }`}
    >
      <span className="flex h-5 w-5 flex-none items-center justify-center">{icon}</span>
      <span>{label}</span>
    </button>
  )
}

/** Filled sheet icons, sized to the 20px slot and inheriting the row's colour. */
function DocIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="h-5 w-5">
      <path d="M14.5 2.8H7a2 2 0 0 0-2 2v14.4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7.3Z" />
      <path d="M14.3 2.8v4.6h4.6" />
      <path d="M8.6 13.2h6.8M8.6 16.6h4.4" />
    </svg>
  )
}

function TrashIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="h-5 w-5">
      <path d="M4 6.6h16" />
      <path d="M9.4 6.6V4.9a1.4 1.4 0 0 1 1.4-1.4h2.4a1.4 1.4 0 0 1 1.4 1.4v1.7" />
      <path d="M6.4 6.6l.9 12.6a2 2 0 0 0 2 1.9h5.4a2 2 0 0 0 2-1.9l.9-12.6" />
      <path d="M10.4 10.6v6M13.6 10.6v6" />
    </svg>
  )
}

/** Shared by both detail routes — also what a stale bookmark to a deleted meal lands on. */
function DetailMissing({ onBack }) {
  const { t } = useLanguage()
  return (
    <div className="pt-16 text-center">
      <p className="text-sm text-slate-500 dark:text-slate-400">{t('history.couldntLoadDetail')}</p>
      <button
        onClick={onBack}
        className="mt-4 inline-flex items-center gap-1.5 text-sm font-medium text-grade-aplus dark:text-green-400"
      >
        <BackToHistoryLabel />
      </button>
    </div>
  )
}
