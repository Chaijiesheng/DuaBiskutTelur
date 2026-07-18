import { useEffect, useState } from 'react'
import { deleteHistoryEntry, exportHistoryPdf, fetchHistory, fetchHistoryDetail } from '../api.js'
import { GRADE_COLORS } from './GradeReveal.jsx'
import ResultsScreen from './ResultsScreen.jsx'
import HistoryEmptyModal from './HistoryEmptyModal.jsx'
import Dialog from './Dialog.jsx'
import SignInBanner from './SignInBanner.jsx'
import WeeklyCaloriesChart from './WeeklyCaloriesChart.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import { useTheme } from '../theme/ThemeContext.jsx'

const LOCALE_TAG = { en: 'en-US', zh: 'zh-CN', ms: 'ms-MY' }

export default function HistoryScreen({
  onAuthExpired,
  isVisitor,
  visitorEntries,
  onDeleteVisitorEntry,
  dailyBudget,
  goal,
}) {
  const { t, lang } = useLanguage()
  const { theme } = useTheme()
  const gradeColors = GRADE_COLORS[theme]
  // Visitors don't have server history — show their in-session results instead.
  const [fetched, setFetched] = useState(null)
  const [error, setError] = useState(false)
  const [selectedId, setSelectedId] = useState(null)
  const [detail, setDetail] = useState(null)
  const [detailError, setDetailError] = useState(false)
  // Pop-out shown first when there's nothing to display; clicking its CTA
  // reveals the plain empty-history view underneath. Re-rolled each time the
  // tab is opened (component remounts on tab switch).
  const [emptyPromptClosed, setEmptyPromptClosed] = useState(false)
  // Per-row "⋮" opens the delete confirmation directly.
  const [confirmDeleteId, setConfirmDeleteId] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState(false)

  useEffect(() => {
    if (isVisitor) return
    fetchHistory()
      .then(setFetched)
      .catch((e) => {
        if (e.code === 'UNAUTHENTICATED') onAuthExpired?.()
        else setError(true)
      })
  }, [isVisitor, onAuthExpired])

  useEffect(() => {
    // Visitors already have the full result in memory (visitorEntries) — no
    // server round trip needed, and there's no session to fetch it from anyway.
    if (selectedId == null || isVisitor) return
    setDetail(null)
    setDetailError(false)
    fetchHistoryDetail(selectedId)
      .then(setDetail)
      .catch((e) => {
        if (e.code === 'UNAUTHENTICATED') onAuthExpired?.()
        else setDetailError(true)
      })
  }, [selectedId, isVisitor, onAuthExpired])

  const entries = isVisitor ? visitorEntries : fetched

  const handleDelete = async (id) => {
    setDeleting(true)
    setDeleteError(false)
    try {
      if (isVisitor) {
        onDeleteVisitorEntry?.(id)
      } else {
        await deleteHistoryEntry(id)
        setFetched((prev) => prev?.filter((e) => e.id !== id) ?? prev)
      }
      setConfirmDeleteId(null)
    } catch (e) {
      if (e.code === 'UNAUTHENTICATED') onAuthExpired?.()
      else setDeleteError(true)
    } finally {
      setDeleting(false)
    }
  }

  if (selectedId != null) {
    if (isVisitor) {
      const entry = visitorEntries?.find((e) => e.id === selectedId)
      if (!entry?.result) return null
      return (
        <ResultsScreen
          result={entry.result}
          dailyBudget={dailyBudget}
          goal={goal}
          onSnapAnother={() => setSelectedId(null)}
          actionLabel={t('results.backToHistory')}
        />
      )
    }
    if (detailError) {
      return (
        <div className="pt-16 text-center">
          <p className="text-sm text-slate-500 dark:text-slate-400">{t('history.couldntLoadDetail')}</p>
          <button
            onClick={() => setSelectedId(null)}
            className="mt-4 text-sm font-medium text-grade-aplus dark:text-green-400"
          >
            {t('results.backToHistory')}
          </button>
        </div>
      )
    }
    if (!detail) {
      return <p className="pt-16 text-center text-sm text-slate-500 dark:text-slate-400">{t('history.loading')}</p>
    }
    return (
      <ResultsScreen
        result={detail}
        dailyBudget={dailyBudget}
        goal={goal}
        onSnapAnother={() => setSelectedId(null)}
        actionLabel={t('results.backToHistory')}
        onExportPdf={isVisitor ? undefined : () => exportHistoryPdf(selectedId)}
      />
    )
  }

  if (!isVisitor && error) {
    return <p className="pt-16 text-center text-sm text-slate-500 dark:text-slate-400">{t('history.couldntLoadHistory')}</p>
  }
  if (!entries) {
    return <p className="pt-16 text-center text-sm text-slate-500 dark:text-slate-400">{t('history.loading')}</p>
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
    <div className="space-y-4 pt-2">
      {isVisitor && <SignInBanner text={t('history.visitorEphemeralNotice')} />}
      <WeeklyCaloriesChart entries={entries} dailyBudget={dailyBudget} />
      <ul className="space-y-2">
        {entries.map((entry) => (
          <li key={entry.id} className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setSelectedId(entry.id)}
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
            {/* 🗑️ not ⋮ — the button goes straight to the delete confirmation,
                so a "more options" glyph promised a menu that never existed. */}
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
