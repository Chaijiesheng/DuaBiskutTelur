/**
 * The articulated figure used by the in-session exercise demonstration.
 *
 * A body is drawn once, as limb shapes with fixed joint-to-joint lengths. Every
 * pose of every exercise is then eight joint angles against that body — which is
 * the whole reason this approach is affordable across a 59-row catalogue. Posing
 * a new exercise means writing a short table, not drawing a person five times.
 *
 * Why drawn rather than filmed: the service worker precaches
 * `**\/*.{js,css,html,png,svg,ico}` on install, and this codebase already went
 * to the trouble of excluding a 415 KB chunk from that. Sixty animated GIFs
 * would be that problem twenty times over, would not adapt to dark mode, and
 * would need licensing. This is a few kilobytes of numbers.
 *
 * Coverage is deliberately partial. POSES below holds the exercises that have
 * been posed so far; everything else falls back to a pattern glyph, so a
 * catalogue row without art shows something honest rather than an empty box.
 */

/** Joint-to-joint lengths. Every angle below is measured against these. */
export const LIMB = { torso: 44, uarm: 30, farm: 27, thigh: 34, shin: 30 }

/**
 * One pose.
 *
 * Angles are degrees, clockwise, each relative to its parent joint. Legs are
 * solved rather than eyeballed — the hip and the planted foot are given, and the
 * knee is derived between them, which is what keeps the heel down and the shin
 * length honest in every pose.
 */
function pose(hip, torso, head, shoulder, elbow, thigh, knee, ankle) {
  return { hip, torso, head, shoulder, elbow, thigh, knee, ankle }
}

/**
 * Pose tables, keyed by the catalogue's own exercise key.
 *
 * Five poses read as a rep: the loop plays them down and back up. A hold — a
 * plank, a stretch — gets exactly one, because animating it would invent
 * movement the exercise does not contain.
 */
export const POSES = {
  // ---- squat pattern
  bodyweight_squat: {
    view: [0, 0, 150, 176],
    ground: [18, 154, 132, 154],
    poses: [
      pose([62, 86], 0, 0, 0, 6, 0, 0, 0),
      pose([58, 91.5], 13, -7, -28, 8, -26, 47, -21),
      pose([54, 97], 25, -14, -57, 12, -39.5, 66.5, -27),
      pose([50, 102.5], 36, -20, -84, 16, -51.3, 80.3, -29),
      pose([46, 108], 45, -25, -105, 20, -62.7, 91, -28),
    ],
  },
  // Same movement, stopped a little higher and more upright — a box is behind
  // you, so the hips travel back further and the depth is fixed.
  box_squat: {
    view: [0, 0, 150, 176],
    ground: [18, 154, 132, 154],
    poses: [
      pose([62, 86], 0, 0, 0, 6, 0, 0, 0),
      pose([57, 91], 12, -6, -26, 8, -25, 45, -20),
      pose([52, 96], 24, -13, -54, 12, -38, 64, -26),
      pose([48, 101], 34, -19, -80, 16, -49, 78, -29),
      pose([44, 106], 42, -23, -100, 19, -60, 88, -28),
    ],
  },

  // ---- hinge pattern
  hip_hinge: {
    view: [0, 0, 150, 176],
    ground: [18, 154, 132, 154],
    poses: [
      pose([62, 86], 0, 0, 0, 6, 0, 0, 0),
      pose([58, 87], 18, -10, -14, 6, -8, 16, -8),
      pose([54, 88], 36, -20, -30, 6, -14, 28, -14),
      pose([50, 89], 54, -30, -46, 6, -19, 37, -18),
      pose([47, 90], 70, -38, -62, 6, -22, 43, -21),
    ],
  },

  // ---- push pattern. Hands planted, body one straight line pivoting at the toes.
  push_up: {
    view: [0, 0, 190, 176],
    ground: [18, 154, 178, 154],
    poses: [
      pose([84, 112], -68, 20, 82, -31, -68, 0, 138),
      pose([83, 115], -71, 20, 91, -51, -71, 0, 141),
      pose([82, 118], -74, 20, 101, -71, -74, 0, 144),
      pose([81, 122], -77, 20, 110, -91, -77, 0, 147),
      pose([80, 125], -81, 20, 120, -111, -81, 0, 151),
    ],
  },
  // Knees down: the pivot moves from the toes to the knees, so the shin folds
  // back and the body line is shorter.
  knee_push_up: {
    view: [0, 0, 190, 176],
    ground: [18, 154, 178, 154],
    poses: [
      pose([92, 116], -66, 20, 80, -30, -66, 96, 60),
      pose([91, 119], -69, 20, 89, -50, -69, 96, 60),
      pose([90, 122], -72, 20, 99, -70, -72, 96, 60),
      pose([89, 125], -75, 20, 108, -90, -75, 96, 60),
      pose([88, 128], -78, 20, 118, -110, -78, 96, 60),
    ],
  },

  // ---- pull pattern
  superman: {
    view: [0, 0, 190, 176],
    ground: [18, 154, 178, 154],
    poses: [
      pose([96, 140], -90, 8, 74, -6, -90, 0, 160),
      pose([96, 138], -93, 4, 70, -5, -87, 0, 157),
      pose([96, 136], -96, 0, 66, -4, -84, 0, 154),
      pose([96, 135], -98, -3, 63, -3, -82, 0, 152),
      pose([96, 134], -100, -6, 60, -2, -80, 0, 150),
    ],
  },

  // ---- core pattern. A hold: one pose, no loop.
  plank: {
    view: [0, 0, 190, 176],
    ground: [18, 154, 178, 154],
    poses: [pose([84, 120], -76, 16, 96, -78, -76, 0, 146)],
  },
  dead_bug: {
    view: [0, 0, 190, 176],
    ground: [18, 154, 178, 154],
    poses: [
      pose([92, 138], -90, 6, 150, -10, -40, 70, 118),
      pose([92, 138], -90, 6, 132, -8, -55, 78, 125),
      pose([92, 138], -90, 6, 112, -6, -70, 86, 132),
      pose([92, 138], -90, 6, 96, -4, -82, 92, 138),
      pose([92, 138], -90, 6, 82, -2, -92, 96, 144),
    ],
  },

  // ---- cardio pattern
  march_in_place: {
    view: [0, 0, 150, 176],
    ground: [18, 154, 132, 154],
    poses: [
      pose([62, 86], 0, 0, 10, 20, 0, 0, 0),
      pose([62, 85], 0, 0, -20, 40, -30, 70, -40),
      pose([62, 84], 0, 0, -40, 55, -55, 95, -40),
      pose([62, 85], 0, 0, -20, 40, -30, 70, -40),
      pose([62, 86], 0, 0, 10, 20, 0, 0, 0),
    ],
  },
}

