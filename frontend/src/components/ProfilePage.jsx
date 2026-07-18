import { useEffect, useState } from 'react'
import { fetchAchievements } from '../api.js'
import { EXERCISE_OPTIONS } from '../calorieCalculator.js'
import { Avatar } from './AccountMenu.jsx'
import SignInBanner from './SignInBanner.jsx'
import AccordionSection from './AccordionSection.jsx'
import AchievementUnlockOverlay from './AchievementUnlockOverlay.jsx'
import AchievementDetailModal from './AchievementDetailModal.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import LanguageThemePicker from './LanguageThemePicker.jsx'

const SEEN_KEY_PREFIX = 'dbt_seen_achievements_'

function loadSeenIds(userKey) {
  try {
    const raw = localStorage.getItem(SEEN_KEY_PREFIX + userKey)
    return raw ? new Set(JSON.parse(raw)) : new Set()
  } catch {
    return new Set()
  }
}

function saveSeenIds(userKey, ids) {
  try {
    localStorage.setItem(SEEN_KEY_PREFIX + userKey, JSON.stringify([...ids]))
  } catch {
    // private mode / quota errors — losing the "seen" record just means an
    // old unlock replays its animation once more, which is harmless
  }
}

/** Dedicated Profile tab: identity, then accordion sections (only one open at a time). */
export default function ProfilePage({ user, isVisitor, hasProfile, dailyBudget, onEditProfile, onLogout }) {
  const { t, lang } = useLanguage()
  const [achievements, setAchievements] = useState(null)
  // Collapsed by default; opening one section closes any other that was open.
  const [openSection, setOpenSection] = useState(null)
  const toggleSection = (key) => setOpenSection((prev) => (prev === key ? null : key))
  const [unlockQueue, setUnlockQueue] = useState([])
  const [selectedBadge, setSelectedBadge] = useState(null)

  useEffect(() => {
    if (isVisitor) return
    fetchAchievements(lang)
      .then((data) => {
        setAchievements(data)
        const userKey = user?.email || 'anon'
        const seen = loadSeenIds(userKey)
        const newlyUnlocked = data.badges.filter((b) => b.unlocked && !seen.has(b.id))
        if (newlyUnlocked.length > 0) {
          setUnlockQueue(newlyUnlocked)
          const updated = new Set(seen)
          data.badges.forEach((b) => b.unlocked && updated.add(b.id))
          saveSeenIds(userKey, updated)
        }
      })
      .catch(() => {})
  }, [isVisitor, user?.email, lang])

  if (isVisitor) {
    return (
      <div className="pt-14 text-center">
        <span className="text-5xl">👤</span>
        <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">{t('profile.signInPrompt')}</p>
        <div className="mt-4">
          <SignInBanner text={t('profile.signInCta')} />
        </div>
        <div className="mx-auto mt-5 w-full max-w-xs">
          <LanguageThemePicker />
        </div>
        <a
          href="/privacy.html"
          target="_blank"
          rel="noopener noreferrer"
          className="mt-4 inline-block text-xs font-medium text-slate-500 underline dark:text-slate-400"
        >
          {t('profile.privacyPolicy')}
        </a>
      </div>
    )
  }

  const exerciseValue = user.exerciseFrequency
  const exerciseLabel = EXERCISE_OPTIONS.find((o) => o.value === exerciseValue)
    ? t(`profileScreen.exercise.${exerciseValue}.label`)
    : null
  const initial = (user.name || user.email || '?').charAt(0).toUpperCase()

  return (
    <div className="space-y-3 pt-2">
      <section className="flex items-center gap-4 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <Avatar user={user} initial={initial} large />
        <div className="min-w-0">
          <p className="truncate text-base font-bold text-slate-900 dark:text-slate-100">{user.name || t('profile.you')}</p>
          <p className="truncate text-xs text-slate-500 dark:text-slate-400">{user.email}</p>
        </div>
      </section>

      <AccordionSection
        title={t('profile.profileDetails')}
        isOpen={openSection === 'details'}
        onToggle={() => toggleSection('details')}
      >
        {hasProfile ? (
          <div className="grid grid-cols-2 gap-3">
            <Detail label={t('profile.age')} value={user.age} />
            <Detail label={t('profile.sex')} value={user.sex ? t(`profile.${user.sex}`) : '—'} />
            <Detail label={t('profile.weight')} value={user.weightKg ? `${user.weightKg} kg` : '—'} />
            <Detail label={t('profile.height')} value={user.heightCm ? `${user.heightCm} cm` : '—'} />
            <Detail label={t('profile.activity')} value={exerciseLabel || '—'} />
            <Detail label={t('profile.dailyGoal')} value={`${dailyBudget} kcal`} />
          </div>
        ) : (
          <p className="text-sm text-slate-500 dark:text-slate-400">{t('profile.noProfileYet')}</p>
        )}
        <button
          onClick={onEditProfile}
          className="mt-4 w-full rounded-2xl border border-slate-300 py-2.5 text-sm font-semibold text-slate-700 dark:border-slate-600 dark:text-slate-300"
        >
          {hasProfile ? t('profile.editProfile') : t('profile.setUpProfile')}
        </button>
      </AccordionSection>

      <AccordionSection
        title={t('profile.achievements')}
        badge={
          achievements && achievements.currentStreakDays > 0 ? (
            <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">
              {t('profile.dayStreak', achievements.currentStreakDays)}
            </span>
          ) : null
        }
        isOpen={openSection === 'achievements'}
        onToggle={() => toggleSection('achievements')}
      >
        {!achievements ? (
          <p className="text-sm text-slate-500 dark:text-slate-400">{t('history.loading')}</p>
        ) : (
          <div className="grid grid-cols-3 gap-3">
            {achievements.badges.map((badge) => (
              <AchievementCard
                key={badge.id}
                badge={badge}
                onSelect={() => badge.unlocked && setSelectedBadge(badge)}
              />
            ))}
          </div>
        )}
      </AccordionSection>

      <AccordionSection
        title={t('profile.settings')}
        isOpen={openSection === 'settings'}
        onToggle={() => toggleSection('settings')}
      >
        <LanguageThemePicker showLabels />

        <a
          href="/privacy.html"
          target="_blank"
          rel="noopener noreferrer"
          className="mt-3 block w-full rounded-2xl border border-slate-200 py-2.5 text-center text-sm font-semibold text-slate-600 dark:border-slate-600 dark:text-slate-300"
        >
          🔒 {t('profile.privacyPolicy')}
        </a>
        <button
          onClick={onLogout}
          className="mt-3 w-full rounded-2xl border border-slate-200 py-2.5 text-sm font-semibold text-slate-600 dark:border-slate-600 dark:text-slate-300"
        >
          {t('profile.logOut')}
        </button>
      </AccordionSection>

      {unlockQueue.length > 0 && (
        <AchievementUnlockOverlay
          badge={unlockQueue[0]}
          onDismiss={() => setUnlockQueue((q) => q.slice(1))}
        />
      )}
      {selectedBadge && (
        <AchievementDetailModal badge={selectedBadge} onClose={() => setSelectedBadge(null)} />
      )}
    </div>
  )
}

function AchievementCard({ badge, onSelect }) {
  const { t } = useLanguage()
  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={!badge.unlocked}
      className={`flex flex-col items-center gap-1 rounded-xl border p-3 text-center transition ${
        badge.unlocked
          ? 'border-grade-aplus bg-green-50 active:scale-95 dark:bg-green-900/30'
          : 'border-slate-200 bg-slate-50 opacity-50 dark:border-slate-600 dark:bg-slate-700'
      }`}
    >
      <span className="text-2xl">{badge.icon}</span>
      <span className="text-[10px] font-semibold leading-tight text-slate-700 dark:text-slate-300">{badge.label}</span>
      {!badge.unlocked && (
        <span className="text-[9px] font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{t('profile.locked')}</span>
      )}
    </button>
  )
}

function Detail({ label, value }) {
  return (
    <div className="rounded-xl bg-slate-50 px-3 py-2.5 dark:bg-slate-700">
      <p className="text-[11px] font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">{label}</p>
      <p className="mt-0.5 text-sm font-bold text-slate-900 dark:text-slate-100">{value}</p>
    </div>
  )
}
