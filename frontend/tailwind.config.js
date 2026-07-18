/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // WCAG AA (≥4.5:1 on white) — the old brighter set failed contrast on
        // the grade letters and on white-on-green buttons. Keep in sync with
        // GRADE_COLORS.light in GradeReveal.jsx; where text-grade-aplus sits on
        // a dark surface, pair it with dark:text-green-400.
        grade: {
          aplus: '#15803d',
          a: '#166534',
          b: '#4d7c0f',
          c: '#b45309',
          d: '#b91c1c',
        },
      },
      keyframes: {
        chopstickFloat: {
          '0%, 100%': { transform: 'translateY(0) rotate(-4deg)' },
          '50%': { transform: 'translateY(-9px) rotate(4deg)' },
        },
        chopstickTap: {
          '0%': { transform: 'scale(1) rotate(0deg)' },
          '30%': { transform: 'scale(1.2) rotate(-10deg)' },
          '60%': { transform: 'scale(0.9) rotate(8deg)' },
          '100%': { transform: 'scale(1) rotate(0deg)' },
        },
        softPulse: {
          '0%': { transform: 'scale(0.8)', opacity: '0.55' },
          '100%': { transform: 'scale(1.2)', opacity: '0' },
        },
      },
      animation: {
        'chopstick-float': 'chopstickFloat 2.6s ease-in-out infinite',
        'chopstick-tap': 'chopstickTap 0.45s ease-out',
        'soft-pulse': 'softPulse 2.4s ease-out infinite',
      },
    },
  },
  plugins: [],
}
