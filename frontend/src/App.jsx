import { useCallback, useEffect, useRef, useState } from 'react'
import CaptureScreen from './components/CaptureScreen.jsx'
import TodaySummaryCard from './components/TodaySummaryCard.jsx'
import BarcodeScanScreen from './components/BarcodeScanScreen.jsx'
import AnalyzingScreen from './components/AnalyzingScreen.jsx'
import ResultsScreen from './components/ResultsScreen.jsx'
import HistoryScreen from './components/HistoryScreen.jsx'
import AnalysisScreen from './components/AnalysisScreen.jsx'
import ProfilePage from './components/ProfilePage.jsx'
import ErrorScreen from './components/ErrorScreen.jsx'
import InstallPrompt from './components/InstallPrompt.jsx'
import ProfileScreen from './components/ProfileScreen.jsx'
import AccountMenu from './components/AccountMenu.jsx'
import DashboardSummary from './components/DashboardSummary.jsx'
import SignInScreen from './components/SignInScreen.jsx'
import EmptyDashboardModal from './components/EmptyDashboardModal.jsx'
import {
  analyzeImage,
  lookupBarcode as lookupBarcodeApi,
  fetchMe,
  fetchDashboardToday,
  saveProfile as saveProfileApi,
  saveBudget as saveBudgetApi,
  logout as logoutApi,
} from './api.js'
import { compressImage } from './imageUtils.js'
import { calculateDailyBudget } from './calorieCalculator.js'
import { LanguageProvider, useLanguage } from './i18n/LanguageContext.jsx'
import { ThemeProvider } from './theme/ThemeContext.jsx'
import { setUpdateGateBusy } from './swUpdateGate.js'
import ErrorBoundary from './ErrorBoundary.jsx'

const DEFAULT_BUDGET = 2000

function loadLocalProfile() {
  try {
    return JSON.parse(localStorage.getItem('mealProfile'))
  } catch {
    return null
  }
}

export default function App() {
  return (
    <ThemeProvider>
      <LanguageProvider>
        <ErrorBoundary>
          <AppShell />
        </ErrorBoundary>
      </LanguageProvider>
    </ThemeProvider>
  )
}

