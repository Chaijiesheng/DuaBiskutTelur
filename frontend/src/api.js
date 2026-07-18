const BASE = import.meta.env.VITE_API_BASE ?? ''

export class ApiError extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
  }
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
export async function analyzeImage(blob, filename = 'meal.jpg', lang = 'en') {
  const form = new FormData()
  form.append('image', blob, filename)
  form.append('lang', lang)
  let response
  try {
    response = await apiFetch('/api/analyze', { method: 'POST', body: form })
  } catch {
    throw new ApiError('NETWORK', 'Could not reach the analyzer.')
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response.json()
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

/** Looks up a scanned barcode and returns a graded AnalysisResponse, same shape as analyzeImage. */
export async function lookupBarcode(code, servings = 1, lang = 'en') {
  const params = new URLSearchParams({ servings: String(servings), lang })
  let response
  try {
    response = await apiFetch(`/api/barcode/${encodeURIComponent(code)}?${params}`)
  } catch {
    throw new ApiError('NETWORK', 'Could not reach the server.')
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

/** Downloads the PDF report for a saved meal and triggers a save-as in the browser. */
export async function exportHistoryPdf(id) {
  const response = await apiFetch(`/api/history/${id}/pdf`)
  if (!response.ok) {
    throw await toApiError(response)
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `duabiskuttelur-report-${id}.pdf`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
