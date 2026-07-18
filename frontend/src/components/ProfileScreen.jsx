import { useMemo, useState } from 'react'
import {
  EXERCISE_OPTIONS,
  GOAL_OPTIONS,
  calculateDailyBudget,
  calculateProteinTarget,
  isProfileComplete,
} from '../calorieCalculator.js'
import AccordionSection from './AccordionSection.jsx'
import FoodEquivalents from './FoodEquivalents.jsx'
import { useLanguage } from '../i18n/LanguageContext.jsx'

const EMPTY = { age: '', sex: '', weightKg: '', heightCm: '', steps: '', exerciseFrequency: '', goal: '' }

const GOAL_COLORS = {
  weight_loss: 'border-blue-600 bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400',
  muscle_gain: 'border-amber-600 bg-amber-50 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400',
  maintenance: 'border-grade-aplus bg-green-50 text-grade-aplus dark:bg-green-900/30 dark:text-green-400',
}

export default function ProfileScreen({ initialProfile, currentDailyBudget, allowSkip, onSave, onCancel }) {
  const { t } = useLanguage()
  const [form, setForm] = useState(() => ({ ...EMPTY, ...initialProfile }))
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [confirming, setConfirming] = useState(false)

  const set = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }))

  const previewBudget = useMemo(() => calculateDailyBudget(form), [form])
  const previewProtein = useMemo(() => calculateProteinTarget(form), [form])
  const oldProtein = useMemo(() => calculateProteinTarget(initialProfile), [initialProfile])
  const complete = isProfileComplete(form)

  // Editing an already-complete profile (not first-time setup) and the goal/
  // formula inputs would actually change the numbers — worth a confirm step
  // so the user sees the impact before it applies, especially for a goal switch.
  // Compared against the user's actual current budget (which may have been
  // manually overridden via the account menu), not a value recomputed from
  // the old profile fields — otherwise a custom budget silently gets replaced
  // by the formula's result without ever showing the real before/after.
  const isEditingExisting = Boolean(initialProfile && isProfileComplete(initialProfile))
  const budgetWillChange =
    isEditingExisting && (currentDailyBudget !== previewBudget || oldProtein !== previewProtein)

  const buildPayload = () => ({
    age: Number(form.age),
    sex: form.sex,
    weightKg: Number(form.weightKg),
    heightCm: Number(form.heightCm),
    steps: form.steps === '' ? null : Number(form.steps),
    exerciseFrequency: form.exerciseFrequency,
    goal: form.goal,
  })

  const submit = (e) => {
    e.preventDefault()
    if (!complete) return
    if (budgetWillChange) {
      setConfirming(true)
      return
    }
    onSave(buildPayload())
  }

  const applyChange = () => {
    onSave(buildPayload())
  }

  if (confirming) {
    return (
      <div className="pb-6 pt-2">
        <div className="mb-5 text-center">
          <span className="text-4xl">📊</span>
          <h2 className="mt-2 text-lg font-bold text-slate-900 dark:text-slate-100">
            {t('profileScreen.confirmChange.title')}
          </h2>
          <p className="mx-auto mt-1 max-w-xs text-xs text-slate-500 dark:text-slate-400">
            {t('profileScreen.confirmChange.body')}
          </p>
        </div>

        <div className="space-y-3 rounded-2xl bg-slate-50 p-4 dark:bg-slate-700">
          <ChangeRow label={t('profileScreen.confirmChange.calories')} from={currentDailyBudget} to={previewBudget} unit="kcal" />
          <ChangeRow label={t('profileScreen.confirmChange.protein')} from={oldProtein} to={previewProtein} unit="g" />
        </div>

        <button
          type="button"
          onClick={applyChange}
          className="mt-5 w-full rounded-2xl bg-grade-aplus py-3.5 text-sm font-bold text-white shadow-md"
        >
          {t('profileScreen.confirmChange.apply')}
        </button>
        <button
          type="button"
          onClick={() => setConfirming(false)}
          className="w-full py-1 text-center text-xs font-medium text-slate-500 dark:text-slate-400"
        >
          {t('profileScreen.confirmChange.back')}
        </button>
      </div>
    )
  }

  return (
    <div className="pb-6 pt-2">
      <div className="mb-5 text-center">
        <span className="text-4xl">🔥</span>
        <h2 className="mt-2 text-lg font-bold text-slate-900 dark:text-slate-100">{t('profileScreen.heading')}</h2>
        <p className="mx-auto mt-1 max-w-xs text-xs text-slate-500 dark:text-slate-400">{t('profileScreen.body')}</p>
      </div>

      <form onSubmit={submit} className="space-y-4">
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('profileScreen.age')}>
            <input
              type="number"
              min="10"
              max="100"
              required
              value={form.age}
              onChange={set('age')}
              className="input"
              placeholder="30"
            />
          </Field>
          <Field label={t('profileScreen.sex')}>
            <select required value={form.sex} onChange={set('sex')} className="input">
              <option value="" disabled>
                {t('profileScreen.select')}
              </option>
              <option value="male">{t('profileScreen.male')}</option>
              <option value="female">{t('profileScreen.female')}</option>
            </select>
          </Field>
          <Field label={t('profileScreen.weightKg')}>
            <input
              type="number"
              min="30"
              max="250"
              step="0.1"
              required
              value={form.weightKg}
              onChange={set('weightKg')}
              className="input"
              placeholder="65"
            />
          </Field>
          <Field label={t('profileScreen.heightCm')}>
            <input
              type="number"
              min="100"
              max="230"
              required
              value={form.heightCm}
              onChange={set('heightCm')}
              className="input"
              placeholder="170"
            />
          </Field>
        </div>

        <div>
          <label className="text-xs font-semibold text-slate-700 dark:text-slate-300">{t('profileScreen.exerciseFrequency')}</label>
          <div className="mt-1.5 grid grid-cols-3 gap-2">
            {EXERCISE_OPTIONS.map((opt) => (
              <button
                type="button"
                key={opt.value}
                onClick={() => setForm((f) => ({ ...f, exerciseFrequency: opt.value }))}
                className={`rounded-xl border px-2 py-2.5 text-center text-xs font-semibold transition ${
                  form.exerciseFrequency === opt.value
                    ? 'border-grade-aplus bg-green-50 text-grade-aplus dark:bg-green-900/30 dark:text-green-400'
                    : 'border-slate-200 text-slate-500 dark:border-slate-600 dark:text-slate-400'
                }`}
              >
                {t(`profileScreen.exercise.${opt.value}.label`)}
                <span className="mt-0.5 block text-[10px] font-normal text-slate-500 dark:text-slate-400">
                  {t(`profileScreen.exercise.${opt.value}.hint`)}
                </span>
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="text-xs font-semibold text-slate-700 dark:text-slate-300">{t('profileScreen.goalLabel')}</label>
          <div className="mt-1.5 grid grid-cols-3 gap-2">
            {GOAL_OPTIONS.map((opt) => (
              <button
                type="button"
                key={opt.value}
                onClick={() => setForm((f) => ({ ...f, goal: opt.value }))}
                className={`rounded-xl border px-2 py-2.5 text-center text-xs font-semibold transition ${
                  form.goal === opt.value
                    ? GOAL_COLORS[opt.value]
                    : 'border-slate-200 text-slate-500 dark:border-slate-600 dark:text-slate-400'
                }`}
              >
                {t(`profileScreen.goal.${opt.value}.label`)}
                <span className="mt-0.5 block text-[10px] font-normal text-slate-500 dark:text-slate-400">
                  {t(`profileScreen.goal.${opt.value}.hint`)}
                </span>
              </button>
            ))}
          </div>
        </div>

        <AccordionSection
          title={t('profileScreen.advanced')}
          isOpen={advancedOpen}
          onToggle={() => setAdvancedOpen((v) => !v)}
        >
          <Field label={t('profileScreen.dailySteps')}>
            <input
              type="number"
              min="0"
              max="60000"
              value={form.steps}
              onChange={set('steps')}
              className="input"
              placeholder={t('profileScreen.dailyStepsPlaceholder')}
            />
          </Field>
          <p className="mt-1.5 text-[11px] text-slate-500 dark:text-slate-400">{t('profileScreen.dailyStepsHint')}</p>
        </AccordionSection>

        {previewBudget != null && (
          <div className="rounded-2xl bg-green-50 px-4 py-3 text-center dark:bg-green-900/30">
            <p className="text-[11px] font-medium uppercase tracking-wide text-green-700 dark:text-green-400">
              {t('profileScreen.estimatedBudget')}
            </p>
            <p className="text-2xl font-black text-green-700 dark:text-green-400">{previewBudget} kcal</p>
            <FoodEquivalents calories={previewBudget} />
          </div>
        )}

        <button
          type="submit"
          disabled={!complete}
          className="w-full rounded-2xl bg-grade-aplus py-3.5 text-sm font-bold text-white shadow-md disabled:opacity-40"
        >
          {t('profileScreen.save')}
        </button>

        {allowSkip && (
          <button
            type="button"
            onClick={onCancel}
            className="w-full py-1 text-center text-xs font-medium text-slate-500 dark:text-slate-400"
          >
            {t('profileScreen.skip')}
          </button>
        )}
        {!allowSkip && onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="w-full py-1 text-center text-xs font-medium text-slate-500 dark:text-slate-400"
          >
            {t('profileScreen.cancel')}
          </button>
        )}
      </form>
    </div>
  )
}

function Field({ label, children }) {
  return (
    <label className="block">
      <span className="text-xs font-semibold text-slate-700 dark:text-slate-300">{label}</span>
      <div className="mt-1.5">{children}</div>
    </label>
  )
}

function ChangeRow({ label, from, to, unit }) {
  return (
    <div className="flex items-center justify-between text-sm">
      <span className="text-slate-500 dark:text-slate-400">{label}</span>
      <span className="font-semibold text-slate-900 dark:text-slate-100">
        {from} → {to} <span className="text-xs font-normal text-slate-500 dark:text-slate-400">{unit}</span>
      </span>
    </div>
  )
}
