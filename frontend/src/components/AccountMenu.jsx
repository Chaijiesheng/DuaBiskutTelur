import { useEffect, useRef, useState } from 'react'
import { googleLoginUrl } from '../api.js'
import FoodEquivalents from './FoodEquivalents.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'

const DEFAULT_BUDGET = 2000

export default function AccountMenu({ user, hasProfile, dailyBudget, onSaveBudget, onEditProfile, onLogout }) {
  const { t } = useLanguage()
  const [open, setOpen] = useState(false)
  const [budget, setBudget] = useState(dailyBudget)
  const ref = useRef(null)
  const isVisitor = !user

  useEffect(() => setBudget(dailyBudget), [dailyBudget])

  useEffect(() => {
    if (!open) return undefined
    const onClick = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false)
    }
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [open])

  const initial = (user?.name || user?.email || '?').charAt(0).toUpperCase()

  const commitBudget = () => {
    const value = Number(budget) || DEFAULT_BUDGET
    if (value !== dailyBudget) onSaveBudget(value)
  }

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2 rounded-full border border-slate-200 bg-white py-1 pl-1 pr-3 text-xs font-medium text-slate-600 shadow-sm dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300"
        aria-label={t('account.ariaLabel')}
      >
        {isVisitor ? <GuestIcon /> : <Avatar user={user} initial={initial} />}
        <span className="tabular-nums">{dailyBudget} kcal</span>
      </button>

      {open && (
        <div className="absolute right-0 z-20 mt-2 w-64 rounded-xl border border-slate-200 bg-white p-4 shadow-lg dark:border-slate-700 dark:bg-slate-800">
          {isVisitor ? (
            <div className="border-b border-slate-100 pb-3 dark:border-slate-700">
              <p className="text-sm font-semibold text-slate-900 dark:text-slate-100">{t('account.guest')}</p>
              <p className="text-xs text-slate-500 dark:text-slate-400">{t('account.guestSubtitle')}</p>
              <a
                href={googleLoginUrl()}
                className="mt-2 flex w-full items-center justify-center gap-2 rounded-lg border border-slate-300 py-2 text-xs font-semibold text-slate-700 dark:border-slate-600 dark:text-slate-300"
              >
                <GoogleG /> {t('account.signInWithGoogle')}
              </a>
            </div>
          ) : (
            <div className="flex items-center gap-3 border-b border-slate-100 pb-3 dark:border-slate-700">
              <Avatar user={user} initial={initial} large />
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-slate-900 dark:text-slate-100">{user.name || t('account.you')}</p>
                <p className="truncate text-xs text-slate-500 dark:text-slate-400">{user.email}</p>
              </div>
            </div>
          )}

          <label className="mt-3 block text-xs font-semibold text-slate-700 dark:text-slate-300" htmlFor="budget">
            {t('account.dailyBudget')}
          </label>
          <div className="mt-1.5 flex gap-2">
            <input
              id="budget"
              type="number"
              min="1000"
              max="5000"
              step="50"
              value={budget}
              onChange={(e) => setBudget(e.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-700 dark:text-slate-100"
            />
            <button
              onClick={commitBudget}
              className="shrink-0 rounded-lg bg-grade-aplus px-3 text-sm font-semibold text-white"
            >
              {t('account.save')}
            </button>
          </div>
          <FoodEquivalents calories={Number(budget) || 0} />

          <button
            onClick={() => {
              setOpen(false)
              onEditProfile()
            }}
            className="mt-3 w-full text-left text-xs font-medium text-grade-aplus dark:text-green-400"
          >
            {hasProfile ? t('account.recalculate') : t('account.setUpForBudget')}
          </button>

          {!isVisitor && (
            <button
              onClick={onLogout}
              className="mt-3 w-full rounded-lg border border-slate-200 py-2 text-sm font-semibold text-slate-600 dark:border-slate-700 dark:text-slate-300"
            >
              {t('account.logOut')}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function GuestIcon() {
  return (
    <span className="flex h-7 w-7 items-center justify-center rounded-full bg-slate-200 text-sm dark:bg-slate-600">
      👤
    </span>
  )
}

export function Avatar({ user, initial, large }) {
  const [broken, setBroken] = useState(false)
  const size = large ? 'h-10 w-10 text-base' : 'h-7 w-7 text-xs'
  if (user?.picture && !broken) {
    return (
      <img
        src={user.picture}
        alt=""
        referrerPolicy="no-referrer"
        onError={() => setBroken(true)}
        className={`${size} rounded-full object-cover`}
      />
    )
  }
  return (
    <span
      className={`${size} flex items-center justify-center rounded-full bg-grade-aplus font-bold text-white`}
    >
      {initial}
    </span>
  )
}

export function GoogleG() {
  return (
    <svg className="h-4 w-4" viewBox="0 0 24 24" aria-hidden="true">
      <path
        fill="#4285F4"
        d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1Z"
      />
      <path
        fill="#34A853"
        d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84A11 11 0 0 0 12 23Z"
      />
      <path
        fill="#FBBC05"
        d="M5.84 14.1a6.6 6.6 0 0 1 0-4.2V7.06H2.18a11 11 0 0 0 0 9.88l3.66-2.84Z"
      />
      <path
        fill="#EA4335"
        d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1A11 11 0 0 0 2.18 7.06l3.66 2.84C6.71 7.3 9.14 5.38 12 5.38Z"
      />
    </svg>
  )
}
