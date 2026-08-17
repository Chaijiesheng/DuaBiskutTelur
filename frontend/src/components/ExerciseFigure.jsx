import { BODY_PARTS, LIMB, POSES, hasFigure } from '../workout/exerciseRig.js'

/**
 * The looping figure shown while an exercise is in front of you.
 *
 * <p>Five poses cross-faded over 3.2 seconds — down through the rep and back up.
 * The loop runs on its own rather than waiting to be tapped: a demonstration you
 * have to ask for is one most people never see, and it costs a CSS opacity
 * cycle.
 *
 * <p>Two things it refuses to do. It never renders an empty box for an exercise
 * nobody has posed yet — the catalogue has 59 rows and the poses arrive
 * progressively, so the fallback is a real part of the design rather than an
 * error state. And under `prefers-reduced-motion` it stops looping and shows
 * every pose at once, which carries the same information without the movement.
 *
 * <p>Decorative by construction: the exercise name, the dose and the cue are all
 * real text beside it, so this is `aria-hidden` rather than carrying a
 * description that would duplicate them.
 */
export default function ExerciseFigure({ exerciseKey, pattern, variant = 'loop' }) {
  if (!hasFigure(exerciseKey)) {
    return <PatternGlyph pattern={pattern} />
  }

  const rig = POSES[exerciseKey]
  const [, , width, height] = rig.view
  const isHold = rig.poses.length === 1
  // `all` is the How-to sheet's view and the reduced-motion view: every pose at
  // once, oldest faintest, so the shape of the movement reads without motion.
  const showAll = variant === 'all' || isHold

  return (
    <svg
      viewBox={rig.view.join(' ')}
      className={`h-full w-auto max-w-full ${showAll ? 'rig-all' : 'rig-loop'}`}
      aria-hidden="true"
      focusable="false"
      width={width}
      height={height}
    >
      <defs>
        {BODY_PARTS.map((part) => {
          const Tag = part.tag
          return <Tag key={part.id} id={part.id} {...part.attrs} />
        })}
      </defs>
      <line
        x1={rig.ground[0]}
        y1={rig.ground[1]}
        x2={rig.ground[2]}
        y2={rig.ground[3]}
        className="stroke-slate-200 dark:stroke-slate-700"
        strokeWidth="2"
      />
      {rig.poses.map((p, i) => (
        <g key={i} className={`rig-frame rig-p${i}`}>
          <Figure pose={p} />
        </g>
      ))}
    </svg>
  )
}

/**
 * One pose, as nested rotations.
 *
 * <p>Each group's transform is a plain SVG attribute rather than a CSS
 * transform: nesting `translate` then `rotate` in SVG's own coordinate system is
 * unambiguous, where CSS transforms on SVG bring `transform-box` and
 * `transform-origin` into it and resolve differently depending on both.
 */
function Figure({ pose: p }) {
  return (
    <g className="fill-slate-800 dark:fill-slate-100" transform={`translate(${p.hip[0]},${p.hip[1]})`}>
      <g transform={`rotate(${p.thigh})`}>
        <use href="#rig-thigh" />
        <g transform={`translate(0,${LIMB.thigh}) rotate(${p.knee})`}>
          <use href="#rig-shin" />
          <g transform={`translate(0,${LIMB.shin}) rotate(${p.ankle})`}>
            <use href="#rig-foot" />
          </g>
        </g>
      </g>
      <use href="#rig-pelvis" />
      <g transform={`rotate(${p.torso})`}>
        <use href="#rig-torso" />
        <g transform={`translate(0,-40) rotate(${p.shoulder})`}>
          <use href="#rig-uarm" />
          <g transform={`translate(0,${LIMB.uarm}) rotate(${p.elbow})`}>
            <use href="#rig-farm" />
            <circle cx="0" cy="26" r="4.4" />
          </g>
        </g>
        <g transform={`translate(0,-44) rotate(${p.head})`}>
          <use href="#rig-head" />
        </g>
      </g>
    </g>
  )
}

/**
 * What an exercise that hasn't been posed yet shows instead.
 *
 * <p>The movement pattern is real information — it is what the planner picked
 * the exercise for, and what the Replace sheet swaps within — so this says
 * something true rather than apologising for missing art. The cue underneath it
 * on the session screen is doing the actual instructing either way.
 */
const PATTERN_GLYPH = {
  squat: '🦵',
  hinge: '🍑',
  push: '🙌',
  pull: '🪢',
  core: '🎯',
  cardio: '🏃',
  mobility: '🧘',
}

function PatternGlyph({ pattern }) {
  return (
    <span aria-hidden="true" className="text-4xl opacity-60">
      {PATTERN_GLYPH[pattern] ?? '🏋️'}
    </span>
  )
}
