import { useCallback, useEffect, useRef, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import CaptureScreen from './components/CaptureScreen.jsx'
import TodaySummaryCard from './components/TodaySummaryCard.jsx'
import BarcodeScanScreen from './components/BarcodeScanScreen.jsx'
import AnalyzingScreen from './components/AnalyzingScreen.jsx'
import ResultsScreen from './components/ResultsScreen.jsx'
import MenuResultsScreen from './components/MenuResultsScreen.jsx'
import HistoryScreen from './components/HistoryScreen.jsx'
import WorkoutScreen from './components/WorkoutScreen.jsx'
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
  CANCELLED,
  analyzeImage,
  exportHistoryPdf,
  lookupBarcode as lookupBarcodeApi,
  rankMenuImage,
  fetchMe,
  fetchDashboardToday,
  saveProfile as saveProfileApi,
  saveBudget as saveBudgetApi,
  logout as logoutApi,
} from './api.js'
import { compressImage, compressMenuImage } from './imageUtils.js'
import { calculateDailyBudget } from './calorieCalculator.js'
import { LanguageProvider, useLanguage } from './i18n/LanguageContext.jsx'
import { ThemeProvider } from './theme/ThemeContext.jsx'
import { setUpdateGateBusy } from './swUpdateGate.js'
import { HistoryProvider } from './history/HistoryContext.jsx'
import { WorkoutProvider } from './workout/WorkoutContext.jsx'
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
    <BrowserRouter>
      <ThemeProvider>
        <LanguageProvider>
          <ErrorBoundary>
            <AppShell />
          </ErrorBoundary>
        </LanguageProvider>
      </ThemeProvider>
    </BrowserRouter>
  )
}

/** Which bottom-tab is lit for a given path. Detail views stay under their tab. */
function activeTabFor(pathname) {
  if (pathname.startsWith('/workout')) return 'workout'
  if (pathname.startsWith('/history')) return 'history'
  if (pathname.startsWith('/analysis')) return 'analysis'
  if (pathname.startsWith('/profile')) return 'profile'
  return 'snap'
}

