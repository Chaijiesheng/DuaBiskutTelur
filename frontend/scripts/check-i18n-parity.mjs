// Fails (exit 1) if the en/zh/ms dictionaries ever drift out of key parity —
// a missing key silently falls back to English at runtime (LanguageContext),
// so this check is the only thing that makes translation gaps visible. Run
// directly (`node scripts/check-i18n-parity.mjs`) or via CI.
import en from '../src/i18n/en.js'
import zh from '../src/i18n/zh.js'
import ms from '../src/i18n/ms.js'

function keys(obj, prefix = '') {
  return Object.entries(obj).flatMap(([k, v]) =>
    typeof v === 'object' && v !== null && !Array.isArray(v) ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`],
  )
}

const dicts = { en: new Set(keys(en)), zh: new Set(keys(zh)), ms: new Set(keys(ms)) }
let failed = false

for (const lang of ['zh', 'ms']) {
  const missing = [...dicts.en].filter((k) => !dicts[lang].has(k))
  const extra = [...dicts[lang]].filter((k) => !dicts.en.has(k))
  if (missing.length > 0 || extra.length > 0) {
    failed = true
    if (missing.length > 0) console.error(`✗ ${lang} is missing keys:`, missing)
    if (extra.length > 0) console.error(`✗ ${lang} has extra keys:`, extra)
  }
}

if (en.analyzing.insights.length !== zh.analyzing.insights.length ||
    en.analyzing.insights.length !== ms.analyzing.insights.length) {
  failed = true
  console.error('✗ analyzing.insights arrays differ in length:',
    en.analyzing.insights.length, zh.analyzing.insights.length, ms.analyzing.insights.length)
}

if (failed) process.exit(1)
console.log(`✓ i18n parity OK — ${dicts.en.size} keys in each of en/zh/ms`)
