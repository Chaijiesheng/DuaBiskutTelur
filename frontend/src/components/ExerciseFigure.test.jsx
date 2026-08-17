import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

const { default: ExerciseFigure } = await import('./ExerciseFigure.jsx')
const { POSES, hasFigure, posedCount } = await import('../workout/exerciseRig.js')

/**
 * The figure is drawn from a pose table rather than shipped as art, so the
 * things worth testing are the properties that make that affordable and safe:
 * the rig assembles, the poses are internally consistent, and an exercise
 * nobody has posed yet degrades to something honest instead of an empty box.
 */
describe('the exercise figure', () => {
  it('draws every pose of a posed exercise', () => {
    const { container } = render(<ExerciseFigure exerciseKey="bodyweight_squat" pattern="squat" />)

    const svg = container.querySelector('svg')
    expect(svg).toBeTruthy()
    expect(svg.querySelectorAll('g.rig-frame')).toHaveLength(POSES.bodyweight_squat.poses.length)
    // Eight limb shapes per pose, defined once and referenced.
    expect(svg.querySelectorAll('defs > *').length).toBeGreaterThan(0)
  })

  it('loops by default and holds still in the sheet', () => {
    const { container: loop } = render(<ExerciseFigure exerciseKey="bodyweight_squat" pattern="squat" />)
    const { container: all } = render(
      <ExerciseFigure exerciseKey="bodyweight_squat" pattern="squat" variant="all" />,
    )

    expect(loop.querySelector('svg').classList.contains('rig-loop')).toBe(true)
    expect(all.querySelector('svg').classList.contains('rig-all')).toBe(true)
  })

  /**
   * A hold has no rep to animate. Looping one pose would just be a still image
   * pretending to be a demonstration.
   */
  it('never loops a hold', () => {
    const { container } = render(<ExerciseFigure exerciseKey="plank" pattern="core" />)

    expect(POSES.plank.poses).toHaveLength(1)
    expect(container.querySelector('svg').classList.contains('rig-all')).toBe(true)
    expect(container.querySelector('svg').classList.contains('rig-loop')).toBe(false)
  })

  /**
   * The coverage promise. 59 exercises ship before 59 are posed, so an unposed
   * one must show something true — the movement pattern the planner picked it
   * for — rather than an empty frame or a spinner that never resolves.
   */
  it('falls back to the movement pattern for an exercise nobody has posed', () => {
    const { container } = render(<ExerciseFigure exerciseKey="barbell_deadlift" pattern="hinge" />)

    expect(hasFigure('barbell_deadlift')).toBe(false)
    expect(container.querySelector('svg')).toBeNull()
    expect(container.textContent.trim()).not.toBe('')
  })

  it('still renders something for an unknown pattern', () => {
    const { container } = render(<ExerciseFigure exerciseKey="nope" pattern={undefined} />)
    expect(container.textContent.trim()).not.toBe('')
  })

  /** Decorative: the name, dose and cue beside it are the real content. */
  it('is hidden from screen readers rather than duplicating the text beside it', () => {
    const { container } = render(<ExerciseFigure exerciseKey="bodyweight_squat" pattern="squat" />)
    expect(container.querySelector('svg').getAttribute('aria-hidden')).toBe('true')
  })
})

describe('the pose tables', () => {
  it('gives every posed exercise either a full rep or a single hold', () => {
    for (const [key, rig] of Object.entries(POSES)) {
      const n = rig.poses.length
      expect(n === 1 || n === 5, `${key} has ${n} poses; expected 5 for a rep or 1 for a hold`).toBe(true)
    }
  })

  /**
   * Every pose is eight named angles. A missing one would silently render as
   * `rotate(undefined)`, which browsers ignore — the limb would just sit at
   * zero and look subtly wrong rather than fail.
   */
  it('gives every pose a complete set of joints', () => {
    const joints = ['torso', 'head', 'shoulder', 'elbow', 'thigh', 'knee', 'ankle']
    for (const [key, rig] of Object.entries(POSES)) {
      rig.poses.forEach((p, i) => {
        expect(Array.isArray(p.hip) && p.hip.length === 2, `${key} pose ${i} has no hip position`).toBe(true)
        for (const joint of joints) {
          expect(typeof p[joint], `${key} pose ${i} is missing ${joint}`).toBe('number')
        }
      })
    }
  })

  /** Every pose has to fit the box it is drawn in, or limbs clip at the edge. */
  it('keeps every hip inside its own viewBox', () => {
    for (const [key, rig] of Object.entries(POSES)) {
      const [, , width, height] = rig.view
      for (const p of rig.poses) {
        expect(p.hip[0] > 0 && p.hip[0] < width, `${key} hip x ${p.hip[0]} is outside 0..${width}`).toBe(true)
        expect(p.hip[1] > 0 && p.hip[1] < height, `${key} hip y ${p.hip[1]} is outside 0..${height}`).toBe(true)
      }
    }
  })

  /**
   * Coverage is partial on purpose and grows over time — but it must never
   * silently go backwards, which is what deleting a pose table by accident
   * would look like.
   */
  it('has poses for the movements a kit-less beginner sees most', () => {
    for (const key of ['bodyweight_squat', 'box_squat', 'push_up', 'knee_push_up', 'plank']) {
      expect(hasFigure(key), `${key} lost its pose table`).toBe(true)
    }
    expect(posedCount()).toBeGreaterThanOrEqual(9)
  })
})
