import { afterEach, describe, expect, it, vi } from 'vitest'
import { CANCELLED, analyzeImage, rankMenuImage, lookupBarcode } from './api.js'

/**
 * Pressing Cancel and losing signal both reject fetch with an AbortError. If the
 * app cannot tell them apart it puts "Could not reach the analyzer" in front of
 * someone who had just told it to stop — an error screen for doing exactly what
 * the button offered.
 */
describe('cancelling an in-flight analysis', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function fetchRejectingWith(error) {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(error)))
  }

  const abortError = () => Object.assign(new Error('aborted'), { name: 'AbortError' })

  it.each([
    ['analyzeImage', () => analyzeImage(new Blob(), 'meal.jpg', 'en')],
    ['rankMenuImage', () => rankMenuImage(new Blob(), 'menu.jpg', 'en')],
    ['lookupBarcode', () => lookupBarcode('123', 1, 'en')],
  ])('%s reports an abort as CANCELLED, not as a network failure', async (_name, call) => {
    fetchRejectingWith(abortError())

    await expect(call()).rejects.toMatchObject({ code: CANCELLED })
  })

  it.each([
    ['analyzeImage', () => analyzeImage(new Blob(), 'meal.jpg', 'en')],
    ['rankMenuImage', () => rankMenuImage(new Blob(), 'menu.jpg', 'en')],
    ['lookupBarcode', () => lookupBarcode('123', 1, 'en')],
  ])('%s still reports a genuine failure as NETWORK', async (_name, call) => {
    fetchRejectingWith(new TypeError('Failed to fetch'))

    await expect(call()).rejects.toMatchObject({ code: 'NETWORK' })
  })

  it('passes the signal through, so abort() actually reaches the request', async () => {
    const fetchSpy = vi.fn(() => Promise.reject(abortError()))
    vi.stubGlobal('fetch', fetchSpy)
    const controller = new AbortController()

    await expect(analyzeImage(new Blob(), 'meal.jpg', 'en', controller.signal))
      .rejects.toMatchObject({ code: CANCELLED })
    expect(fetchSpy.mock.calls[0][1].signal).toBe(controller.signal)
  })
})
