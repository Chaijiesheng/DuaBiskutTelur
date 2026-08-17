import { useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'

/**
 * The completion screen.
 *
 * <p>Two questions, both optional, and the coach's reply appears only once one
 * of them is answered. That order is the whole point: a reaction printed before
 * you say anything is decoration, and a reaction printed after is the app
 * demonstrating it listened. It is also literally true here — "too hard" takes a
 * set off the next session, and the reply says so.
 */
const FEELS = ['too_easy', 'just_right', 'too_hard']
const ENERGIES = ['great', 'normal', 'tired']

export default function WorkoutComplete({ summary, onSubmit, onDone, submitting }) {
  const { t } = useLanguage()
  const [feel, setFeel] = useState(null)
  const [energy, setEnergy] = useState(null)

  const pick = (nextFeel, nextEnergy) => {
    setFeel(nextFeel)
    setEnergy(nextEnergy)
    onSubmit({ feel: nextFeel, energy: nextEnergy })
  }

  return (
    <div className="pt-2">
      <div className="pt-4 text-center">
        <span
          aria-hidden="true"
          className="inline-flex h-16 w-16 items-center justify-center rounded-full bg-green-50 text-3xl dark:bg-green-950/40"
        >
          ✓
        </span>
        <h2 className="mt-3.5 text-2xl font-black tracking-tight text-slate-900 dark:text-slate-100">
          {t('workout.completeTitle')}
        </h2>
        <p className="mx-auto mt-1.5 max-w-[16rem] text-sm leading-relaxed text-slate-500 dark:text-slate-400">
          {t('workout.completeBody')}
        </p>
      </div>

      <div className="mt-5 grid grid-cols-3 gap-2">
        <DoneStat value={summary.minutes} label={t('workout.statMinutes')} />
        <DoneStat value={summary.exercises} label={t('workout.statExercises')} />
        <DoneStat value={summary.sets} label={t('workout.statSets')} />
      </div>

      <p className="mb-2.5 mt-6 px-0.5 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('workout.howDidItFeel')}
      </p>
      <div className="grid grid-cols-3 gap-2">
        {FEELS.map((value) => (
          <Choice
            key={value}
            label={t(`workout.feel.${value}`)}
            on={feel === value}
            onClick={() => pick(value, energy)}
          />
        ))}
      </div>

      <p className="mb-2.5 mt-5 px-0.5 text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {t('workout.energyNow')}{' '}
        <span className="font-semibold normal-case tracking-normal">{t('workout.optional')}</span>
      </p>
      <div className="grid grid-cols-3 gap-2">
        {ENERGIES.map((value) => (
          <Choice
            key={value}
            label={t(`workout.energy.${value}`)}
            on={energy === value}
            onClick={() => pick(feel, value)}
          />
        ))}
      </div>

      {summary.coachReply && (
        <section className="mt-4 rounded-2xl bg-green-50 px-4 py-3.5 dark:bg-green-950/40">
          <div className="flex items-center gap-2">
            <span aria-hidden="true" className="text-sm">💬</span>
            <p className="text-[0.7rem] font-extrabold uppercase tracking-wider text-grade-aplus dark:text-green-400">
              {t('workout.coach')}
            </p>
          </div>
          <p className="mt-2 text-sm leading-relaxed text-slate-700 dark:text-slate-200">
            {summary.coachReply}
          </p>
        </section>
      )}

      <button
        type="button"
        onClick={onDone}
        disabled={submitting}
        className="mt-5 min-h-[3.25rem] w-full rounded-2xl bg-grade-aplus py-4 text-base font-extrabold text-white shadow-sm disabled:opacity-60"
      >
        {t('workout.finish')}
      </button>
    </div>
  )
}

function DoneStat({ value, label }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-3 text-center shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <p className="text-xl font-black tracking-tight text-slate-900 dark:text-slate-100">{value}</p>
      <p className="mt-0.5 text-[0.65rem] font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {label}
      </p>
    </div>
  )
}

function Choice({ label, on, onClick }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={`min-h-[3.25rem] rounded-2xl border-2 px-2 py-3 text-sm ${
        on
          ? 'border-grade-aplus bg-green-50 font-extrabold text-grade-aplus dark:border-green-400 dark:bg-green-950/40 dark:text-green-400'
          : 'border-slate-200 bg-white font-semibold text-slate-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200'
      }`}
    >
      {label}
    </button>
  )
}