/** The shapes the figure is built from, defined once and reused by every pose. */
export const BODY_PARTS = [
  { tag: 'ellipse', id: 'rig-pelvis', attrs: { cx: 0, cy: 1, rx: 9.5, ry: 7.5 } },
  { tag: 'path', id: 'rig-torso', attrs: { d: 'M -7.6,3 L -9.2,-30 Q -9.8,-43 0,-44.5 Q 9.6,-43 9,-30 L 7.6,3 Z' } },
  { tag: 'ellipse', id: 'rig-head', attrs: { cx: 1.5, cy: -9.5, rx: 8.4, ry: 9.6 } },
  { tag: 'rect', id: 'rig-uarm', attrs: { x: -4.2, y: -4, width: 8.4, height: 34, rx: 4.2 } },
  { tag: 'rect', id: 'rig-farm', attrs: { x: -3.5, y: -3, width: 7, height: 28, rx: 3.5 } },
  { tag: 'rect', id: 'rig-thigh', attrs: { x: -6.3, y: -5, width: 12.6, height: 41, rx: 6.3 } },
  { tag: 'rect', id: 'rig-shin', attrs: { x: -4.8, y: -4, width: 9.6, height: 36, rx: 4.8 } },
  { tag: 'path', id: 'rig-foot', attrs: { d: 'M -5.5,-4.5 L 14,-4.5 Q 18.5,-4.5 18.5,-1 L 18.5,1.5 Q 18.5,2.5 16.5,2.5 L -4.5,2.5 Q -6.5,2.5 -6.5,-1 Z' } },
]

/** True when this exercise has been posed. Everything else gets the fallback glyph. */
export function hasFigure(exerciseKey) {
  return Object.prototype.hasOwnProperty.call(POSES, exerciseKey)
}

/** How many exercises are posed — reported by tests so coverage cannot silently drop. */
export function posedCount() {
  return Object.keys(POSES).length
}
