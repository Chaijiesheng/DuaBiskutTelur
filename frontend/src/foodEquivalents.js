// Rough, single-serving calorie estimates for common local dishes — used only to
// help users *visualize* a calorie number, not as nutrition data (see scoring
// engine / USDA lookup for that). labelKey resolves via i18n/*.js foodEquivalents.*.
export const REFERENCE_FOODS = [
  { emoji: '🍛', labelKey: 'plates_nasi_lemak', kcal: 400 },
  { emoji: '🍗', labelKey: 'plates_chicken_rice', kcal: 600 },
  { emoji: '🫓', labelKey: 'pieces_roti_canai', kcal: 300 },
]

/** Rounds to the nearest half so counts read naturally ("~7", "~6.5"). */
function formatCount(n) {
  const rounded = Math.round(n * 2) / 2
  return rounded % 1 === 0 ? String(rounded) : rounded.toFixed(1)
}

export function foodEquivalents(calories) {
  if (!calories || calories <= 0) return []
  return REFERENCE_FOODS.map((food) => ({
    ...food,
    count: formatCount(calories / food.kcal),
  }))
}
