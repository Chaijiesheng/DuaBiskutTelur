import { useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * Workout setup: six questions, one decision per screen.
 *
 * <p>Chosen over the single-page form the prototype also offered, and the reason
 * is the keyboard: every answer here is a tap on a fixed option, so a
 * one-per-screen flow needs no typing, no scrolling and no submit button on four
 * of its six steps. A form asking the same six things puts a wall of controls in
 * front of somebody who has not yet seen a single workout.
 *
 * <p>Single-select advances on tap. Multi-select cannot, because tapping is how
 * you add a second answer — those two steps get an explicit Continue, and the
 * last one is skippable outright.
 */

/**
 * The six questions in order.
 *
 * <p>`key` and every option value are the server's vocabulary tags verbatim, not
 * display text. Labels come from the translation catalog, so switching language
 * changes what is read and never what is sent.
 */
const STEPS = [
  { key: 'goal', field: 'goal', cols: 1, options: ['lose_weight', 'build_muscle', 'maintain', 'general_fitness'] },
  { key: 'level', field: 'level', cols: 1, options: ['beginner', 'intermediate', 'advanced'] },
  { key: 'days', field: 'daysPerWeek', cols: 5, numeric: true, options: [1, 2, 3, 4, 5] },
  { key: 'minutes', field: 'sessionMinutes', cols: 2, numeric: true, options: [15, 30, 45, 60] },
  { key: 'equipment', field: 'equipment', cols: 2, multi: true, options: ['none', 'dumbbells', 'bands', 'gym'] },
  {
    key: 'preferences',
    field: 'preferences',
    cols: 2,
    multi: true,
    skippable: true,
    options: ['strength', 'cardio', 'mobility', 'running', 'home'],
  },
]

const EMPTY = {
  goal: null,
  level: null,
  daysPerWeek: null,
  sessionMinutes: null,
  equipment: [],
  preferences: [],
}

export default function WorkoutOnboarding({ onSave, onCancel, saving, error }) {
  const { t } = useLanguage()
  const [stepIndex, setStepIndex] = useState(0)
  const [answers, setAnswers] = useState(EMPTY)

  const step = STEPS[stepIndex]
  const isLast = stepIndex === STEPS.length - 1

  const submit = (finalAnswers) => {
    onSave({
      ...finalAnswers,
      // The server rejects anything outside its vocabulary, so an unanswered
      // required question must not be sent as a guess.
      equipment: finalAnswers.equipment.length > 0 ? finalAnswers.equipment : ['none'],
    })
  }

  const advance = (next) => {
    if (isLast) submit(next)
    else setStepIndex((i) => i + 1)
  }

  const pick = (value) => {
    if (step.multi) {
      const list = answers[step.field]
      setAnswers({
        ...answers,
        [step.field]: list.includes(value) ? list.filter((v) => v !== value) : [...list, value],
      })
      return
    }
    const next = { ...answers, [step.field]: value }
    setAnswers(next)
    advance(next)
  }

  const back = () => {
    if (stepIndex === 0) onCancel()
    else setStepIndex((i) => i - 1)
  }

  const isOn = (value) =>
    step.multi ? answers[step.field].includes(value) : answers[step.field] === value

  const label = (value) =>
    step.numeric && step.field === 'sessionMinutes'
      ? t('workout.options.minutes', value)
      : step.numeric
        ? String(value)
        : t(`workout.options.${value}`)

  const gridClass = { 1: 'grid-cols-1', 2: 'grid-cols-2', 5: 'grid-cols-5' }[step.cols]

  return (
    <div className="pt-2">
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={back}
          aria-label={t('workout.back')}
          className="h-9 w-9 shrink-0 rounded-xl border border-slate-200 text-slate-600 dark:border-slate-700 dark:text-slate-300"
        >
          ←
        </button>
        {/* Decorative: the step count beside it is the accessible version, and a
            row of six bars announced individually is noise. */}
        <div aria-hidden="true" className="flex flex-1 gap-1">
          {STEPS.map((s, i) => (
            <span
              key={s.key}
              className={`h-1 flex-1 rounded-full ${
                i <= stepIndex ? 'bg-grade-aplus dark:bg-green-400' : 'bg-slate-200 dark:bg-slate-700'
              }`}
            />
          ))}
        </div>
        <span className="shrink-0 text-xs font-bold text-slate-500 dark:text-slate-400">
          {t('workout.stepOf', stepIndex + 1, STEPS.length)}
        </span>
      </div>

      <div className="pt-6">
        <p className="text-xs font-bold uppercase tracking-wide text-grade-aplus dark:text-green-400">
          {t(`workout.questions.${step.key}.kicker`)}
        </p>
        <h2 className="mt-2 text-2xl font-extrabold leading-tight tracking-tight text-slate-900 dark:text-slate-100">
          {t(`workout.questions.${step.key}.title`)}
        </h2>
        <p className="mt-2 text-sm leading-relaxed text-slate-500 dark:text-slate-400">
          {t(`workout.questions.${step.key}.help`)}
        </p>
      </div>

      <div className={`mt-5 grid gap-2.5 ${gridClass}`}>
        {step.options.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={isOn(value)}
            onClick={() => pick(value)}
            className={`flex min-h-[3.5rem] items-center justify-between gap-2 rounded-2xl border-2 px-4 py-3 text-left text-base transition ${
              isOn(value)
                ? 'border-grade-aplus bg-green-50 font-extrabold text-grade-aplus dark:border-green-400 dark:bg-green-950/40 dark:text-green-400'
                : 'border-slate-200 bg-white font-semibold text-slate-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200'
            } ${step.cols === 5 ? 'justify-center px-1 text-center' : ''}`}
          >
            <span>{label(value)}</span>
            {step.cols !== 5 && <span aria-hidden="true" className="text-sm font-extrabold">{isOn(value) ? '✓' : ''}</span>}
          </button>
        ))}
      </div>

      {error && (
        <p role="alert" className="mt-4 text-sm text-red-600 dark:text-red-400">
          {error}
        </p>
      )}

      {/* Multi-select can't advance on tap — tapping is how you add a second
          answer — so these two steps get an explicit button. */}
      {step.multi && (
        <button
          type="button"
          disabled={saving}
          onClick={() => advance(answers)}
          className="mt-5 min-h-[3.25rem] w-full rounded-2xl bg-grade-aplus py-4 text-base font-extrabold text-white shadow-sm disabled:opacity-60"
        >
          {t('workout.continue')}
        </button>
      )}
      {step.skippable && (
        <button
          type="button"
          disabled={saving}
          onClick={() => advance(answers)}
          className="w-full py-3 text-xs font-semibold text-slate-500 disabled:opacity-60 dark:text-slate-400"
        >
          {t('workout.skipStep')}
        </button>
      )}
    </div>
  )
}

export { STEPS }
