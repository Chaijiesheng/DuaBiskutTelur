import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import {
  AnalysisSkeleton,
  BadgeGridSkeleton,
  DetailSkeleton,
  HistoryListSkeleton,
  MenuListSkeleton,
  SkeletonBlock,
} from './Skeleton.jsx'

/**
 * U1 replaced a centred "Loading…" line on every async surface with a
 * placeholder shaped like the content. The visual part of that is a matter of
 * taste and needs no test; two properties of it are not.
 *
 * The first is the one a purely visual change quietly breaks: the text being
 * removed was the *only* thing telling a screen reader that anything was
 * happening. Grey rectangles announce nothing, so replacing text with
 * decoration would have made the wait worse for exactly the people least able
 * to see that it was a wait.
 *
 * The second is reduced motion. A pulsing placeholder on every screen is a lot
 * of movement to inflict on someone who asked their OS for less of it.
 */

const EVERY_SKELETON = [
  ['HistoryListSkeleton', HistoryListSkeleton],
  ['MenuListSkeleton', MenuListSkeleton],
  ['AnalysisSkeleton', AnalysisSkeleton],
  ['BadgeGridSkeleton', BadgeGridSkeleton],
  ['DetailSkeleton', DetailSkeleton],
]

describe('every skeleton', () => {
  it.each(EVERY_SKELETON)('%s announces that content is loading', (_name, Skeleton) => {
    render(<Skeleton label="Loading history…" />)

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    // getByRole finds it only because sr-only hides it visually, not from the
    // accessibility tree — which is the whole point.
    expect(within(status).getByText('Loading history…')).toBeInTheDocument()
  })

  it.each(EVERY_SKELETON)('%s hides its placeholder blocks from assistive tech', (_name, Skeleton) => {
    const { container } = render(<Skeleton label="Loading…" />)

    const blocks = container.querySelectorAll('div.bg-slate-200')
    expect(blocks.length).toBeGreaterThan(0)
    blocks.forEach((block) => {
      expect(block).toHaveAttribute('aria-hidden', 'true')
    })
  })

  it.each(EVERY_SKELETON)('%s gates its pulse on motion-safe', (_name, Skeleton) => {
    const { container } = render(<Skeleton label="Loading…" />)

    container.querySelectorAll('div.bg-slate-200').forEach((block) => {
      expect(block.className).toContain('motion-safe:animate-pulse')
      // An ungated `animate-pulse` would pulse regardless of the OS setting.
      // The motion-safe: prefix contains the substring, so match on a boundary.
      expect(block.className).not.toMatch(/(^|\s)animate-pulse(\s|$)/)
    })
  })
})

describe('shape', () => {
  it('holds space for the weekly chart above the history list', () => {
    // Without this the list would render first and jump down when the chart
    // arrived — the layout shift a skeleton exists to prevent.
    const { container } = render(<HistoryListSkeleton label="Loading…" />)

    expect(container.querySelectorAll('li')).toHaveLength(5)
    expect(container.querySelector('div.h-40')).toBeInTheDocument()
  })

  it('does not put a chart above the menu list, which has none', () => {
    const { container } = render(<MenuListSkeleton label="Loading…" />)

    expect(container.querySelectorAll('li')).toHaveLength(4)
    expect(container.querySelector('div.h-40')).not.toBeInTheDocument()
  })

  it('renders a stable number of rows across re-renders', () => {
    // A randomized count would animate the skeleton's own shape while waiting.
    const { container, rerender } = render(<HistoryListSkeleton label="Loading…" />)
    const first = container.querySelectorAll('li').length

    rerender(<HistoryListSkeleton label="Loading…" />)

    expect(container.querySelectorAll('li')).toHaveLength(first)
  })

  it('lets the caller size a block without losing the shared styling', () => {
    const { container } = render(<SkeletonBlock className="h-12 w-12 rounded-lg" />)

    const block = container.firstChild
    expect(block.className).toContain('h-12')
    expect(block.className).toContain('bg-slate-200')
    expect(block.className).toContain('motion-safe:animate-pulse')
  })
})
