# DuaBiskutTelur 🍛

Snap a photo of your meal and get it graded. DuaBiskutTelur identifies every
dish on your plate (Malaysian favourites included — nasi lemak, roti canai,
teh tarik, char kway teow…), estimates calories and macros, scores the
combination 1–100 with a letter grade, and gives you friendly, practical
suggestions for your next meal.

Installable as a mobile-first PWA, with an Android Trusted Web Activity wrapper.

## Features

- 📸 **Photo analysis** — AI vision identifies each dish and portion; USDA
  FoodData Central resolves nutrition (model estimates as fallback, always
  labeled which source you're seeing)
- 🔖 **Barcode scanning** — exact label data via Open Food Facts, with a
  native `BarcodeDetector` fast path and a lazy-loaded ZXing fallback
- 🎯 **Deterministic grading** — the 1–100 score is pure, tunable arithmetic
  (macro balance / nutrient quality / portion / variety), never an AI opinion,
  with a per-meal "how grading works" breakdown
- 💬 **AI feedback** — highlights, concerns, and next-meal suggestions,
  goal-aware (weight loss / muscle gain / maintenance) and budget-aware
- 📊 **History & trends** — daily dashboard, 7-day charts, weight trend,
  water tracker, 29 achievements (3 secret)
- 🌐 **Three languages** (English, 中文, Bahasa Melayu) with CI-enforced
  dictionary parity; light/dark themes
- 👤 **Optional sign-in** — Google OAuth for persistent history; the full
  analyze flow also works anonymously
- 📄 **PDF export** of any saved meal report
- 📱 **PWA** — installable, offline app shell, gated background updates that
  never interrupt an in-flight analysis

## Screenshots

<!-- TODO: add screenshots/GIFs (capture screen, grade reveal, history, dark mode) -->
*Screenshots coming soon.*

## How it works

```
photo ──▶ Spring Boot backend
            1. Gemini vision (gemini-flash-latest) ─▶ foods + portions + fallback macros (strict JSON)
            2. USDA FoodData Central        ─▶ calories/macros per identified food
               (falls back to the model's estimate when a dish isn't in USDA)
            3. Deterministic scoring engine ─▶ score 1–100, grade A+–D (pure Java, unit-tested)
            4. Gemini feedback (gemini-flash-lite-latest) ─▶ highlights, concerns, suggestions
          ──▶ React PWA renders the graded meal + history trend
```

All API keys live in the backend only — never in the frontend.

## Getting started

Prerequisites: **Java 17+**, **Maven**, **Node.js 18+**.

The backend boots with **zero configuration** and serves a realistic mocked
analysis — ideal for UI development. Real analysis and sign-in need free API
keys:

| Key | Where to get it | Behaviour without it |
|---|---|---|
| `GEMINI_API_KEY` | [aistudio.google.com](https://aistudio.google.com) → "Get API key" | Mocked analysis (great for dev/demo) |
| `GEMINI_API_KEY_2`, `_3` | Same (optional backups) | Skipped if blank |
| `USDA_API_KEY` | [fdc.nal.usda.gov/api-key-signup](https://fdc.nal.usda.gov/api-key-signup) | Nutrition uses the vision model's estimates |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | [console.cloud.google.com](https://console.cloud.google.com) → Credentials → OAuth client ID (Web) | App runs; Google sign-in fails until set |

Copy `.env.example` to `.env` and fill in what you have. For sign-in, register
these **Authorized redirect URIs** on the OAuth client:

```
https://<your-domain>/login/oauth2/code/google   # production
http://localhost:8080/login/oauth2/code/google   # local testing
```

> **Free-tier note:** Gemini's free tier allows ~15 requests/minute per key.
> On HTTP 429 the backend automatically falls back to `GEMINI_API_KEY_2`,
> then `_3`, and prefers the primary again once its cooldown passes. If every
> key is limited it backs off (2s/4s/8s) and finally returns a friendly
> "Analyzer is busy" with a retry button.

### Run it

```bash
# Backend (port 8080)
cd backend
mvn spring-boot:run

# Frontend (port 5173 — proxies /api to the backend)
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

### Tests

```bash
cd backend && mvn test                        # scoring bands + end-to-end endpoint tests
cd frontend && node scripts/check-i18n-parity.mjs && npm run build
```

CI runs all of the above on every push and pull request
(`.github/workflows/ci.yml`).

## Production build & deployment

```bash
cd frontend && npm run build   # dist/ with precached app shell + manifest (installable PWA)
cd backend && mvn package      # runnable jar in target/
```

Or use the included **Docker Compose** setup (multi-stage builds; nginx serves
the PWA and proxies `/api` to the backend; per-IP rate limiting and security
headers included):

```bash
# with a filled .env (chmod 600) next to docker-compose.yml
docker compose build
docker compose up -d
```

The frontend binds to `127.0.0.1:8081` by default (for hosts running a
front nginx / multiple sites); change to `"80:80"` for a dedicated host.
Terminate TLS in front (CDN or host nginx + certbot) and forward
`X-Forwarded-Proto` / `X-Forwarded-For`. Full deployment, operations, and
architecture details: **[HANDOVER.md](HANDOVER.md)**.

## Scoring (deterministic, not AI)

| Component | Points | Rule |
|---|---|---|
| Balance | 40 | Deviation from ~30% protein / 40% carbs / 30% fat by calories |
| Nutrient quality | 30 | Bonuses: fiber ≥ 8g, vegetables/fruit. Penalties: sugar > 25g, sodium > 800mg, deep-fried |
| Portion sanity | 20 | Penalized over 50% of the daily budget (default 2000 kcal, editable) or under 250 kcal |
| Variety | 10 | Full bonus at 3+ distinct food groups |

Grades: 90–100 A+, 80–89 A, 70–79 B, 55–69 C, below 55 D.
Every threshold is a constant in `application.yml` (`scoring.*`).

## Project structure

```
backend/    Spring Boot 3 (Java 17) — controller → service → client layers;
            GeminiClient implements VisionAnalysisClient + FeedbackClient so the
            AI provider can be swapped without touching the service layer
frontend/   React 18 + Vite + Tailwind, installable PWA (vite-plugin-pwa)
android/    TWA wrapper — generate.js regenerates the project via Bubblewrap
ops/        Host-side scripts (nightly DB backup)
```

## Roadmap

Result correction (edit portions / remove misidentified items), manual meal
entry, visitor-to-account history migration, account deletion & data export,
history pagination, reminders, monitoring. See
[HANDOVER.md](HANDOVER.md) §11–12 for the prioritized list.

## Troubleshooting (Windows)

If the backend dies at startup with `Unable to establish loopback connection …
Invalid argument: connect`, your machine is blocking the JVM's internal
unix-domain socket in the user Temp folder (commonly endpoint-security
software). Point the JDK at a different directory:

```powershell
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=C:\Users\Public"
```

## License

[MIT](LICENSE) © 2026 Chai Jie Sheng
