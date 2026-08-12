import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TIER_LABELS, TIER_ORDER } from '../tierMeta.js'

vi.mock('../shareCard.js', () => ({
  buildMenuShareCard: vi.fn(),
  downloadBlob: vi.fn(),
}))
vi.mock('../i18n/LanguageContext.jsx', () => ({
  useLanguage: () => ({
    t: (key, arg) => {
      const strings = {
        'results.share': 'Share',
        'results.shareAria': 'Share this result',
        'results.preparingShare': 'Preparing image…',
        'results.shareError': "Couldn't share this report — try again.",
        'app.title1': 'Dua',
        'app.title2': 'BiskutTelur',
      }
      if (key === 'menuResults.dishCount') return `${arg} dishes ranked`
      if (key === 'menuResults.shareText') return `I ranked ${arg} dishes off this menu!`
      return strings[key] ?? key
    },
    lang: 'en',
  }),
}))
vi.mock('../theme/ThemeContext.jsx', () => ({ useTheme: () => ({ theme: 'light' }) }))
vi.mock('./FoodCard.jsx', () => ({ default: () => null }))
vi.mock('./AccordionSection.jsx', () => ({ default: ({ children }) => <div>{children}</div> }))

const { buildMenuShareCard } = await import('../shareCard.js')
const { default: MenuResultsScreen } = await import('./MenuResultsScreen.jsx')

const RESULT = {
  dishCount: 12,
  truncated: false,
  tiers: [
    { tier: 'HANG', dishes: [{ name: 'Ikan bakar', nutrition: {} }, { name: 'Sayur campur', nutrition: {} }] },
    { tier: 'TOP', dishes: [{ name: 'Ayam percik', nutrition: {} }] },
    { tier: 'RENSHANGREN', dishes: [] },
    { tier: 'NPC', dishes: [] },
    { tier: 'LAWANLE', dishes: [{ name: 'Teh tarik', nutrition: {} }] },
  ],
}

const icon = () => screen.getByRole('button', { name: 'Share this result' })

/**
 * A menu scan had no share control at all — not at the top, not at the bottom.
 * The tier list is the most shareable thing the app produces and it was the one
 * result you could not show anyone.
 *
 * It cannot reuse the meal card: a menu result has no score, no grade and no
 * totals, so `buildMenuShareCard` draws the tiers instead. What these cover is
 * that it is fed from the same place the screen renders from — a share image
 * that disagreed with the screen would be worse than none.
 */
describe('sharing a menu scan', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    buildMenuShareCard.mockResolvedValue({ blob: new Blob(), shareText: 'ranked' })
  })

  it('offers both controls, like the meal report', () => {
    render(<MenuResultsScreen result={RESULT} onScanAnother={() => {}} />)

    expect(icon()).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Share' })).toBeInTheDocument()
  })

  it('builds the card from tierMeta, so the image cannot contradict the screen', async () => {
    render(<MenuResultsScreen result={RESULT} onScanAnother={() => {}} />)

    await userEvent.click(icon())
    await waitFor(() => expect(buildMenuShareCard).toHaveBeenCalled())

    const [args] = buildMenuShareCard.mock.calls[0]
    expect(args.tierRows.map((r) => r.label)).toEqual(TIER_ORDER.map((tier) => TIER_LABELS[tier]))
    expect(args.dishCount).toBe(12)
  })

  it('counts every tier, including the empty ones', async () => {
    render(<MenuResultsScreen result={RESULT} onScanAnother={() => {}} />)

    await userEvent.click(icon())
    await waitFor(() => expect(buildMenuShareCard).toHaveBeenCalled())

    // Five rows always, so the card keeps its shape whatever the menu held —
    // an absent tier is information, not a row to omit.
    const [args] = buildMenuShareCard.mock.calls[0]
    expect(args.tierRows).toHaveLength(5)
    expect(args.tierRows.map((r) => r.count)).toEqual([2, 1, 0, 0, 1])
    expect(args.tierRows[0].dishes).toEqual(['Ikan bakar', 'Sayur campur'])
  })

  it('carries a colour per tier, since the labels are the whole point', async () => {
    render(<MenuResultsScreen result={RESULT} onScanAnother={() => {}} />)

    await userEvent.click(icon())
    await waitFor(() => expect(buildMenuShareCard).toHaveBeenCalled())

    const [args] = buildMenuShareCard.mock.calls[0]
    args.tierRows.forEach((row) => expect(row.colour).toMatch(/^#[0-9a-f]{6}$/i))
  })

  it('survives a menu whose tiers came back missing', async () => {
    // The backend sends only non-empty groups in some responses; a tier the
    // screen renders as empty must not become undefined on the card.
    render(<MenuResultsScreen result={{ dishCount: 0, truncated: false, tiers: [] }} onScanAnother={() => {}} />)

    await userEvent.click(icon())
    await waitFor(() => expect(buildMenuShareCard).toHaveBeenCalled())

    const [args] = buildMenuShareCard.mock.calls[0]
    expect(args.tierRows.map((r) => r.count)).toEqual([0, 0, 0, 0, 0])
    expect(args.tierRows.every((r) => Array.isArray(r.dishes))).toBe(true)
  })

  it('shows the failure once, on the control with room for it', async () => {
    buildMenuShareCard.mockRejectedValue(new Error('canvas exploded'))
    render(<MenuResultsScreen result={RESULT} onScanAnother={() => {}} />)

    await userEvent.click(icon())

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent("Couldn't share"))
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })
})