function AppShell() {
  const { t, lang } = useLanguage()
  const [ready, setReady] = useState(false)
  const [user, setUser] = useState(null) // null = visitor (not signed in)
  const [tab, setTab] = useState('snap')
  const [phase, setPhase] = useState('capture')
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [lastFile, setLastFile] = useState(null)
  const [lastBarcodeArgs, setLastBarcodeArgs] = useState(null)
  const [online, setOnline] = useState(navigator.onLine)
  const [installEvent, setInstallEvent] = useState(null)
  const [showInstall, setShowInstall] = useState(false)
  const [showProfileScreen, setShowProfileScreen] = useState(false)
  const [showSignInScreen, setShowSignInScreen] = useState(false)
  const [dashboard, setDashboard] = useState(null)
  const [showDashboardModal, setShowDashboardModal] = useState(false)
  const [showEmptyDashboardModal, setShowEmptyDashboardModal] = useState(false)
  // True when the last analysis came back unpersisted for a client that still
  // thought it was signed in — i.e. the session cookie expired server-side.
  const [sessionExpired, setSessionExpired] = useState(false)

  // Visitor-only local state (lost on refresh, per the "no account" experience).
  const [localProfile, setLocalProfile] = useState(loadLocalProfile)
  const [localBudget, setLocalBudget] = useState(
    () => Number(localStorage.getItem('dailyBudget')) || DEFAULT_BUDGET,
  )
  const [visitorHistory, setVisitorHistory] = useState([])

  const isAuthed = Boolean(user)
  const dailyBudget = isAuthed ? user.dailyBudget ?? DEFAULT_BUDGET : localBudget
  const hasProfile = isAuthed ? user.hasProfile : Boolean(localProfile)

  const profileForm = isAuthed
    ? user.hasProfile
      ? {
          age: user.age,
          sex: user.sex,
          weightKg: user.weightKg,
          heightCm: user.heightCm,
          steps: user.steps,
          exerciseFrequency: user.exerciseFrequency,
          goal: user.goal,
        }
      : null
    : localProfile

  useEffect(() => {
    fetchMe()
      .then((me) => setUser(me)) // me is null for visitors — that's fine
      .catch(() => setUser(null))
      .finally(() => setReady(true))
  }, [])

  // Right after login (or on reload while already signed in), check today's
  // logged meals and pop up a one-time summary — calories/protein/meals/grade
  // if there's data, or a funny nudge if there isn't. Either way it's just a
  // temporary overlay; the Snap/History pages themselves stay unaffected.
  useEffect(() => {
    if (!isAuthed) {
      setDashboard(null)
      setShowDashboardModal(false)
      setShowEmptyDashboardModal(false)
      return
    }
    fetchDashboardToday()
      .then((data) => {
        setDashboard(data)
        if (data.hasData) setShowDashboardModal(true)
        else setShowEmptyDashboardModal(true)
      })
      .catch(() => setDashboard(null))
  }, [isAuthed])

  // Returning guests (sign-in prompt already dismissed in an earlier visit)
  // never see SignInScreen again, so the same "no meals today" nudge has to
  // run here instead. Guests have no persisted history, so "today" is just
  // this session's in-memory list, checked against React state directly.
  // Gated to the very first time the app finishes loading — without this,
  // the effect re-fires on every isAuthed flip, including logout, turning
  // "sign out" into an unwanted "you haven't logged anything today!" popup.
  const initialLoadHandledRef = useRef(false)
  useEffect(() => {
    if (!ready) return
    const isInitialLoad = !initialLoadHandledRef.current
    initialLoadHandledRef.current = true
    if (!isInitialLoad || isAuthed) return
    if (localStorage.getItem('signInScreenDismissed') !== '1') return
    if (visitorHistory.length === 0) {
      setShowEmptyDashboardModal(true)
    }
  }, [ready, isAuthed])

  useEffect(() => {
    const up = () => setOnline(true)
    const down = () => setOnline(false)
    window.addEventListener('online', up)
    window.addEventListener('offline', down)
    return () => {
      window.removeEventListener('online', up)
      window.removeEventListener('offline', down)
    }
  }, [])

  useEffect(() => {
    const onPrompt = (e) => {
      e.preventDefault()
      setInstallEvent(e)
    }
    window.addEventListener('beforeinstallprompt', onPrompt)
    return () => window.removeEventListener('beforeinstallprompt', onPrompt)
  }, [])

  // Lets a background app-update deploy apply itself (see main.jsx) without
  // ever reloading out from under an in-flight photo analysis or an open
  // barcode scan — both involve state (a captured file, a live camera
  // stream) that a reload would silently throw away.
  useEffect(() => {
    setUpdateGateBusy(phase === 'analyzing' || phase === 'barcode')
  }, [phase])

  const saveProfile = async (newProfile) => {
    if (isAuthed) {
      try {
        const updated = await saveProfileApi(newProfile)
        setUser(updated)
      } catch {
        /* leave prior state; user can retry */
      }
    } else {
      localStorage.setItem('mealProfile', JSON.stringify(newProfile))
      setLocalProfile(newProfile)
      const computed = calculateDailyBudget(newProfile)
      if (computed != null) {
        localStorage.setItem('dailyBudget', String(computed))
        setLocalBudget(computed)
      }
    }
    setShowProfileScreen(false)
  }

  const saveBudget = async (value) => {
    if (isAuthed) {
      try {
        const updated = await saveBudgetApi(value)
        setUser(updated)
      } catch {
        /* ignore */
      }
    } else {
      localStorage.setItem('dailyBudget', String(value))
      setLocalBudget(value)
    }
  }

  const dismissProfileScreen = () => {
    localStorage.setItem('profileScreenDismissed', '1')
    setShowProfileScreen(false)
  }

  const dismissSignInScreen = () => {
    localStorage.setItem('signInScreenDismissed', '1')
    setShowSignInScreen(false)
  }

  const doLogout = async () => {
    await logoutApi().catch(() => {})
    setUser(null)
    setVisitorHistory([])
  }

  // Shared across analyze() and lookupBarcode(): whichever request started
  // last "owns" the screen. Without this, tapping the Snap tab mid-analysis
  // (which resets to capture) and starting a second photo/scan means both
  // requests are in flight, and whichever happens to resolve last wins the
  // results screen — even if the user has already moved on.
  const requestSeqRef = useRef(0)

  // Sign-in, profile setup, and the install prompt used to all fire the
  // moment the app loaded, stacking up to three blocking screens in front of
  // a brand-new visitor before they'd ever snapped a meal. Now they're deferred
  // until right after the first meal is successfully logged (photo or
  // barcode) — intent is proven, and the render ternary below already shows
  // sign-in ahead of profile setup, so at most one full-screen prompt appears.
  const firstMealPromptedRef = useRef(false)
  const promptFirstRunInterstitials = useCallback(() => {
    if (firstMealPromptedRef.current) return
    firstMealPromptedRef.current = true
    if (!localStorage.getItem('installPromptShown')) {
      localStorage.setItem('installPromptShown', '1')
      setShowInstall(true)
    }
    if (!isAuthed && localStorage.getItem('signInScreenDismissed') !== '1') {
      setShowSignInScreen(true)
    } else if (!hasProfile && localStorage.getItem('profileScreenDismissed') !== '1') {
      setShowProfileScreen(true)
    }
  }, [isAuthed, hasProfile])

  // Queued during a successful analysis, fired only once the user leaves the
  // results view. Firing immediately used to replace the very first grade
  // reveal (count-up, confetti, feedback) with a full-screen sign-in prompt —
  // hiding the product's reward moment behind a sign-up wall.
  const pendingFirstRunPromptsRef = useRef(false)
  useEffect(() => {
    if (!pendingFirstRunPromptsRef.current) return
    if (tab === 'snap' && (phase === 'analyzing' || phase === 'results')) return
    pendingFirstRunPromptsRef.current = false
    promptFirstRunInterstitials()
  }, [tab, phase, promptFirstRunInterstitials])

  // Shared post-success bookkeeping for photo and barcode analyses. When the
  // server reports the meal wasn't attributed to an account (persisted false)
  // while this client still thinks it's signed in, the session cookie expired
  // server-side — flip to visitor mode and keep the meal in the in-session
  // list so nothing is silently lost, with a notice on the results screen.
  const handleAnalysisSuccess = useCallback(
    (analysis) => {
      const expired = isAuthed && analysis.persisted === false
      setSessionExpired(expired)
      if (expired) setUser(null)
      setResult(analysis)
      setPhase('results')
      if (isAuthed && !expired) {
        fetchDashboardToday().then(setDashboard).catch(() => {})
      } else {
        // Visitors keep an in-session history (cleared on refresh).
        setVisitorHistory((prev) => [
          {
            id: Date.now(),
            createdAt: new Date().toISOString(),
            score: analysis.score,
            grade: analysis.grade,
            calories: analysis.totals.calories,
            summary: analysis.foods.map((f) => f.name).join(', '),
            thumbnail: null,
            source: analysis.source,
            result: analysis,
          },
          ...prev,
        ])
      }
      pendingFirstRunPromptsRef.current = true
    },
    [isAuthed],
  )

  const analyze = useCallback(
    async (file) => {
      const seq = ++requestSeqRef.current
      setLastFile(file)
      setLastBarcodeArgs(null)
      setPhase('analyzing')
      setError(null)
      try {
        const compressed = await compressImage(file)
        const analysis = await analyzeImage(compressed, 'meal.jpg', lang)
        if (seq !== requestSeqRef.current) return // superseded by a newer analysis
        handleAnalysisSuccess(analysis)
      } catch (e) {
        if (seq !== requestSeqRef.current) return
        setError(e)
        setPhase('error')
      }
    },
    [lang, handleAnalysisSuccess],
  )

  const lookupBarcode = useCallback(
    async (code, servings) => {
      const seq = ++requestSeqRef.current
      setLastFile(null)
      setLastBarcodeArgs({ code, servings })
      setPhase('analyzing')
      setError(null)
      try {
        const analysis = await lookupBarcodeApi(code, servings, lang)
        if (seq !== requestSeqRef.current) return
        handleAnalysisSuccess(analysis)
      } catch (e) {
        if (seq !== requestSeqRef.current) return
        setError(e)
        setPhase('error')
      }
    },
    [lang, handleAnalysisSuccess],
  )

  const retry = () => {
    if (lastFile) {
      analyze(lastFile)
    } else if (lastBarcodeArgs) {
      // A failed barcode is usually a miss, not a transient error — go back
      // to the scanner rather than replaying the same code.
      setPhase('barcode')
    } else {
      setPhase('capture')
    }
  }

  if (!ready) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-900">
        <div className="h-10 w-10 animate-spin rounded-full border-4 border-slate-200 border-t-grade-aplus dark:border-slate-700" />
      </div>
    )
  }

  return (
    <div className="mx-auto flex min-h-screen max-w-md flex-col bg-slate-50 dark:bg-slate-900">
      <header className="flex items-center justify-between px-5 pb-2 pt-6">
        <div>
          <h1 className="text-xl font-extrabold tracking-tight text-slate-900 dark:text-slate-100">
            {t('app.title1')}<span className="text-grade-aplus dark:text-green-400">{t('app.title2')}</span>
          </h1>
          <p className="text-xs text-slate-500 dark:text-slate-400">{t('app.tagline')}</p>
        </div>
        <AccountMenu
          user={user}
          hasProfile={hasProfile}
          dailyBudget={dailyBudget}
          onSaveBudget={saveBudget}
          onEditProfile={() => setShowProfileScreen(true)}
          onLogout={doLogout}
        />
      </header>

      <main className="flex-1 px-4 pb-[calc(6rem+env(safe-area-inset-bottom))]">
        {showSignInScreen && !isAuthed ? (
          <SignInScreen onSkip={dismissSignInScreen} />
        ) : showProfileScreen ? (
          <ProfileScreen
            initialProfile={profileForm}
            currentDailyBudget={dailyBudget}
            allowSkip={!hasProfile}
            onSave={saveProfile}
            onCancel={hasProfile ? () => setShowProfileScreen(false) : dismissProfileScreen}
          />
        ) : tab === 'history' ? (
          <HistoryScreen
            isVisitor={!isAuthed}
            visitorEntries={visitorHistory}
            onDeleteVisitorEntry={(id) =>
              setVisitorHistory((prev) => prev.filter((e) => e.id !== id))
            }
            onAuthExpired={() => setUser(null)}
            dailyBudget={dailyBudget}
            goal={profileForm?.goal}
          />
        ) : tab === 'analysis' ? (
          <AnalysisScreen
            isVisitor={!isAuthed}
            visitorEntries={visitorHistory}
            onAuthExpired={() => setUser(null)}
            dailyBudget={dailyBudget}
            goal={isAuthed ? user.goal : null}
          />
        ) : tab === 'profile' ? (
          <ProfilePage
            user={user}
            isVisitor={!isAuthed}
            hasProfile={hasProfile}
            dailyBudget={dailyBudget}
            onEditProfile={() => setShowProfileScreen(true)}
            onLogout={doLogout}
          />
        ) : phase === 'analyzing' ? (
          <AnalyzingScreen />
        ) : phase === 'results' && result ? (
          <ResultsScreen
            result={result}
            dailyBudget={dailyBudget}
            goal={profileForm?.goal}
            onSnapAnother={() => setPhase('capture')}
            banner={sessionExpired ? t('results.sessionExpired') : undefined}
          />
        ) : phase === 'error' ? (
          <ErrorScreen error={error} onRetry={retry} onBack={() => setPhase('capture')} />
        ) : phase === 'barcode' ? (
          <BarcodeScanScreen
            onConfirm={lookupBarcode}
            onCancel={() => setPhase('capture')}
            onTakePhotoInstead={() => setPhase('capture')}
          />
        ) : (
          <>
            <TodaySummaryCard
              isVisitor={!isAuthed}
              dashboard={dashboard}
              visitorEntries={visitorHistory}
              dailyBudget={dailyBudget}
              profile={profileForm}
            />
            <CaptureScreen online={online} onPhoto={analyze} onScanBarcode={() => setPhase('barcode')} />
          </>
        )}
      </main>

      {/* pb-[env(...)] keeps the tab row clear of the iPhone home indicator in standalone mode */}
      <nav className="fixed inset-x-0 bottom-0 z-10 mx-auto flex max-w-md border-t border-slate-200 bg-white/95 pb-[env(safe-area-inset-bottom)] backdrop-blur dark:border-slate-700 dark:bg-slate-800/95">
        <TabButton
          active={tab === 'snap'}
          label={t('nav.snap')}
          icon="📸"
          onClick={() => {
            setTab('snap')
            setPhase('capture')
          }}
        />
        <TabButton
          active={tab === 'history'}
          label={t('nav.history')}
          icon="🗓️"
          onClick={() => setTab('history')}
        />
        <TabButton
          active={tab === 'analysis'}
          label={t('nav.analysis')}
          icon="📊"
          onClick={() => setTab('analysis')}
        />
        <TabButton
          active={tab === 'profile'}
          label={t('nav.profile')}
          icon="👤"
          onClick={() => setTab('profile')}
        />
      </nav>

      {showInstall && installEvent && (
        <InstallPrompt installEvent={installEvent} onDone={() => setShowInstall(false)} />
      )}

      {showEmptyDashboardModal && (
        <EmptyDashboardModal onClose={() => setShowEmptyDashboardModal(false)} />
      )}

      {showDashboardModal && (
        <DashboardSummary data={dashboard} onClose={() => setShowDashboardModal(false)} />
      )}
    </div>
  )
}

function TabButton({ active, label, icon, onClick }) {
  return (
    <button
      onClick={onClick}
      className={`flex flex-1 flex-col items-center gap-0.5 py-3 text-xs font-medium ${
        active ? 'text-grade-aplus dark:text-green-400' : 'text-slate-500 dark:text-slate-400'
      }`}
    >
      <span className="text-lg leading-none">{icon}</span>
      {label}
    </button>
  )
}
