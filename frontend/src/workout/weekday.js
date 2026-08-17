const LOCALE_TAG = { en: 'en-US', zh: 'zh-CN', ms: 'ms-MY' }

/**
 * The full weekday name for an ISO date, in the user's language.
 *
 * <p>Exists because the week strip and the minutes chart both label their
 * columns with a single narrow letter — which is right visually, where position
 * disambiguates, and wrong for a screen reader, where it does not. "T, 0 min"
 * appears twice in a week and "S, 0 min" appears twice more, so a listener gets
 * four columns they cannot tell apart.
 *
 * <p>Derived from the date on the client rather than sent by the server, so it
 * follows the language the user is actually reading in — the server's own
 * narrow labels are English by construction.
 */
export function fullWeekdayName(isoDate, lang) {
  // Noon rather than midnight: parsing a bare ISO date as UTC and rendering it
  // in a negative-offset zone lands on the previous day, which would name every
  // column wrong for anyone west of Greenwich.
  const date = new Date(`${isoDate}T12:00:00`)
  if (Number.isNaN(date.getTime())) {
    return isoDate
  }
  return date.toLocaleDateString(LOCALE_TAG[lang] ?? 'en-US', { weekday: 'long' })
}
