/**
 * The exercise demonstration registry.
 *
 * <p>One catalogue key maps to one demonstration file in
 * {@code public/exercises/}. The map below is the whole mapping: it is
 * explicit, so a key that is not listed has no demonstration and the UI says
 * so, rather than requesting a file that isn't there and rendering a broken
 * image.
 *
 * <p>Deliberately not derived from the key at runtime. Building the filename
 * from {@code `${key}.gif`} would look tidier and would be wrong: the app would
 * have no way to know whether the file exists, every unposed exercise would
 * fire a 404, and the failure would surface as a broken image rather than as a
 * designed state. Being able to answer "do we have a demonstration for this?"
 * without a network round trip is the point.
 *
 * <p>Nothing here is reachable by the model. The planner picks catalogue rows
 * and stores their keys; this file turns a key into a path. A generated plan
 * cannot name a file, so it cannot make the app fetch one.
 *
 * <h2>Why the dimensions are stored</h2>
 * The assets are not one shape. They arrived between 855x1140 portrait and
 * 980x653 landscape, so a single hard-coded ratio would letterbox most of them
 * and crop the intent out of the rest. Each entry carries the dimensions the
 * browser actually decodes, which lets the frame be reserved at the real ratio
 * before the file loads — no layout shift, and no guessing.
 *
 * <h2>Adding a demonstration</h2>
 * Drop the file into {@code frontend/public/exercises/} and add a line here
 * with its real pixel dimensions. {@code exerciseAssets.test.js} checks the
 * registry and the directory agree, and that nothing is oversized.
 */

/** Catalogue key to file and its decoded pixel size. Grouped by movement pattern. */
export const EXERCISE_ASSETS = {
  // squat
  bodyweight_squat: { file: 'bodyweight_squat.gif', width: 960, height: 540 },
  box_squat: { file: 'box_squat.gif', width: 960, height: 540 },
  reverse_lunge: { file: 'reverse_lunge.gif', width: 960, height: 540 },
  split_squat: { file: 'split_squat.gif', width: 960, height: 540 },
  // hinge
  glute_bridge: { file: 'glute_bridge.gif', width: 960, height: 540 },
  hip_hinge: { file: 'hip_hinge.gif', width: 600, height: 646 },
  single_leg_bridge: { file: 'single_leg_bridge.gif', width: 960, height: 540 },
  // push
  incline_push_up: { file: 'incline_push_up.gif', width: 960, height: 540 },
  knee_push_up: { file: 'knee_push_up.gif', width: 740, height: 493 },
  push_up: { file: 'push_up.gif', width: 960, height: 540 },
  // pull
  doorway_row: { file: 'doorway_row.gif', width: 625, height: 458 },
  prone_row: { file: 'prone_row.gif', width: 855, height: 1140 },
  superman: { file: 'superman.gif', width: 960, height: 540 },
  // core
  bird_dog: { file: 'bird_dog.gif', width: 960, height: 540 },
  dead_bug: { file: 'dead_bug.gif', width: 960, height: 540 },
  plank: { file: 'plank.gif', width: 960, height: 540 },
  side_plank: { file: 'side_plank.gif', width: 960, height: 540 },
  // cardio
  jumping_jack: { file: 'jumping_jack.gif', width: 960, height: 540 },
  march_in_place: { file: 'march_in_place.gif', width: 860, height: 860 },
  // step_touch.gif is an animated WebP that was given a .gif extension.
  // Browsers sniff the content and render it, which is why it is registered
  // rather than held back, but the extension is wrong at source.
  step_touch: { file: 'step_touch.gif', width: 400, height: 400 },
  // mobility
  ankle_rock: { file: 'ankle_rock.gif', width: 980, height: 653 },
  cat_cow: { file: 'cat_cow.gif', width: 960, height: 540 },
  hip_flexor_stretch: { file: 'hip_flexor_stretch.gif', width: 960, height: 540 },
  thoracic_rotation: { file: 'thoracic_rotation.gif', width: 960, height: 540 },
  worlds_greatest_stretch: { file: 'worlds_greatest_stretch.gif', width: 800, height: 800 },
}

/**
 * The frame a demonstration is reserved at before it loads, and the shape the
 * unavailable state uses so the layout is identical either way.
 */
export const FALLBACK_ASPECT = { width: 16, height: 9 }

/**
 * No demonstration may be taller than this once laid out. A portrait asset in a
 * phone-width column would otherwise push the reps and the Complete-set button
 * off screen; capping the height letterboxes it instead, which costs a little
 * size and keeps the controls where the user expects them.
 */
export const MAX_DEMO_HEIGHT = '15rem'

/** The registry entry for an exercise, or null when it has no demonstration. */
export function demoAsset(exerciseKey) {
  return EXERCISE_ASSETS[exerciseKey] ?? null
}

/**
 * The URL for an exercise's demonstration, or null when there isn't one.
 *
 * <p>Null is a normal answer, not an error: the catalogue has 59 exercises and
 * the assets arrive in batches.
 */
export function demoUrl(exerciseKey) {
  const asset = demoAsset(exerciseKey)
  return asset ? `${import.meta.env.BASE_URL}exercises/${asset.file}` : null
}

/** True when this exercise has a demonstration. */
export function hasDemo(exerciseKey) {
  return Object.prototype.hasOwnProperty.call(EXERCISE_ASSETS, exerciseKey)
}

/**
 * Where a missing file belongs. Shown only in development, as the fix
 * instruction for whoever is preparing assets — never to a real user.
 */
export function expectedAssetPath(exerciseKey) {
  return `public/exercises/${exerciseKey}.gif`
}

/** How many exercises have a demonstration — reported by tests so coverage cannot silently drop. */
export function demoCount() {
  return Object.keys(EXERCISE_ASSETS).length
}