function AppShell() {
  const { t, lang } = useLanguage()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  // The tab is derived from the URL rather than held in state, which is what
  // makes the Android hardware back button (and browser back, and a bookmarked
  // link) work at all — previously it was React state, so back exited the app.
  const tab = activeTabFor(pathname)
  // Everything not needed mid-set is gone while a workout is running, the tab
  // bar included. That is not styling: a row of other destinations at the bottom
  // of the screen is an invitation to leave a session you are three sets into.
  const inSession = pathname.startsWith('/workout/session')
  const [ready, setReady] = useState(false)
  const [user, setUser] = useState(null) // null = visitor (not signed in)
  // Not a route: 'analyzing' and 'results' are transient states of one capture,
  // not places you can link someone to.
  const [phase, setPhase] = useState('capture')
  const [result, setResult] = useState(null)
  const [menuResult, setMenuResult] = useState(null)
  const [error, setError] = useState(null)
  const [lastFile, setLastFile] = useState(null)
  const [lastBarcodeArgs, setLastBarcodeArgs] = useState(null)
  const [lastMenuFile, setLastMenuFile] = useState(null)
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
  // Bumped whenever something changes the saved history, which is how the
  // HistoryProvider knows to reload. A counter rather than an imperative
  // refresh handle: this component renders the provider, so it can't consume it.
  const [historyVersion, setHistoryVersion] = useState(0)

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
    // visitorHistory.length is a real dependency; initialLoadHandledRef above is
    // what keeps this to one run, not the (previously incomplete) dep list.
  }, [ready, isAuthed, visitorHistory.length])

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

  const onAuthExpired = useCallback(() => setUser(null), [])

  const doLogout = async () => {
    await logoutApi().catch(() => {})
    setUser(null)
    setVisitorHistory([])
  }

  // Anything derived from the account that lives on this device. Theme and
  // language are left alone deliberately — they're preferences, not a record of
  // the person, and resetting them would look like a bug rather than erasure.
  const clearLocalTraces = () => {
    ['mealProfile', 'dailyBudget', 'dbt_water_today', 'dbt_water_target'].forEach((key) =>
      localStorage.removeItem(key),
    )
    Object.keys(localStorage)
      .filter((key) => key.startsWith('dbt_seen_achievements_'))
      .forEach((key) => localStorage.removeItem(key))
  }

  // The server has already erased the account and ended every session by the
  // time this runs; this is purely the client catching up.
  const onAccountDeleted = () => {
    clearLocalTraces()
    setUser(null)
    setVisitorHistory([])
    setLocalProfile(null)
    setLocalBudget(DEFAULT_BUDGET)
    setDashboard(null)
    setPhase('capture')
    navigate('/', { replace: true })
  }

  // Shared across analyze() and lookupBarcode(): whichever request started
  // last "owns" the screen. Without this, tapping the Snap tab mid-analysis
  // (which resets to capture) and starting a second photo/scan means both
  // requests are in flight, and whichever happens to resolve last wins the
  // results screen — even if the user has already moved on.
  const requestSeqRef = useRef(0)
  // Lets the user stop an analysis. Separate from requestSeqRef, which only
  // decides whether a *returning* response still matters — it cannot stop the
  // request, so on a slow connection the app kept holding a server thread and
  // the user's data plan for a result nobody was waiting for any more.
  const abortRef = useRef(null)

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
        setHistoryVersion((v) => v + 1)
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

  /** Fresh AbortController per request; the previous one is already spent. */
  const startRequest = useCallback(() => {
    abortRef.current = new AbortController()
    return abortRef.current.signal
  }, [])

  /**
   * Stops the in-flight request and returns to the capture screen. Bumps the
   * sequence too, so a response already on the wire is ignored rather than
   * landing on a screen the user has left.
   */
  const cancelAnalysis = useCallback(() => {
    requestSeqRef.current += 1
    abortRef.current?.abort()
    setError(null)
    setPhase('capture')
  }, [])

  const analyze = useCallback(
    async (file) => {
      const seq = ++requestSeqRef.current
      setLastFile(file)
      setLastBarcodeArgs(null)
      setLastMenuFile(null)
      setPhase('analyzing')
      setError(null)
      try {
        const compressed = await compressImage(file)
        const analysis = await analyzeImage(compressed, 'meal.jpg', lang, startRequest())
        if (seq !== requestSeqRef.current) return // superseded by a newer analysis
        handleAnalysisSuccess(analysis)
      } catch (e) {
        if (seq !== requestSeqRef.current || e?.code === CANCELLED) return
        setError(e)
        setPhase('error')
      }
    },
    [lang, handleAnalysisSuccess, startRequest],
  )

  const lookupBarcode = useCallback(
    async (code, servings) => {
      const seq = ++requestSeqRef.current
      setLastFile(null)
      setLastBarcodeArgs({ code, servings })
      setLastMenuFile(null)
      setPhase('analyzing')
      setError(null)
      try {
        const analysis = await lookupBarcodeApi(code, servings, lang, startRequest())
        if (seq !== requestSeqRef.current) return
        handleAnalysisSuccess(analysis)
      } catch (e) {
        if (seq !== requestSeqRef.current || e?.code === CANCELLED) return
        setError(e)
        setPhase('error')
      }
    },
    [lang, handleAnalysisSuccess, startRequest],
  )

  // Ranking a menu is deliberately NOT routed through handleAnalysisSuccess:
  // it isn't a meal that was eaten, so it never touches the dashboard, the
  // visitor in-session history, or the first-run interstitial queue.
  const rankMenu = useCallback(
    async (file) => {
      const seq = ++requestSeqRef.current
      setLastFile(null)
      setLastBarcodeArgs(null)
      setLastMenuFile(file)
      setPhase('analyzing')
      setError(null)
      try {
        const compressed = await compressMenuImage(file)
        const ranking = await rankMenuImage(compressed, 'menu.jpg', lang, startRequest())
        if (seq !== requestSeqRef.current) return
        // Same distinction ResultsScreen relies on: persisted:false means
        // "not saved" for BOTH a plain never-signed-in visitor and a session
        // that expired mid-request — only the latter deserves the "your
        // sign-in expired" banner.
        const expired = isAuthed && ranking.persisted === false
        setSessionExpired(expired)
        if (expired) setUser(null)
        setMenuResult(ranking)
        setPhase('menuResults')
      } catch (e) {
        if (seq !== requestSeqRef.current || e?.code === CANCELLED) return
        setError(e)
        setPhase('error')
      }
    },
    [lang, isAuthed, startRequest],
  )

  const retry = () => {
    if (lastFile) {
      analyze(lastFile)
    } else if (lastMenuFile) {
      rankMenu(lastMenuFile)
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

  const snapFlow =
    phase === 'analyzing' ? (
      <AnalyzingScreen
        titleKey={lastMenuFile ? 'analyzing.titleMenu' : 'analyzing.title'}
        flow={lastMenuFile ? 'menu' : lastBarcodeArgs ? 'barcode' : 'meal'}
        onCancel={cancelAnalysis}
      />
    ) : phase === 'results' && result ? (
      <ResultsScreen
        result={result}
        dailyBudget={dailyBudget}
        goal={profileForm?.goal}
        onSnapAnother={() => setPhase('capture')}
        shareImageSource={lastFile}
        banner={sessionExpired ? t('results.sessionExpired') : undefined}
        // The warning belongs here, on the first result, rather than on the
        // History tab where it used to live — by the time a visitor goes
        // looking for their meals they have already logged the ones a refresh
        // is about to take.
        isVisitor={!isAuthed}
        // A fresh analysis is saved for signed-in users, so it can be exported
        // straight away. It used to require finding the meal again in history.
        onExportPdf={result.entryId ? () => exportHistoryPdf(result.entryId) : undefined}
        // A correction changes this meal's calories, so the dashboard and the
        // history list are both stale until the cache is invalidated.
        onResultCorrected={(corrected) => {
          setResult(corrected)
          setHistoryVersion((v) => v + 1)
        }}
      />
    ) : phase === 'menuResults' && menuResult ? (
      <MenuResultsScreen
        result={menuResult}
        onScanAnother={() => setPhase('capture')}
        banner={sessionExpired ? t('menuResults.notSaved') : undefined}
        shareImageSource={lastMenuFile}
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
        <CaptureScreen
          online={online}
          onPhoto={analyze}
          onScanBarcode={() => setPhase('barcode')}
          onScanMenu={rankMenu}
        />
      </>
    )

  return (
    <div className="mx-auto flex min-h-screen max-w-md flex-col bg-slate-50 dark:bg-slate-900">
      {/* First thing in the tab order, visible only once focused. Without it a
          keyboard or switch user tabs through the header and the whole account
          menu again on every single screen. */}
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:bg-slate-900 focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-white dark:focus:bg-slate-100 dark:focus:text-slate-900"
      >
        {t('nav.skipToContent')}
      </a>

      {!inSession && (
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
      )}

      <main
        id="main"
        tabIndex={-1}
        className={`flex-1 px-4 ${
          // No tab bar to clear during a session, so the padding that keeps
          // content above it would just be a gap under the primary button.
          inSession
            ? 'pb-[calc(1.5rem+env(safe-area-inset-bottom))]'
            : 'pb-[calc(6rem+env(safe-area-inset-bottom))]'
        }`}
      >
        {/* The two first-run interstitials are full-screen takeovers rather than
            destinations — you can't link someone to "your sign-in prompt" — so
            they short-circuit the route table instead of living in it. */}
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
        ) : (
          <HistoryProvider
            isVisitor={!isAuthed}
            visitorEntries={visitorHistory}
            onAuthExpired={onAuthExpired}
            version={historyVersion}
          >
          <Routes>
            <Route index element={snapFlow} />
            <Route
              path="history/*"
              element={
                <HistoryScreen
                  isVisitor={!isAuthed}
                  onDeleteVisitorEntry={(id) =>
                    setVisitorHistory((prev) => prev.filter((e) => e.id !== id))
                  }
                  dailyBudget={dailyBudget}
                  goal={profileForm?.goal}
                />
              }
            />
            <Route
              path="workout/*"
              element={
                <WorkoutProvider isVisitor={!isAuthed}>
                  <WorkoutScreen isVisitor={!isAuthed} online={online} />
                </WorkoutProvider>
              }
            />
            <Route
              path="analysis"
              element={
                <AnalysisScreen
                  isVisitor={!isAuthed}
                  dailyBudget={dailyBudget}
                  goal={isAuthed ? user.goal : null}
                />
              }
            />
            <Route
              path="profile"
              element={
                <ProfilePage
                  user={user}
                  isVisitor={!isAuthed}
                  hasProfile={hasProfile}
                  dailyBudget={dailyBudget}
                  onEditProfile={() => setShowProfileScreen(true)}
                  onLogout={doLogout}
                  onAccountDeleted={onAccountDeleted}
                />
              }
            />
            {/* An unknown path is most likely a stale bookmark or a mistyped
                deep link; replace so back doesn't bounce between the two. */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
          </HistoryProvider>
        )}
      </main>

      {/* pb-[env(...)] keeps the tab row clear of the iPhone home indicator in standalone mode */}
      {!inSession && (
      <nav
        aria-label={t('nav.sections')}
        className="fixed inset-x-0 bottom-0 z-10 mx-auto flex max-w-md border-t border-slate-200 bg-white/95 pb-[env(safe-area-inset-bottom)] backdrop-blur dark:border-slate-700 dark:bg-slate-800/95"
      >
        <TabButton
          active={tab === 'snap'}
          label={t('nav.snap')}
          icon="📸"
          onClick={() => {
            // Tapping Snap always returns to a fresh capture, even from a
            // results screen — same as before routing.
            setPhase('capture')
            navigate('/')
          }}
        />
        <TabButton
          active={tab === 'workout'}
          label={t('nav.workout')}
          icon="🏋️"
          onClick={() => navigate('/workout')}
        />
        <TabButton
          active={tab === 'history'}
          label={t('nav.history')}
          icon="🗓️"
          onClick={() => navigate('/history')}
        />
        <TabButton
          active={tab === 'analysis'}
          label={t('nav.analysis')}
          icon="📊"
          onClick={() => navigate('/analysis')}
        />
        <TabButton
          active={tab === 'profile'}
          label={t('nav.profile')}
          icon="👤"
          onClick={() => navigate('/profile')}
        />
      </nav>
      )}

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

/**
 * One destination in the bottom bar.
 *
 * The review asked for `role="tablist"`/`role="tab"`/`aria-selected` here.
 * **That is the wrong role for this bar** and it is not just pedantry: `tab`
 * promises an associated `tabpanel` that it shows and hides, which a screen
 * reader will then look for via `aria-controls`. These four buttons change the
 * route — there are no panels, and jsx-a11y rejects the landmark/role
 * combination outright.
 *
 * What the review actually complained about is real, though: four bare buttons
 * announced with no sense that they are a set or which one you are on. The
 * navigation landmark now carries a name, and the current destination is marked
 * with `aria-current="page"` — the standard for exactly this, and it says the
 * true thing rather than a convenient one.
 *
 * The emoji is `aria-hidden`. It is decoration beside a text label that already
 * says the same thing, and unhidden it was read out as "camera with flash
 * emoji, Snap".
 */
function TabButton({ active, label, icon, onClick }) {
  return (
    <button
      aria-current={active ? 'page' : undefined}
      onClick={onClick}
      className={`flex flex-1 flex-col items-center gap-0.5 py-3 text-xs font-medium ${
        active ? 'text-grade-aplus dark:text-green-400' : 'text-slate-500 dark:text-slate-400'
      }`}
    >
      <span aria-hidden="true" className="text-lg leading-none">{icon}</span>
      {label}
    </button>
  )
}
