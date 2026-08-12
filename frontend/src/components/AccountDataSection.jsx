import { useState } from 'react'
import Dialog from './Dialog.jsx'
import { deleteAccount, exportAccountData } from '../api.js'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * Export and account deletion. Kept out of Settings deliberately — an
 * irreversible action doesn't belong one tap away from the theme picker.
 *
 * The export is offered above the delete rather than beside it, because
 * "download a copy first" is advice that only helps before the account is gone.
 */
export default function AccountDataSection({ onAccountDeleted }) {
  const { t } = useLanguage()
  const [exportState, setExportState] = useState('idle') // idle | working | error
  const [confirming, setConfirming] = useState(false)

  const handleExport = async () => {
    setExportState('working')
    try {
      await exportAccountData()
      setExportState('idle')
    } catch {
      setExportState('error')
    }
  }

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-semibold text-slate-800 dark:text-slate-100">{t('accountData.exportTitle')}</p>
        <p className="mt-1 text-xs leading-relaxed text-slate-500 dark:text-slate-400">{t('accountData.exportBody')}</p>
        <button
          onClick={handleExport}
          disabled={exportState === 'working'}
          className="mt-2 w-full rounded-2xl border border-slate-300 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-60 dark:border-slate-600 dark:text-slate-300"
        >
          {exportState === 'working' ? t('accountData.exportPreparing') : `⬇️ ${t('accountData.exportButton')}`}
        </button>
        {exportState === 'error' && (
          <p className="mt-1.5 text-center text-xs text-red-500 dark:text-red-400">{t('accountData.exportError')}</p>
        )}
      </div>

      <div className="border-t border-slate-200 pt-4 dark:border-slate-700">
        <p className="text-sm font-semibold text-red-700 dark:text-red-400">{t('accountData.deleteTitle')}</p>
        <p className="mt-1 text-xs leading-relaxed text-slate-500 dark:text-slate-400">{t('accountData.deleteBody')}</p>
        <button
          onClick={() => setConfirming(true)}
          className="mt-2 w-full rounded-2xl border border-red-300 py-2.5 text-sm font-semibold text-red-700 dark:border-red-900/60 dark:text-red-400"
        >
          {t('accountData.deleteButton')}
        </button>
      </div>

      {confirming && (
        <DeleteConfirmDialog onClose={() => setConfirming(false)} onDeleted={onAccountDeleted} />
      )}
    </div>
  )
}

function DeleteConfirmDialog({ onClose, onDeleted }) {
  const { t } = useLanguage()
  const [typed, setTyped] = useState('')
  const [state, setState] = useState('idle') // idle | deleting | error

  // Translated rather than a fixed English "DELETE": asking someone to type a
  // word they may not read isn't a confirmation, it's a copying exercise.
  const confirmWord = t('accountData.deleteConfirmWord')
  const armed = typed.trim().toUpperCase() === confirmWord.toUpperCase()

  const handleDelete = async () => {
    if (!armed) return
    setState('deleting')
    try {
      await deleteAccount()
      onDeleted()
    } catch {
      setState('error')
    }
  }

  return (
    <Dialog
      onClose={state === 'deleting' ? undefined : onClose}
      ariaLabel={t('accountData.deleteDialogTitle')}
      overlayClassName="fixed inset-0 z-30 flex items-center justify-center bg-black/40 px-6"
      panelClassName="w-full max-w-xs rounded-3xl bg-white p-6 text-center shadow-xl dark:bg-slate-800"
    >
      <span className="text-4xl">⚠️</span>
      <h3 className="mt-3 text-base font-bold text-slate-900 dark:text-slate-100">
        {t('accountData.deleteDialogTitle')}
      </h3>
      <p className="mt-2 text-sm leading-relaxed text-slate-500 dark:text-slate-400">
        {t('accountData.deleteDialogBody')}
      </p>

      <label className="mt-4 block text-left">
        <span className="text-xs font-semibold text-slate-700 dark:text-slate-300">
          {t('accountData.deleteConfirmPrompt', confirmWord)}
        </span>
        <input
          type="text"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          disabled={state === 'deleting'}
          autoComplete="off"
          className="input mt-1.5"
        />
      </label>

      {state === 'error' && (
        <p className="mt-2 text-xs text-red-500 dark:text-red-400">{t('accountData.deleteError')}</p>
      )}

      <div className="mt-5 flex gap-3">
        <button
          onClick={onClose}
          disabled={state === 'deleting'}
          className="flex-1 rounded-2xl border border-slate-300 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-60 dark:border-slate-600 dark:text-slate-300"
        >
          {t('accountData.deleteCancel')}
        </button>
        <button
          onClick={handleDelete}
          disabled={!armed || state === 'deleting'}
          className="flex-1 rounded-2xl bg-red-600 py-2.5 text-sm font-bold text-white disabled:opacity-40"
        >
          {state === 'deleting' ? t('accountData.deleting') : t('accountData.deleteConfirm')}
        </button>
      </div>
    </Dialog>
  )
}
