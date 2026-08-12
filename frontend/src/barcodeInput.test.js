import { describe, expect, it } from 'vitest'
import { isValidBarcode, normalizeBarcode } from './barcodeInput.js'

/**
 * The scanner is unusable without sight — aiming a package inside a viewfinder
 * you cannot see is not a task with an accessible version — so typing the
 * digits is the accessible path, not a convenience. It is also what covers a
 * scratched label or a broken camera.
 */
describe('normalizeBarcode', () => {
  it('keeps only digits, since people type them with separators', () => {
    expect(normalizeBarcode('9 555 191 100 04')).toBe('955519110004')
    expect(normalizeBarcode('9555-1911-0004')).toBe('955519110004')
  })

  it('survives the empty and absent cases without throwing', () => {
    expect(normalizeBarcode('')).toBe('')
    expect(normalizeBarcode(null)).toBe('')
    expect(normalizeBarcode(undefined)).toBe('')
  })
})

describe('isValidBarcode', () => {
  it('accepts the formats the scanner itself reads', () => {
    expect(isValidBarcode('12345678')).toBe(true)        // EAN-8
    expect(isValidBarcode('012345678905')).toBe(true)    // UPC-A
    expect(isValidBarcode('9555191100048')).toBe(true)   // EAN-13
    expect(isValidBarcode('19555191100045')).toBe(true)  // ITF-14 multipack
  })

  it('rejects a half-typed code, so the button stays disabled mid-entry', () => {
    expect(isValidBarcode('955')).toBe(false)
    expect(isValidBarcode('')).toBe(false)
  })

  it('rejects anything longer than a real barcode', () => {
    expect(isValidBarcode('123456789012345')).toBe(false)
  })

  it('judges the digits, not the punctuation around them', () => {
    expect(isValidBarcode('9 555 191 100 048')).toBe(true)
    expect(isValidBarcode('abcdefgh')).toBe(false)
  })
})
