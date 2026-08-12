import { describe, expect, it } from 'vitest'
import {
  calculateBmr,
  calculateDailyBudget,
  calculateProteinTarget,
  estimateProteinTarget,
  isProfileComplete,
  stepsCalories,
  MACRO_TARGET_RATIO,
} from './calorieCalculator.js'

/**
 * This file and backend/src/main/java/.../CalorieBudget.java implement the same
 * formula twice — the frontend previews a budget as the user types, the backend
 * stores the authoritative one so it isn't client-tamperable. Both carry a
 * "keep in sync" comment and nothing enforced it, so the two could drift and the
 * only symptom would be a number that changes the moment you press Save.
 *
 * PARITY_CASES below is the enforcement: the identical table exists in
 * CalorieBudgetTest.java. Either implementation drifting breaks its own test,
 * and the fix is to bring it back to the shared numbers rather than to edit
 * them. Change a case here only alongside the Java one.
 */
const PARITY_CASES = [
  {
    name: 'baseline maintenance, moderate steps',
    profile: { age: 30, sex: 'male', weightKg: 70, heightCm: 175, steps: 6000, exerciseFrequency: 'normal_workout', goal: 'maintenance' },
    expected: 2630,
  },
  {
    name: 'weight loss applies the percentage, not the cap',
    profile: { age: 25, sex: 'female', weightKg: 55, heightCm: 160, steps: 12000, exerciseFrequency: 'not_workout', goal: 'weight_loss' },
    expected: 1440,
  },
  {
    name: 'muscle gain hits the absolute cap before the percentage',
    profile: { age: 40, sex: 'male', weightKg: 90, heightCm: 180, steps: 15000, exerciseFrequency: 'daily_workout', goal: 'muscle_gain' },
    expected: 3740,
  },
  {
    name: 'clamps up to the floor',
    profile: { age: 70, sex: 'female', weightKg: 35, heightCm: 140, steps: 0, exerciseFrequency: 'not_workout', goal: 'weight_loss' },
    expected: 1200,
  },
  {
    name: 'clamps down to the ceiling',
    profile: { age: 20, sex: 'male', weightKg: 150, heightCm: 200, steps: 30000, exerciseFrequency: 'daily_workout', goal: 'muscle_gain' },
    expected: 4500,
  },
]

describe('daily budget parity with the backend', () => {
  it.each(PARITY_CASES)('$name', ({ profile, expected }) => {
    expect(calculateDailyBudget(profile)).toBe(expected)
  })
})

describe('calculateBmr', () => {
  it('uses the Mifflin-St Jeor constants, offset by sex', () => {
    const base = { age: 30, weightKg: 70, heightCm: 175 }
    // 10*70 + 6.25*175 - 5*30 = 1643.75, then +5 male / -161 female.
    expect(calculateBmr({ ...base, sex: 'male' })).toBeCloseTo(1648.75, 5)
    expect(calculateBmr({ ...base, sex: 'female' })).toBeCloseTo(1482.75, 5)
  })
})

describe('stepsCalories', () => {
  it('only credits steps beyond the baseline already in the activity multiplier', () => {
    expect(stepsCalories(3000, 70, 'not_workout')).toBe(0)
    expect(stepsCalories(1000, 70, 'not_workout')).toBe(0)
    // 3000 extra steps at 0.04 kcal, bodyweight 70 (no scaling), full credit.
    expect(stepsCalories(6000, 70, 'not_workout')).toBeCloseTo(120, 5)
  })

  it('discounts step credit as the activity multiplier already accounts for more of it', () => {
    const sedentary = stepsCalories(10000, 70, 'not_workout')
    const moderate = stepsCalories(10000, 70, 'normal_workout')
    const veryActive = stepsCalories(10000, 70, 'daily_workout')
    // Otherwise a "daily workout" profile is paid twice for the same activity.
    expect(moderate).toBeCloseTo(sedentary * 0.6, 5)
    expect(veryActive).toBeCloseTo(sedentary * 0.3, 5)
  })

  it('falls back to full credit for an unknown frequency rather than zero', () => {
    expect(stepsCalories(10000, 70, 'something-else')).toBeCloseTo(stepsCalories(10000, 70, 'not_workout'), 5)
  })
})

