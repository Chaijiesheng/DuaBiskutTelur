import { useState } from 'react'
import { useLanguage } from '../i18n/LanguageContext.jsx'
import {
  FALLBACK_ASPECT,
  MAX_DEMO_HEIGHT,
  demoAsset,
  demoUrl,
  expectedAssetPath,
} from '../workout/exerciseAssets.js'

/**
 * The exercise demonstration: a looping GIF, shown as a plain image.
 *
 * <p>The file is the authority on what the movement looks like. This component
 * does not drive the animation, re-time it, or draw anything over it — the GIF
 * loops on its own, which is the one thing GIFs are reliably good at.
 *
 * <p>Two things it refuses to do:
 *
 * <p>It never shifts the layout. The box is reserved at the authored 16:9 ratio
 * before the image arrives, so the reps and the Complete-set button underneath
 * do not jump when a 300 KB file finishes decoding — the moment a user is most
 * likely to be reaching for the button.
 *
 * <p>It never shows a broken image. An exercise with no registered asset, or a
 * registered asset that fails to load, both land on the same honest state.
 * There is no substitute demonstration and no generated fallback: showing the
 * wrong movement is worse than showing none, because the user would do it.
 */
export default function ExerciseDemo({ exerciseKey, name, priority = false, rounded = 'rounded-2xl' }) {
  const { t } = useLanguage()
  const [failed, setFailed] = useState(false)
  const asset = demoAsset(exerciseKey)
  const url = demoUrl(exerciseKey)
  // The unavailable state reserves the same shape a landscape demonstration
  // would, so a missing file does not resize the card relative to its neighbours.
  const shape = asset ?? FALLBACK_ASPECT
  const box = {
    aspectRatio: `${shape.width} / ${shape.height}`,
    maxHeight: MAX_DEMO_HEIGHT,
  }

  const frame = `relative flex w-full items-center justify-center overflow-hidden ${rounded}`
    + ' border border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-900/60'

  if (!url || failed) {
    return (
      <div className={frame} style={box}>
        <div className="px-4 text-center">
          <p className="text-xs font-bold text-slate-500 dark:text-slate-400">
            {t('workout.demoUnavailable')}
          </p>
          {/* The fix instruction, for whoever is preparing assets. Stripped from
              production builds — a file path is not something to show a user. */}
          {import.meta.env.DEV && (
            <p className="mt-1 break-all font-mono text-[0.6rem] text-slate-400 dark:text-slate-500">
              {expectedAssetPath(exerciseKey)}
            </p>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className={frame} style={box}>
      <img
        src={url}
        alt={t('workout.demoAlt', name)}
        width={asset.width}
        height={asset.height}
        // Eager only for the exercise actually in front of you. Everything else
        // — the whole preview list — waits until it is scrolled to, which keeps
        // opening a session from pulling several megabytes at once.
        loading={priority ? 'eager' : 'lazy'}
        decoding="async"
        onError={() => setFailed(true)}
        className="h-full w-full object-contain"
      />
    </div>
  )
}
