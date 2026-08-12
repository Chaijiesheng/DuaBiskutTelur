/**
 * Validation for the typed-barcode fallback.
 *
 * Extracted for the same reason `confidence.js` and `portionCorrection.js` are:
 * the rule is small, it decides whether a button is enabled, and it is the only
 * part of the manual-entry path with anything to get wrong.
 */

/** Digits only — people read barcodes aloud with spaces and hyphens in them. */
export function normalizeBarcode(input) {
  return String(input ?? '').replace(/\D/g, '')
}

/**
 * EAN-8 at the short end through ITF-14 at the long end, which covers every
 * format the scanner itself recognises (EAN-8/13, UPC-A/E, Code 128) plus the
 * case-level codes that turn up on multipacks.
 */
export function isValidBarcode(input) {
  const digits = normalizeBarcode(input)
  return digits.length >= 8 && digits.length <= 14
}
