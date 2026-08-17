const BASE = import.meta.env.VITE_API_BASE ?? ''

export class ApiError extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
  }
}

/**
 * Distinguishes "the user pressed cancel" from "the network failed".
 *
 * fetch rejects with an AbortError either way, and reporting a cancellation as
 * a network error would put an error screen in front of someone who had just
 * told the app to stop. Callers check for CANCELLED and go quiet.
 */
export const CANCELLED = 'CANCELLED'

function asNetworkOrAbort(e, message) {
  if (e?.name === 'AbortError') {
    return new ApiError(CANCELLED, 'Cancelled.')
  }
  return new ApiError('NETWORK', message)
}

async function toApiError(response) {
  if (response.status === 401) {
    return new ApiError('UNAUTHENTICATED', 'Please sign in to continue.')
  }
  let body = {}
  try {
    body = await response.json()
  } catch {
    /* non-JSON error body */
  }
  return new ApiError(body.error ?? `HTTP_${response.status}`, body.message ?? 'Request failed')
}

// Cookies (the login session) must ride along on every API call.
function apiFetch(path, opts = {}) {
  return fetch(`${BASE}${path}`, { credentials: 'include', ...opts })
}

export function googleLoginUrl() {
  return `${BASE}/oauth2/authorization/google`
}

/** Current user + profile, or null if not signed in. */
export async function fetchMe() {
  let response
  try {
    response = await apiFetch('/api/me')
  } catch {
    throw new ApiError('NETWORK', 'Could not reach the server.')
  }
  if (response.status === 401) {
    return null
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

export async function saveProfile(profile) {
  const response = await apiFetch('/api/profile', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profile),
  })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

export async function saveBudget(dailyBudget) {
  const response = await apiFetch('/api/budget', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dailyBudget }),
  })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

export async function logout() {
  await apiFetch('/api/logout', { method: 'POST' })
}