describe('isProfileComplete', () => {
  const complete = {
    age: 30, sex: 'male', weightKg: 70, heightCm: 175,
    exerciseFrequency: 'normal_workout', goal: 'maintenance',
  }

  it('accepts a filled-in profile', () => {
    expect(isProfileComplete(complete)).toBe(true)
  })

  it('rejects anything missing or unrecognised', () => {
    expect(isProfileComplete(null)).toBe(false)
    expect(isProfileComplete({ ...complete, sex: 'unspecified' })).toBe(false)
    expect(isProfileComplete({ ...complete, exerciseFrequency: 'occasionally' })).toBe(false)
    expect(isProfileComplete({ ...complete, goal: 'get ripped' })).toBe(false)
    expect(isProfileComplete({ ...complete, weightKg: 0 })).toBe(false)
  })

  it('treats steps as optional', () => {
    expect(isProfileComplete({ ...complete, steps: undefined })).toBe(true)
  })

  it('returns null from calculateDailyBudget rather than a wrong number', () => {
    expect(calculateDailyBudget({ ...complete, sex: null })).toBeNull()
  })
})

describe('protein targets', () => {
  it('scales by bodyweight, highest for weight loss', () => {
    // Weight loss is highest on purpose: protein protects lean mass in a deficit.
    expect(calculateProteinTarget({ weightKg: 70, goal: 'weight_loss' })).toBe(140)
    expect(calculateProteinTarget({ weightKg: 70, goal: 'muscle_gain' })).toBe(126)
    expect(calculateProteinTarget({ weightKg: 70, goal: 'maintenance' })).toBe(105)
  })

  it('defaults an unknown goal to the maintenance ratio', () => {
    expect(calculateProteinTarget({ weightKg: 70, goal: undefined })).toBe(105)
  })

  it('needs a weight', () => {
    expect(calculateProteinTarget({ goal: 'maintenance' })).toBeNull()
    expect(calculateProteinTarget(null)).toBeNull()
  })

  it('falls back to a share of calories for visitors with no profile', () => {
    // Same 15%-of-calories rule as DashboardService.proteinTargetFor.
    expect(estimateProteinTarget(null, 2000)).toBe(75)
    // A real profile still wins over the estimate.
    expect(estimateProteinTarget({ weightKg: 70, goal: 'maintenance' }, 2000)).toBe(105)
  })
})

/**
 * The macro splits exist twice: here for display (MacroDonut) and in
 * MacroTargets.java for grading. Nothing enforces agreement at build time, so
 * this table and the identical one in MacroTargetsTest.java are what does.
 *
 * A drift is silent — nothing crashes, the grade just stops matching the target
 * shown on the same screen. That was exactly the bug: the donut said "protein
 * target 25%" while the engine graded everyone against 30%.
 */
describe('macro targets, in step with MacroTargets.java', () => {
  //                goal            protein carbs  fat
  const PARITY_CASES = [
    ['weight_loss', 35, 35, 30],
    ['muscle_gain', 30, 45, 25],
    ['maintenance', 25, 45, 30],
  ]

  it.each(PARITY_CASES)('%s matches the split the backend grades against', (goal, protein, carbs, fat) => {
    expect(MACRO_TARGET_RATIO[goal]).toEqual({ protein, carbs, fat })
  })

  it.each(PARITY_CASES)('%s adds up to a whole meal', (goal, protein, carbs, fat) => {
    // A split that does not sum to 100 makes the deviation arithmetic in
    // balancePoints meaningless — every meal would read as off target.
    expect(protein + carbs + fat).toBe(100)
  })
})
