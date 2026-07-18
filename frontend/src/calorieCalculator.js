// Estimates a daily calorie budget from a simple profile: age, sex, weight,
// height, exercise frequency, an optional daily step count, and a goal. This
// is a rough estimate, not medical advice — the formulas below are standard,
// widely-used approximations.

const MIN_BUDGET = 1200
const MAX_BUDGET = 4500

// Calories burned per step for a 70kg adult, scaled by actual weight below.
// Steps up to BASELINE_STEPS are treated as ordinary daily movement already
// folded into the activity multiplier, so only steps beyond that add extra
// calories — this avoids double-counting normal walking as "exercise".
const BASELINE_STEPS = 3000
const KCAL_PER_STEP_AT_70KG = 0.04

// The activity multiplier already assumes a level of daily movement —
// "daily_workout" (1.725x) bakes in very-active-lifestyle steps, so crediting
// tracked steps at full weight on top of it double-counts the same activity.
// Discount step credit as the multiplier already accounts for more of it.
// Mirrors CalorieBudget.java on the backend — keep both in sync.
const STEP_CREDIT_MULTIPLIER = {
  not_workout: 1.0,
  normal_workout: 0.6,
  daily_workout: 0.3,
}

// Standard Harris-Benedict/Mifflin-St Jeor activity multipliers, collapsed to
// the three categories this app asks for.
export const ACTIVITY_MULTIPLIER = {
  not_workout: 1.2, // sedentary — little or no structured exercise
  normal_workout: 1.55, // moderately active — exercise a few times a week
  daily_workout: 1.725, // very active — exercise most/every day
}

// Labels/hints are translated — see i18n/*.js under profileScreen.exercise.<value>.
export const EXERCISE_OPTIONS = [
  { value: 'not_workout' },
  { value: 'normal_workout' },
  { value: 'daily_workout' },
]

// Percentage adjustment applied to TDEE, capped in absolute kcal so it can't
// get disproportionate at very low or very high TDEE. Mirrors
// CalorieBudget.java on the backend — keep both in sync. Unknown/missing goal
// is treated as maintenance: no adjustment.
const GOAL_ADJUSTMENT = {
  weight_loss: { pct: -0.2, cap: -750 },
  muscle_gain: { pct: 0.12, cap: 400 },
}

// Protein target ratio by goal — weight loss highest (protects lean mass in
// a deficit), maintenance lowest. Mirrors DashboardService.java.
const PROTEIN_GRAMS_PER_KG = {
  weight_loss: 2.0,
  muscle_gain: 1.8,
  maintenance: 1.5,
}

// Labels/hints are translated — see i18n/*.js under profileScreen.goal.<value>.
export const GOAL_OPTIONS = [
  { value: 'weight_loss' },
  { value: 'muscle_gain' },
  { value: 'maintenance' },
]

// Target macro split by goal, as % of daily calories — shown on the results
// screen as a comparison against a meal's actual split, never as a strict
// rule (there's no one correct diet). Computed entirely client-side, no
// backend call needed.
export const MACRO_TARGET_RATIO = {
  weight_loss: { protein: 35, carbs: 35, fat: 30 },
  muscle_gain: { protein: 30, carbs: 45, fat: 25 },
  maintenance: { protein: 25, carbs: 45, fat: 30 },
}

/** Mifflin-St Jeor BMR — the standard formula, needs biological sex for accuracy. */
export function calculateBmr({ age, weightKg, heightCm, sex }) {
  const base = 10 * weightKg + 6.25 * heightCm - 5 * age
  return sex === 'female' ? base - 161 : base + 5
}

export function stepsCalories(steps, weightKg, exerciseFrequency) {
  const extraSteps = Math.max(0, steps - BASELINE_STEPS)
  const credit = STEP_CREDIT_MULTIPLIER[exerciseFrequency] ?? STEP_CREDIT_MULTIPLIER.not_workout
  return extraSteps * KCAL_PER_STEP_AT_70KG * (weightKg / 70) * credit
}

function goalAdjustment(goal, tdee) {
  const adjustment = GOAL_ADJUSTMENT[goal]
  if (!adjustment) return 0
  return adjustment.pct < 0 ? Math.max(tdee * adjustment.pct, adjustment.cap) : Math.min(tdee * adjustment.pct, adjustment.cap)
}

export function isProfileComplete(profile) {
  if (!profile) return false
  const { age, sex, weightKg, heightCm, goal } = profile
  return (
    Number(age) > 0 &&
    (sex === 'male' || sex === 'female') &&
    Number(weightKg) > 0 &&
    Number(heightCm) > 0 &&
    Boolean(ACTIVITY_MULTIPLIER[profile.exerciseFrequency]) &&
    Boolean(PROTEIN_GRAMS_PER_KG[goal])
  )
}

/** Daily calorie budget estimate, clamped to a sane range. */
export function calculateDailyBudget(profile) {
  if (!isProfileComplete(profile)) return null
  const bmr = calculateBmr(profile)
  const tdee = bmr * ACTIVITY_MULTIPLIER[profile.exerciseFrequency] +
    stepsCalories(Number(profile.steps || 0), Number(profile.weightKg), profile.exerciseFrequency)
  const total = tdee + goalAdjustment(profile.goal, tdee)
  const rounded = Math.round(total / 10) * 10
  return Math.min(MAX_BUDGET, Math.max(MIN_BUDGET, rounded))
}

/** Daily protein target in grams, or null if weight/goal aren't known yet. */
export function calculateProteinTarget(profile) {
  if (!profile || !(Number(profile.weightKg) > 0)) return null
  const ratio = PROTEIN_GRAMS_PER_KG[profile.goal] ?? PROTEIN_GRAMS_PER_KG.maintenance
  return Math.round(Number(profile.weightKg) * ratio)
}

// Same 15%-of-calories fallback as the backend's DashboardService.proteinTargetFor
// — used for visitors, who may not have a profile (weight) to base a
// bodyweight-ratio target on.
const PROTEIN_CALORIE_SHARE = 0.15

/** Protein target in grams: bodyweight-based when a profile exists, otherwise a calorie-share estimate. */
export function estimateProteinTarget(profile, dailyBudgetKcal) {
  return calculateProteinTarget(profile) ?? Math.round((dailyBudgetKcal * PROTEIN_CALORIE_SHARE) / 4)
}
