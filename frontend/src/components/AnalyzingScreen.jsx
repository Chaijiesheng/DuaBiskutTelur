import { useRef, useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'

function pickInsight(count, excludeIndex) {
  if (count <= 1) return 0
  let next
  do {
    next = Math.floor(Math.random() * count)
  } while (next === excludeIndex)
  return next
}

export default function AnalyzingScreen() {
  const { t } = useLanguage()
  const insights = t('analyzing.insights')
  const [index, setIndex] = useState(() => Math.floor(Math.random() * insights.length))
  const [visible, setVisible] = useState(true)
  const [tapping, setTapping] = useState(false)
  const indexRef = useRef(index)
  indexRef.current = index

  const handleTap = () => {
    setTapping(true)
    setTimeout(() => setTapping(false), 450)
    setVisible(false)
    setTimeout(() => {
      const next = pickInsight(insights.length, indexRef.current)
      setIndex(next)
      setVisible(true)
    }, 220)
  }

  return (
    <div className="flex flex-col items-center gap-6 pt-16">
      <p className="text-sm text-slate-500 dark:text-slate-400">{t('analyzing.title')}…</p>

      <div className="relative flex h-28 w-28 items-center justify-center">
        <div className="absolute h-full w-full rounded-full bg-grade-aplus/10 motion-safe:animate-soft-pulse" />
        <div
          className="absolute h-[78%] w-[78%] rounded-full bg-grade-aplus/15 motion-safe:animate-soft-pulse"
          style={{ animationDelay: '0.7s' }}
        />
        <button
          type="button"
          onClick={handleTap}
          aria-label={t('analyzing.title')}
          className={`select-none border-0 bg-transparent p-0 text-5xl leading-none [-webkit-tap-highlight-color:transparent] ${
            tapping ? 'motion-safe:animate-chopstick-tap' : 'motion-safe:animate-chopstick-float'
          }`}
        >
          🥢
        </button>
      </div>

      <p
        className={`min-h-[3.5rem] max-w-xs text-center text-sm leading-relaxed text-slate-600 transition-opacity duration-200 dark:text-slate-300 ${
          visible ? 'opacity-100' : 'opacity-0'
        }`}
      >
        {insights[index]}
      </p>
    </div>
  )
}
