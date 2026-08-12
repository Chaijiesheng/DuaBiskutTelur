import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const INSIGHTS = ['tip one', 'tip two', 'tip three', 'tip four']

vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key) => {
      if (key === 'analyzing.insights') return INSIGHTS
      if (key === 'analyzing.stages.meal') return ['Reading your photo', 'Looking up nutrition', 'Writing your feedback']
      if (key === 'analyzing.stages.barcode') return ['Looking up the product', 'Writing your feedback']
      if (key === 'analyzing.cancel') return 'Cancel'
      if (key === 'analyzing.title') return 'Analyzing your meal'
      if (key === 'analyzing.takes') return 'This usually takes 10–20 seconds.'
      if (key === 'analyzing.takesBarcode') return 'This usually takes a few seconds.'
      if (key === 'analyzing.late') return 'Taking longer than usual — still working.'
      if (key === 'analyzing.tipLabel') return 'Tip for you'
      if (key === 'analyzing.anotherTip') return 'Show another tip'
      return key
    },
    lang: 'en',
  }),
}))

const { default: AnalyzingScreen } = await import('./AnalyzingScreen.jsx')

const tipControls = () => screen.getAllByRole('button', { name: 'Show another tip' })
const currentTip = () => INSIGHTS.find((text) => screen.queryByText(text))

afterEach(() => {
  vi.useRealTimers()
})

/**
 * U3: an 8–20s wait that showed no progress and offered no way out.
 *
 * The timeline arithmetic is pinned in analyzingPhases.test.js; this covers
 * what the screen does with it, plus the cancel affordance — which is the part
 * that actually costs the user something when it is missing, since without it a
 * slow analysis holds a server thread and their data plan for a result they
 * have stopped waiting for.
 */
describe('AnalyzingScreen', () => {
  it('names the real pipeline stages rather than a bare spinner', () => {
    render(<AnalyzingScreen flow="meal" />)

    expect(screen.getByText('Reading your photo')).toBeInTheDocument()
    expect(screen.getByText('Looking up nutrition')).toBeInTheDocument()
    expect(screen.getByText('Writing your feedback')).toBeInTheDocument()
  })

  it('shows the stages for the flow actually running', () => {
    // A barcode scan does no vision work; saying it is reading a photo would be
    // describing work that is not happening.
    render(<AnalyzingScreen flow="barcode" />)

    expect(screen.getByText('Looking up the product')).toBeInTheDocument()
    expect(screen.queryByText('Reading your photo')).not.toBeInTheDocument()
  })

  it('announces the stage list, so the progression is available without sight', () => {
    render(<AnalyzingScreen flow="meal" />)

    const list = screen.getByRole('status')
    expect(list).toHaveAttribute('aria-live', 'polite')
  })

  it('keeps the tip out of the live region, so trivia never interrupts progress', () => {
    render(<AnalyzingScreen flow="meal" />)

    // The tip rotates on tap and would otherwise be read aloud over the stage
    // it is competing with. Only the pipeline is announced.
    expect(within(screen.getByRole('status')).queryByText(/tip /)).not.toBeInTheDocument()
  })

  it('offers a way out, and only when the caller can honour it', async () => {
    const onCancel = vi.fn()
    const { rerender } = render(<AnalyzingScreen flow="meal" onCancel={onCancel} />)

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalledOnce()

    // No handler, no button — a cancel that does nothing is worse than none.
    rerender(<AnalyzingScreen flow="meal" />)
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('starts with nothing marked complete', () => {
    const { container } = render(<AnalyzingScreen flow="meal" />)

    expect(container.textContent).not.toContain('✓')
  })

  it('sets an expectation for how long the wait should be', () => {
    render(<AnalyzingScreen flow="meal" />)

    // Without this line a 20s response is indistinguishable from a hang.
    expect(screen.getByText('This usually takes 10–20 seconds.')).toBeInTheDocument()
    expect(screen.queryByText('Taking longer than usual — still working.')).not.toBeInTheDocument()
  })

  it('admits it is running long once that expectation expires', () => {
    vi.useFakeTimers()
    render(<AnalyzingScreen flow="meal" />)

    act(() => {
      vi.advanceTimersByTime(21_000)
    })

    // Still no claim of being nearly done — the story only ever changes in the
    // honest direction.
    expect(screen.getByText('Taking longer than usual — still working.')).toBeInTheDocument()
    expect(screen.queryByText('This usually takes 10–20 seconds.')).not.toBeInTheDocument()
  })
})

/**
 * Tapping the chopsticks swaps the tip. That gesture existed before the
 * redesign and nothing on screen admitted it, which made it a feature only its
 * author knew about — so the tip card is wired to the same handler and both
 * carry the same accessible name.
 */
describe('changing the tip', () => {
  it('offers the gesture from the chopsticks and from the tip itself', () => {
    render(<AnalyzingScreen flow="meal" />)

    // Two controls, one action — a hidden gesture is only a feature if
    // something visible offers it too.
    expect(tipControls()).toHaveLength(2)
  })

  it('shows a different tip when the chopsticks are tapped', async () => {
    const user = userEvent.setup()
    render(<AnalyzingScreen flow="meal" />)
    const before = currentTip()

    await user.click(tipControls()[0])

    await waitFor(() => expect(currentTip()).not.toBe(before))
  })

  it('shows a different tip when the card is tapped', async () => {
    const user = userEvent.setup()
    render(<AnalyzingScreen flow="meal" />)
    const before = currentTip()

    await user.click(tipControls()[1])

    await waitFor(() => expect(currentTip()).not.toBe(before))
  })

  it('never repeats the tip already on screen', async () => {
    const user = userEvent.setup()
    render(<AnalyzingScreen flow="meal" />)

    // Tapping and getting the same text back reads as a broken button, so the
    // shuffle excludes the current index rather than picking freely.
    for (let i = 0; i < 12; i++) {
      const before = currentTip()
      await user.click(tipControls()[0])
      await waitFor(() => expect(currentTip()).not.toBe(before))
    }
  })

  it('leaves the pipeline alone', async () => {
    const user = userEvent.setup()
    render(<AnalyzingScreen flow="meal" />)

    await user.click(tipControls()[0])

    // Shuffling a tip is not progress. The stepper must not move.
    await waitFor(() => expect(currentTip()).toBeTruthy())
    expect(screen.getByRole('status').textContent).not.toContain('✓')
  })
})
