// Single source of truth for the 5 menu-ranking tiers. The labels are a
// specific Chinese meme tier list — deliberately NOT translated per language
// (see i18n/en.js etc., which never define these strings), so this file is
// the only place they exist.

// Best-to-worst, matching the reference tier list layout.
export const TIER_ORDER = ['HANG', 'TOP', 'RENSHANGREN', 'NPC', 'LAWANLE']

export const TIER_LABELS = {
  HANG: '夯',
  TOP: '顶级',
  RENSHANGREN: '人上人',
  NPC: 'NPC',
  LAWANLE: '拉完了',
}

// Per-theme hex, same "keep in sync with tailwind.config.js tier.*" convention
// GradeReveal.jsx's GRADE_COLORS uses for the grade.* tokens.
export const TIER_COLORS = {
  light: {
    HANG: '#b91c1c',
    TOP: '#b45309',
    RENSHANGREN: '#a16207',
    NPC: '#4b5563',
    LAWANLE: '#374151',
  },
  dark: {
    HANG: '#f87171',
    TOP: '#fbbf24',
    RENSHANGREN: '#facc15',
    NPC: '#9ca3af',
    LAWANLE: '#d1d5db',
  },
}
