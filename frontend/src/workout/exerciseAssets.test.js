import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

import { EXERCISE_ASSETS, demoCount, demoUrl, expectedAssetPath, hasDemo } from './exerciseAssets.js'

// Resolved from the vitest root (frontend/) rather than import.meta.url, which
// the jsdom environment rewrites to an http URL that fileURLToPath rejects.
const ASSET_DIR = resolve(process.cwd(), 'public/exercises')
const CATALOGUE = resolve(process.cwd(), '../backend/src/main/resources/workout/exercises.csv')

/**
 * The demonstrations are not in the repository -- 41 MB of GIF against a 5 MB
 * source tree, and git keeps whatever it is given forever. They are delivered
 * to the deployment separately, so a clone legitimately has none of them and
 * the three checks below have nothing to check rather than something wrong.
 * Every other check here is about the registry itself and runs regardless.
 */
const ASSETS_PRESENT = existsSync(ASSET_DIR)
const filesOnDisk = ASSETS_PRESENT ? readdirSync(ASSET_DIR).filter((f) => f.endsWith('.gif')) : []

const catalogueKeys = new Set(
  readFileSync(CATALOGUE, 'utf8')
    .split('\n')
    .filter((line) => line.trim() && !line.startsWith('#'))
    .map((line) => line.split('|')[0].trim()),
)

/**
 * The registry is hand-maintained, and the two halves of it live in different
 * places: a file in public/ and a line in exerciseAssets.js. Nothing at build
 * time connects them, so the only thing stopping them drifting apart is this.
 *
 * <p>Both directions matter. A registered key with no file gives the user a
 * broken image where a demonstration should be; a file with no registered key
 * is a download that silently never reaches anyone.
 */
describe('the exercise demonstration registry', () => {
  it.skipIf(!ASSETS_PRESENT)('has a file on disk for every registered exercise', () => {
    const missing = Object.entries(EXERCISE_ASSETS)
      .filter(([, asset]) => !filesOnDisk.includes(asset.file))
      .map(([key, asset]) => `${key} -> ${asset.file}`)

    expect(missing, 'registered but not present in public/exercises/').toEqual([])
  })

  it.skipIf(!ASSETS_PRESENT)('has a registered exercise for every file on disk', () => {
    const registered = new Set(Object.values(EXERCISE_ASSETS).map((a) => a.file))
    const orphans = filesOnDisk.filter((f) => !registered.has(f))

    expect(orphans, 'present in public/exercises/ but not registered, so never shown').toEqual([])
  })

  /**
   * The key is what the backend stores on the session row. A registry entry for
   * a key the catalogue does not have could never match a real exercise, so it
   * would be dead weight that still looks like coverage.
   */
  it('only registers keys that exist in the backend catalogue', () => {
    const unknown = Object.keys(EXERCISE_ASSETS).filter((key) => !catalogueKeys.has(key))

    expect(unknown, 'not an exercise key in exercises.csv').toEqual([])
  })

  it('names every file after its key, in lowercase snake_case', () => {
    for (const [key, asset] of Object.entries(EXERCISE_ASSETS)) {
      expect(asset.file, `${key} does not follow the naming convention`).toBe(`${key}.gif`)
      expect(key).toMatch(/^[a-z][a-z0-9_]*$/)
    }
  })

  it('builds a url under the app base, and null when there is no asset', () => {
    expect(demoUrl('plank')).toBe('/exercises/plank.gif')
    expect(demoUrl('barbell_deadlift')).toBeNull()
    expect(demoUrl('')).toBeNull()
  })

  /** A key that isn't registered must miss, not resolve to something near it. */
  it('never guesses a filename for an unregistered key', () => {
    expect(hasDemo('push_up')).toBe(hasDemo('push_up')) // whichever it is, it is explicit
    expect(hasDemo('not_a_real_exercise')).toBe(false)
    expect(demoUrl('not_a_real_exercise')).toBeNull()
  })

  /**
   * The dimensions are what the frame is reserved from, so a wrong number is a
   * layout shift at the exact moment the user is reaching for Complete set.
   * They must be positive and plausible rather than copied from a neighbour.
   */
  it('records real pixel dimensions for every asset', () => {
    for (const [key, asset] of Object.entries(EXERCISE_ASSETS)) {
      expect(asset.width, `${key} has no width`).toBeGreaterThan(0)
      expect(asset.height, `${key} has no height`).toBeGreaterThan(0)
      const ratio = asset.width / asset.height
      expect(ratio, `${key} has an implausible aspect ratio`).toBeGreaterThan(0.4)
      expect(ratio, `${key} has an implausible aspect ratio`).toBeLessThan(3)
    }
  })

  /**
   * These ship from public/ and are fetched over mobile data on a phone in a
   * gym. The budget is per file, deliberately generous, and exists so a
   * multi-megabyte export cannot land unnoticed: at 15 MB a single
   * demonstration costs more than the entire rest of the app.
   */
  it.skipIf(!ASSETS_PRESENT)('keeps every demonstration inside the download budget', () => {
    const BUDGET_MB = 2
    const heavy = Object.entries(EXERCISE_ASSETS)
      .map(([key, asset]) => [key, statSync(join(ASSET_DIR, asset.file)).size / 1024 / 1024])
      .filter(([, mb]) => mb > BUDGET_MB)
      .map(([key, mb]) => `${key} is ${mb.toFixed(1)} MB`)

    expect(heavy, `over the ${BUDGET_MB} MB per-file budget; re-export smaller`).toEqual([])
  })

  it('reports where a missing file belongs', () => {
    expect(expectedAssetPath('knee_push_up')).toBe('public/exercises/knee_push_up.gif')
  })

  /**
   * Coverage grows in batches and must not silently go backwards, which is what
   * deleting a line by accident would look like.
   */
  it('keeps the demonstrations already delivered', () => {
    for (const key of ['bodyweight_squat', 'plank', 'glute_bridge', 'dead_bug']) {
      expect(hasDemo(key), `${key} lost its demonstration`).toBe(true)
    }
    expect(demoCount()).toBeGreaterThanOrEqual(25)
  })
})