/** POST the (already compressed) image; returns the AnalysisResponse. */
export async function analyzeImage(blob, filename = 'meal.jpg', lang = 'en', signal) {
  const form = new FormData()
  form.append('image', blob, filename)
  form.append('lang', lang)
  let response
  try {
    response = await apiFetch('/api/analyze', { method: 'POST', body: form, signal })
  } catch (e) {
    throw asNetworkOrAbort(e, 'Could not reach the analyzer.')
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** POST a menu photo; returns a MenuRankingResponse (5 tier groups, not a single score). */
export async function rankMenuImage(blob, filename = 'menu.jpg', lang = 'en', signal) {
  const form = new FormData()
  form.append('image', blob, filename)
  form.append('lang', lang)
  let response
  try {
    response = await apiFetch('/api/menu/rank', { method: 'POST', body: form, signal })
  } catch (e) {
    throw asNetworkOrAbort(e, 'Could not reach the analyzer.')
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

export async function fetchMenuHistory() {
  const response = await apiFetch('/api/menu/history')
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Reopens a past menu scan; returns the full MenuRankingResponse. */
export async function fetchMenuHistoryDetail(id) {
  const response = await apiFetch(`/api/menu/history/${id}`)
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Permanently deletes a saved menu scan. */
export async function deleteMenuHistoryEntry(id) {
  const response = await apiFetch(`/api/menu/history/${id}`, { method: 'DELETE' })
  if (!response.ok) {
    throw await toApiError(response)
  }
}

/** Resolves just the product name/unit basis for a scanned barcode — no scoring, no history write. */
export async function lookupBarcodeProduct(code) {
  let response
  try {
    response = await apiFetch(`/api/barcode/${encodeURIComponent(code)}/product`)
  } catch {
    throw new ApiError('NETWORK', 'Could not reach the server.')
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/**
 * Looks up a scanned barcode and returns a graded AnalysisResponse, same shape
 * as analyzeImage. POST because it writes the meal into history — as a GET it
 * was triggerable by any cross-site navigation, since SameSite=Lax sends the
 * session cookie on those.
 */
export async function lookupBarcode(code, servings = 1, lang = 'en', signal) {
  let response
  try {
    response = await apiFetch('/api/barcode/lookup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, servings, lang }),
      signal,
    })
  } catch (e) {
    throw asNetworkOrAbort(e, 'Could not reach the server.')
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Today's calorie/protein/meal-count/grade summary; auth required. */
export async function fetchDashboardToday() {
  const response = await apiFetch('/api/dashboard/today')
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Total meals logged, current logging streak, and badge unlock states; auth required. */
export async function fetchAchievements(lang = 'en') {
  const response = await apiFetch(`/api/achievements?lang=${encodeURIComponent(lang)}`)
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

export async function fetchHistory() {
  const response = await apiFetch('/api/history')
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/**
 * Every meal in the trailing window, uncapped — the inputs to the weekly trend.
 * Separate from fetchHistory() because that one is capped at 50 rows, which
 * silently truncated the weekly totals for anyone logging often.
 */
export async function fetchRecentHistory(days = 7) {
  const response = await apiFetch(`/api/history/recent?days=${days}`)
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/**
 * Corrects the portion sizes of a saved meal and returns it re-graded.
 *
 * One multiplier per food, in the order they were returned. The server holds the
 * nutrition and does the rescaling — this sends no numbers of its own, so a
 * request cannot write a fabricated meal into a history that feeds streaks and
 * achievements. Multipliers are absolute rather than cumulative, which is what
 * makes this safe to call repeatedly as the user taps around.
 */
export async function correctPortions(id, multipliers, lang = 'en') {
  const response = await apiFetch(`/api/history/${id}/portions?lang=${encodeURIComponent(lang)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ multipliers }),
  })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/**
 * Removes one misidentified food from a saved meal and returns it re-graded.
 *
 * Positional, like `correctPortions` — two items on a plate can share a name, so
 * a name is not an identifier. Rejects with an `ApiError` carrying
 * `error === 'LAST_FOOD'` (409) when this is the only item left: an empty meal
 * is not a meal, and what the user is really saying at that point is that the
 * whole entry is wrong.
 */
export async function removeFood(id, index, lang = 'en') {
  const response = await apiFetch(
    `/api/history/${id}/foods/${index}?lang=${encodeURIComponent(lang)}`,
    { method: 'DELETE' },
  )
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Permanently deletes a saved meal entry. */
export async function deleteHistoryEntry(id) {
  const response = await apiFetch(`/api/history/${id}`, { method: 'DELETE' })
  if (!response.ok) {
    throw await toApiError(response)
  }
}

/** Reopens a past analysis; returns the full AnalysisResponse. */
export async function fetchHistoryDetail(id) {
  const response = await apiFetch(`/api/history/${id}`)
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Logs a weigh-in; returns the refreshed weekly-averaged history in one round trip. */
export async function logWeight(weightKg) {
  const response = await apiFetch('/api/weight', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ weightKg }),
  })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Weekly-averaged weigh-in history for the trailing ~8 weeks; auth required. */
export async function fetchWeightHistory() {
  const response = await apiFetch('/api/weight/history')
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Today's water total + target; auth required. */
export async function fetchWaterToday() {
  const response = await apiFetch('/api/water/today')
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Adjusts today's water total by deltaMl (positive to add, negative to correct); returns the refreshed total. */
export async function adjustWater(deltaMl) {
  const response = await apiFetch('/api/water/adjust', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deltaMl }),
  })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Resets today's water total to zero. */
export async function resetWater() {
  const response = await apiFetch('/api/water/reset', { method: 'POST' })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** Updates the user's daily water target. */
export async function setWaterTarget(targetMl) {
  const response = await apiFetch('/api/water/target', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ targetMl }),
  })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/**
 * Hands a fetched blob to the browser as a save-as. The revoke is deferred a
 * tick because some browsers cancel an in-flight download if the object URL is
 * released in the same task as the click.
 */
function saveAs(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/** Downloads the PDF report for a saved meal and triggers a save-as in the browser. */
export async function exportHistoryPdf(id) {
  const response = await apiFetch(`/api/history/${id}/pdf`)
  if (!response.ok) {
    throw await toApiError(response)
  }
  saveAs(await response.blob(), `duabiskuttelur-report-${id}.pdf`)
}

/** Downloads everything the account holds as a JSON file (profile, meals, menu scans, water, weight). */
export async function exportAccountData() {
  const response = await apiFetch('/api/account/export')
  if (!response.ok) {
    throw await toApiError(response)
  }
  saveAs(await response.blob(), 'duabiskuttelur-data-export.json')
}

/** Irreversibly deletes the account and everything belonging to it, on every device. */
export async function deleteAccount() {
  const response = await apiFetch('/api/account', { method: 'DELETE' })
  if (!response.ok) {
    throw await toApiError(response)
  }
}

// --------------------------------------------------------------- workouts

/**
 * Shared shape for the workout endpoints: JSON in, JSON out, and the same
 * error translation as everything else here.
 *
 * Worth factoring rather than repeating ten times: these ten calls are the
 * feature's whole API surface, and a hand-written `if (!response.ok)` that gets
 * forgotten on one of them turns a 409 into an unhandled undefined field three
 * components later.
 */
async function workoutJson(path, { method = 'GET', body } = {}) {
  const response = await apiFetch(path, {
    method,
    ...(body === undefined
      ? {}
      : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
}

/** The whole Workout tab dashboard, generating today's session if there isn't one. */
export function fetchWorkoutToday(lang = 'en') {
  return workoutJson(`/api/workout/today?lang=${encodeURIComponent(lang)}`)
}

/** Saves the six onboarding answers and returns the dashboard built from them. */
export function saveWorkoutProfile(profile, lang = 'en') {
  return workoutJson(`/api/workout/profile?lang=${encodeURIComponent(lang)}`, {
    method: 'POST',
    body: profile,
  })
}

export function startWorkoutSession(id) {
  return workoutJson(`/api/workout/sessions/${id}/start`, { method: 'POST' })
}

/**
 * Marks one set done or not done.
 *
 * PUT with the intended result rather than POST "one more", which is what lets
 * WorkoutContext replay a queue of sets logged offline without tracking which
 * ones already landed — the server's unique constraint absorbs the duplicates.
 */
export function logWorkoutSet(id, exercisePosition, setIndex, done) {
  return workoutJson(`/api/workout/sessions/${id}/sets`, {
    method: 'PUT',
    body: { exercisePosition, setIndex, done },
  })
}

export function fetchWorkoutAlternatives(id, position) {
  return workoutJson(`/api/workout/sessions/${id}/exercises/${position}/alternatives`)
}

export function replaceWorkoutExercise(id, position, exerciseKey) {
  return workoutJson(`/api/workout/sessions/${id}/exercises/${position}`, {
    method: 'PUT',
    body: { exerciseKey },
  })
}

export function skipWorkoutSession(id, skipped) {
  return workoutJson(`/api/workout/sessions/${id}/${skipped ? 'skip' : 'unskip'}`, { method: 'POST' })
}

export function completeWorkoutSession(id, { feel, energy, actualMinutes, lang = 'en' }) {
  return workoutJson(`/api/workout/sessions/${id}/complete`, {
    method: 'POST',
    body: { feel, energy, actualMinutes, lang },
  })
}

/**
 * Past sessions for the Workouts tab inside History.
 *
 * Deliberately not `fetchWorkoutToday`, even though that returns a week strip:
 * `/today` plans a session when there isn't one, so reusing it here would mean
 * opening History created this morning's workout on the way past.
 */
export function fetchWorkoutHistory() {
  return workoutJson('/api/workout/history')
}

/** Workout figures for the Analysis tab. Read-only, for the same reason. */
export function fetchWorkoutStats() {
  return workoutJson('/api/workout/stats')
}

/**
 * One line about today's training, for the Today card on the Snap tab.
 *
 * The lightest of the three workout reads, and the one that matters most that
 * it stays read-only: this runs on the app's home screen for everybody, and
 * `/api/workout/today` would plan a session — and make a Gemini call for the
 * coach note — on every user's first open of the day.
 */
export function fetchWorkoutGlance() {
  return workoutJson('/api/workout/glance')
}
