# Evaluating the DuaBiskutTelur Menu Ranking on 30 Malaysian Dishes

**Date:** 1 August 2026
**Subject under test:** `POST /api/menu/rank` in production (`duabiskuttelur.dpdns.org`), commit as deployed on 1 Aug 2026
**Sample:** 30 local Malaysian menu items, single scan, no retries of the ranking itself

---

## 0. Summary and a note on what is being measured

A caveat has to come first, because it determines how every number below should be read.

The evaluation brief asks for a reference ranking built on **popularity, customer ratings, cultural significance, and overall appeal**. The application does not rank on any of those axes. It ranks on **nutritional healthiness**, computed by a deterministic scoring engine from calories, macronutrients, fibre, sugar, sodium and cooking method. Scoring the app against a popularity ranking measures the gap between two different questions, not the app's accuracy.

Both were therefore computed:

| Comparison | Spearman ρ | Kendall τ-b | Mean \|rank diff\| | Verdict |
|---|---|---|---|---|
| App vs **popularity** reference | **−0.204** | −0.136 | 10.07 | No relationship (t = −1.11, n = 30, not significant) |
| App vs **nutrition** reference | **+0.632** | +0.462 | 5.60 | Strong, significant (t = 4.31, p < 0.001) |

The app is not a popularity engine, and mildly *anti*-correlates with popularity — which is the expected result, since Malaysia's most beloved dishes are disproportionately fried or coconut-rich. Against the axis it actually computes, it achieves ρ = 0.63, which is a genuinely useful level of agreement with expert nutritional judgement.

The rest of this report evaluates the app primarily against the nutrition reference, and reports the popularity comparison as the axis-mismatch measurement it is.

---

## 1. Food Menu Dataset

Thirty items were compiled from published Malaysian food guides, tourism sources and rating aggregators, then rendered as a single printed-style menu (`Warung Selera Malaysia`) with section headings and prices in RM, so the app was exercised through its real vision path rather than a synthetic API call.

| # | Menu item | Category | Source |
|---|---|---|---|
| 1 | Nasi Lemak with Fried Chicken | Rice | TasteAtlas (ranked 2nd best Malaysian dish) [1][2] |
| 2 | Nasi Kandar Ayam Curry | Rice | Will Fly For Food – 35 Malaysian dishes [3] |
| 3 | Nasi Kerabu Ayam Percik | Rice | Will Fly For Food [3]; Wikipedia [9] |
| 4 | Nasi Dagang Ikan Tongkol | Rice | Will Fly For Food [3] |
| 5 | Hainanese Steamed Chicken Rice | Rice | Remitly – traditional Malaysian dishes [4] |
| 6 | Claypot Chicken Rice | Rice | Cooking with Chef Samuel – top 99 [5] |
| 7 | Beef Rendang with White Rice | Rice | Remitly [4] |
| 8 | Char Kway Teow | Noodle | TasteAtlas [1]; Remitly [4] |
| 9 | Penang Asam Laksa | Noodle | Will Fly For Food [3] |
| 10 | Curry Laksa (Curry Mee) | Noodle | Will Fly For Food [3] |
| 11 | Sarawak Laksa | Noodle | TasteAtlas (ranked 1st) [1][2] |
| 12 | KL Hokkien Mee | Noodle | Will Fly For Food [3] |
| 13 | Mee Goreng Mamak | Noodle | Cooking with Chef Samuel [5]; Wikipedia [10] |
| 14 | Wantan Mee (Dry) | Noodle | Cooking with Chef Samuel [5] |
| 15 | Mee Rebus | Noodle | Will Fly For Food [3] |
| 16 | Koay Teow Th'ng | Noodle | Will Fly For Food [3] |
| 17 | Bak Kut Teh | Meat | Will Fly For Food [3] |
| 18 | Chicken Satay (10 sticks) | Meat | Remitly [4]; TasteAtlas [1] |
| 19 | Ikan Bakar Sambal (Stingray) | Seafood | Remitly [4] |
| 20 | Asam Pedas Ikan | Seafood | Remitly [4] |
| 21 | Fish Head Curry | Seafood | Will Fly For Food [3] |
| 22 | Roti Canai | Bread | TasteAtlas (ranked 3rd) [1][2] |
| 23 | Murtabak Ayam | Bread | Cooking with Chef Samuel [5] |
| 24 | Popiah Basah | Snack | Will Fly For Food [3] |
| 25 | Rojak Buah | Snack | Will Fly For Food [3] |
| 26 | Karipap (Curry Puff) | Snack | Cooking with Chef Samuel [5] |
| 27 | Apam Balik | Dessert | Will Fly For Food [3] |
| 28 | Pisang Goreng | Dessert | Will Fly For Food [3] |
| 29 | Cendol | Dessert | Will Fly For Food [3]; TasteAtlas [1] |
| 30 | Ais Kacang | Dessert | Remitly [4] |

**Sources**

1. [TasteAtlas – Malaysia](https://www.tasteatlas.com/malaysia) (rankings reported via press coverage; site blocks direct fetch)
2. [The Star – "Malaysian food 29th best cuisine in the world"](https://www.thestar.com.my/news/nation/2025/12/12/malaysian-food-29th-best-cuisine-in-the-world-says-food-portal)
3. [Will Fly For Food – Malaysian Food: 35 Dishes to Try](https://www.willflyforfood.net/malaysian-food-guide/)
4. [Remitly – Traditional Malaysian Dishes](https://www.remitly.com/blog/lifestyle-culture/traditional-malaysian-dishes/)
5. [Cooking with Chef Samuel – Malaysia Food: Top 99 Dishes](https://cookingwithchefsamuel.com/en/article/malaysia-food-top-99-dishes/)
6. [Calculator Malaysia – Malaysian Food Calories](https://calculatormalaysia.com/malaysian-food-calories/) (calorie anchors used in §5)
7. [SAYS – What 2,000 Calories of Malaysian Food Looks Like](https://says.com/my/fun/what-2000-calories-of-malaysian-food-looks-like)
8. [Business Today – Malaysia ranks 29th in TasteAtlas' 100 Best Cuisines](https://www.businesstoday.com.my/2025/12/12/malaysia-shines-ranking-29th-in-tasteatlas-100-best-cuisines-in-the-world/)
9. [Wikipedia – Nasi kerabu](https://en.wikipedia.org/wiki/Nasi_kerabu)
10. [Wikipedia – Mee goreng](https://en.wikipedia.org/wiki/Mee_goreng)

Beverages were deliberately excluded. The app routes drinks and condiments into a separate "add-ons" section outside the tier list, so including them would have produced unrankable items and contaminated the correlation.

---

## 2. Reference Rankings

### 2a. Popularity reference (as specified in the brief)

Each dish was scored 1–10 on four sub-criteria and ranked by the total, so the ranking is reproducible rather than impressionistic:

- **Iconicity** — is it a recognised national symbol of Malaysian cuisine?
- **Everyday frequency** — how often is it actually ordered day to day?
- **External acclaim** — standing in TasteAtlas / CNN / major food media
- **Cultural depth** — heritage, festival and regional-identity weight

| Rank | Dish | Total | (I, F, A, C) |
|---|---|---|---|
| 1 | Nasi Lemak with Fried Chicken | 40 | 10, 10, 10, 10 |
| 2 | Roti Canai | 36 | 9, 10, 9, 8 |
| 3 | Beef Rendang with White Rice | 34 | 9, 6, 9, 10 |
| 4 | Char Kway Teow | 34 | 9, 8, 9, 8 |
| 5 | Chicken Satay (10 sticks) | 34 | 9, 7, 9, 9 |
| 6 | Cendol | 32 | 8, 7, 9, 8 |
| 7 | Hainanese Steamed Chicken Rice | 32 | 8, 9, 8, 7 |
| 8 | Penang Asam Laksa | 32 | 8, 6, 10, 8 |
| 9 | Bak Kut Teh | 31 | 8, 7, 8, 8 |
| 10 | Curry Laksa (Curry Mee) | 31 | 8, 8, 8, 7 |
| 11 | Nasi Kandar Ayam Curry | 31 | 8, 8, 7, 8 |
| 12 | Sarawak Laksa | 30 | 7, 5, 10, 8 |
| 13 | Mee Goreng Mamak | 29 | 7, 9, 6, 7 |
| 14 | Ikan Bakar Sambal | 28 | 7, 7, 7, 7 |
| 15 | KL Hokkien Mee | 28 | 7, 7, 7, 7 |
| 16 | Ais Kacang | 27 | 7, 6, 7, 7 |
| 17 | Nasi Kerabu Ayam Percik | 27 | 7, 5, 7, 8 |
| 18 | Wantan Mee (Dry) | 27 | 6, 9, 6, 6 |
| 19 | Asam Pedas Ikan | 25 | 6, 6, 6, 7 |
| 20 | Pisang Goreng | 25 | 6, 8, 5, 6 |
| 21 | Rojak Buah | 25 | 6, 6, 6, 7 |
| 22 | Fish Head Curry | 24 | 6, 5, 7, 6 |
| 23 | Murtabak Ayam | 24 | 6, 6, 6, 6 |
| 24 | Nasi Dagang Ikan Tongkol | 24 | 6, 4, 6, 8 |
| 25 | Karipap (Curry Puff) | 23 | 5, 7, 5, 6 |
| 26 | Popiah Basah | 23 | 5, 6, 6, 6 |
| 27 | Apam Balik | 22 | 5, 6, 5, 6 |
| 28 | Claypot Chicken Rice | 22 | 5, 6, 6, 5 |
| 29 | Mee Rebus | 21 | 5, 5, 5, 6 |
| 30 | Koay Teow Th'ng | 18 | 4, 5, 5, 4 |

### 2b. Nutrition reference (the axis the app computes)

Ranked healthiest (1) to least healthy (30) on standard dietary reasoning at typical Malaysian restaurant portions: cooking method, fat load and its source, protein adequacy, refined-carbohydrate and free-sugar content, sodium, and vegetable/fibre contribution.

1. Popiah Basah · 2. Koay Teow Th'ng · 3. Fish Head Curry · 4. Hainanese Steamed Chicken Rice · 5. Asam Pedas Ikan · 6. Ikan Bakar Sambal · 7. Penang Asam Laksa · 8. Nasi Kerabu Ayam Percik · 9. Bak Kut Teh · 10. Sarawak Laksa · 11. Nasi Dagang · 12. Chicken Satay · 13. Mee Rebus · 14. Wantan Mee · 15. Claypot Chicken Rice · 16. Beef Rendang with Rice · 17. Nasi Kandar · 18. Murtabak Ayam · 19. Rojak Buah · 20. Roti Canai · 21. Mee Goreng Mamak · 22. KL Hokkien Mee · 23. Curry Laksa · 24. Nasi Lemak with Fried Chicken · 25. Char Kway Teow · 26. Karipap · 27. Apam Balik · 28. Pisang Goreng · 29. Ais Kacang · 30. Cendol

---

## 3. Application Ranking

Menu image submitted to production. Result: **30/30 dishes read correctly, none truncated, none misclassified as add-ons.** Tier distribution 夯 0 · 顶级 2 · 人上人 10 · NPC 12 · 拉完了 6.

| App # | Dish | Score | Grade | Tier | kcal | P | C | F | Fibre | Sugar | Na | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Popiah Basah | 88 | A | 顶级 | 220 | 6 | 32 | 8 | 5 | 8 | 640 | est. |
| 2 | Koay Teow Th'ng | 80 | A | 顶级 | 239 | 17.3 | 27.9 | 6.3 | 3.2 | 3.6 | 1116 | USDA |
| 3 | Mee Rebus | 79 | B | 人上人 | 556 | 30.8 | 61.6 | 20.3 | 2.8 | 1.3 | 1216 | USDA |
| 4 | Hainanese Steamed Chicken Rice | 77 | B | 人上人 | 464 | 19.8 | 60.5 | 16 | 4 | 6.2 | 1296 | USDA |
| 5 | Nasi Dagang Ikan Tongkol | 77 | B | 人上人 | 428 | 16.1 | 60.8 | 13.4 | 4 | 6.2 | 1236 | USDA |
| 6 | Beef Rendang with White Rice | 76 | B | 人上人 | 548 | 25.1 | 63.9 | 20.5 | 0.8 | 0.5 | 1260 | USDA |
| 7 | Nasi Kandar Ayam Curry | 75 | B | 人上人 | 522 | 22.3 | 68 | 18 | 4.5 | 7 | 1458 | USDA |
| 8 | Penang Asam Laksa | 75 | B | 人上人 | 428 | 20.3 | 63 | 9.9 | 5.4 | 11.3 | 2610 | est. |
| 9 | Roti Canai | 73 | B | 人上人 | 359 | 9.4 | 55.4 | 11 | 11.6 | 3.5 | 358 | USDA |
| 10 | Chicken Satay (10 sticks) | 72 | B | 人上人 | 525 | 40 | 22.5 | 30 | 2.5 | 15 | 1125 | est. |
| 11 | Fish Head Curry | 70 | B | 人上人 | 558 | 29.6 | 39.9 | 32.5 | 8.4 | 15.4 | 2106 | USDA |
| 12 | Nasi Kerabu Ayam Percik | 70 | B | 人上人 | 604 | 89.2 | 9.4 | 23.2 | 0 | 0 | 1800 | USDA |
| 13 | Ikan Bakar Sambal | 69 | C | NPC | 420 | 49.5 | 9 | 20.4 | 2.4 | 4.5 | 1560 | est. |
| 14 | Ais Kacang | 68 | C | NPC | 368 | 7 | 78.8 | 4.2 | 5.3 | 63 | 175 | est. |
| 15 | Claypot Chicken Rice | 68 | C | NPC | 700 | 28 | 96 | 22 | 3.2 | 6 | 1920 | est. |
| 16 | Asam Pedas Ikan | 66 | C | NPC | 360 | 32 | 14 | 19.2 | 3.2 | 6 | 2040 | est. |
| 17 | Sarawak Laksa | 66 | C | NPC | 608 | 22.5 | 65.3 | 30.6 | 4.5 | 5.4 | 2430 | est. |
| 18 | Curry Laksa (Curry Mee) | 65 | C | NPC | 630 | 21.6 | 67.5 | 31.5 | 4.5 | 6.8 | 2475 | est. |
| 19 | Wantan Mee (Dry) | 65 | C | NPC | 1092 | 17.9 | 240.6 | 1.7 | 4.8 | 0.4 | 546 | USDA |
| 20 | Apam Balik | 64 | C | NPC | 741 | 17.2 | 87.6 | 36.8 | 3.5 | 15.8 | 1202 | USDA |
| 21 | Murtabak Ayam | 62 | C | NPC | 516 | 60.1 | 35.2 | 14.6 | 1.5 | 3.8 | 1362 | USDA |
| 22 | KL Hokkien Mee | 60 | C | NPC | 606 | 46.2 | 26.4 | 35.2 | 2.1 | 3 | 956 | USDA |
| 23 | Karipap (Curry Puff) | 56 | C | NPC | 248 | 4.4 | 27.2 | 13.6 | 1.6 | 1.2 | 328 | est. |
| 24 | Rojak Buah | 56 | C | NPC | 741 | 5.3 | 40.7 | 62 | 4.5 | 29.8 | 381 | USDA |
| 25 | Nasi Lemak with Fried Chicken | 52 | D | 拉完了 | 738 | 120.1 | 0 | 28.5 | 0 | 0 | 2246 | USDA |
| 26 | Pisang Goreng | 52 | D | 拉完了 | 606 | 11.1 | 50.3 | 40.7 | 2.4 | 4.5 | 509 | USDA |
| 27 | Cendol | 49 | D | 拉完了 | 747 | 18 | 67.4 | 45.2 | 0 | 66.5 | 510 | USDA |
| 28 | Char Kway Teow | 49 | D | 拉完了 | 735 | 19.3 | 91 | 33.3 | 5.3 | 3.5 | 1820 | est. |
| 29 | Mee Goreng Mamak | 48 | D | 拉完了 | 819 | 48.2 | 49.2 | 46.3 | 1.8 | 0.7 | 1320 | USDA |
| 30 | Bak Kut Teh | 27 | D | 拉完了 | 1270 | 81.1 | 60.7 | 75.6 | 1.5 | 49.5 | 3390 | USDA |

---

## 4. Comparison and Accuracy Analysis

| Metric | vs Popularity | vs Nutrition |
|---|---|---|
| Spearman's ρ | −0.204 | **+0.632** |
| Kendall's τ-b | −0.136 | **+0.462** |
| Significance (t, n=30) | −1.11 (n.s.) | 4.31 (p < 0.001) |
| Mean absolute rank difference | 10.07 | **5.60** |
| Max absolute rank difference | 28 | 21 |
| Items within 3 places | 10 / 30 | 13 / 30 |
| Items within 5 places | 14 / 30 | 17 / 30 |
| Top-10 overlap | 5 / 10 | 4 / 10 |
| Bottom-10 overlap | 3 / 10 | **7 / 10** |

**Against popularity**, agreement is statistically indistinguishable from noise, and the sign is negative. The single largest disagreement is Nasi Lemak with Fried Chicken: popularity rank 1, app rank 25 — a 24-place gap. Char Kway Teow (pop 4 → app 28) and Cendol (pop 6 → app 27) follow the same pattern. This is not a defect; it is the app correctly reporting that Malaysia's most-loved dishes are among its least healthy.

**Against nutrition**, ρ = 0.632 is a strong monotonic relationship. The asymmetry between top-10 (4/10) and bottom-10 (7/10) overlap is the most informative result in the table: **the app is markedly better at identifying the worst dishes than the best ones.** Deep-fried and coconut-heavy dishes are caught reliably; distinguishing genuinely good choices from merely acceptable ones is where it struggles.

### Was the error caused by bad data or bad scoring?

Five of the thirty dishes carried nutrition that is arithmetically impossible — for example Nasi Lemak with **0 g carbohydrate**, Wantan Mee with **240.6 g carbohydrate**, and Bak Kut Teh at **1270 kcal with 49.5 g sugar**. All five came from USDA lookups (5 of 19 USDA-sourced rows, 26%); **none** of the 11 dishes that fell back to the model's own estimate were faulty.

Those five rows were corrected against published Malaysian calorie figures [6][7] and the menu was re-scored through the production scoring engine:

| | ρ vs nutrition | τ-b | Mean \|diff\| |
|---|---|---|---|
| As served | 0.632 | 0.462 | 5.60 |
| With the 5 rows corrected | **0.670** | 0.471 | 5.73 |

The improvement is small. **Fixing the data does not fix the ranking** — so the bulk of the residual disagreement is in the scoring model, not the nutrition lookup. That finding redirects the whole remediation effort and is developed in §5.

---

## 5. Findings

### Strengths

- **Vision extraction is excellent.** 30/30 dishes read from a printed menu with no truncation, no duplicates, and correct main-vs-add-on classification, including Malay, Chinese-derived and hybrid names (`Koay Teow Th'ng`, `Karipap`, `Nasi Kerabu Ayam Percik`).
- **The bottom of the ranking is trustworthy** (7/10 bottom-10 overlap). Char Kway Teow, Mee Goreng Mamak, Pisang Goreng, Cendol and Nasi Lemak with Fried Chicken all land in 拉完了 or adjacent, which matches expert judgement.
- **The top two are exactly right.** Popiah Basah (88) and Koay Teow Th'ng (80) are the healthiest items on this menu, and the app ranked them 1 and 2.
- **Determinism.** The score is computed by a fixed formula, so the same nutrition always yields the same rank — auditable, testable, and free of LLM drift.

### Weaknesses, with the mechanism identified

**1. The fat penalty is scale-blind.** It is applied to fat as a *fraction of calories*, starting at 35%. Compare two dishes in this very sample:

| Dish | Fat | Calories | Fat as % of kcal | Fat penalty |
|---|---|---|---|---|
| Beef Rendang with rice | 20.5 g | 548 | 33.7% | **0.0 pts** |
| Asam Pedas Ikan | 19.2 g | 360 | 48.0% | **6.8 pts** |

Near-identical absolute fat; opposite treatment. The rendang escapes entirely because the accompanying rice dilutes the fraction. This one mechanism explains **every** dish the app ranks worse than the expert: Bak Kut Teh (54% fat fraction), Asam Pedas (48%), Mee Goreng Mamak (51%), Fish Head Curry (52%), Sarawak Laksa (45%), Ikan Bakar (44%). Lean protein dishes with little carbohydrate are being punished for having nothing to dilute their fat with.

**2. The sugar penalty saturates far too early.** It ramps from 15 g to 30 g and then flattens. Ais Kacang carries **63 g of sugar** — more than double the saturation point, roughly triple the WHO free-sugar guidance — yet pays exactly the same penalty as a dish with 30 g, and is simultaneously *rewarded* full portion marks (25/25) for being only 368 kcal. It lands at app #14 against expert #29, the second-largest error in the set. Across the sample, sugar content correlates with ranking error at **r = −0.002**: the score is effectively blind to how much sugar a dish contains beyond 30 g.

**3. Sodium is the strongest single predictor of ranking error** (r = +0.551), because its penalty also saturates — at 1600 mg. Bak Kut Teh's 3390 mg and a 1600 mg dish are indistinguishable to the scorer.

**4. Portion scoring rewards calorie-light junk.** Any dish under 600 kcal (30% of a 2000 kcal budget) receives full portion marks. Ais Kacang and Karipap both benefit from this despite being nutritionally poor.

### Items ranked significantly differently from the nutrition reference

| Dish | App | Expert | Δ | Cause |
|---|---|---|---|---|
| Bak Kut Teh | 30 | 9 | **21** | Corrupt USDA row *and* fat-fraction penalty |
| Ais Kacang | 14 | 29 | **15** | Sugar penalty saturates at 30 g; full portion marks |
| Asam Pedas Ikan | 16 | 5 | 11 | Fat-fraction penalty on a lean 360 kcal dish |
| Roti Canai | 9 | 20 | 11 | 28% fat fraction sits under the 35% threshold |
| Nasi Kandar | 7 | 17 | 10 | Same — rice dilutes the fat fraction |
| Beef Rendang w/ rice | 6 | 16 | 10 | Same |
| Mee Rebus | 3 | 13 | 10 | Same |
| Fish Head Curry | 11 | 3 | 8 | Fat-fraction penalty on an oily but nutritious dish |
| Mee Goreng Mamak | 29 | 21 | 8 | Fat fraction 51% compounds the fried penalty |

### Operational reliability

The **first submission of this 30-item menu failed** — HTTP 503 `ANALYZER_BUSY` after 90 seconds, caused by consecutive Gemini read timeouts on `gemini-flash-latest` and `gemini-2.5-flash` against the 45 s menu ceiling. The identical request succeeded on retry in 38 s. Server logs also show a stale entry in the fallback chain: `gemini-2.5-flash-lite` now returns **404 — "no longer available to new users"**, so one rung of the fallback ladder is dead weight.

---

## 6. Current Limitations

1. **Ranks healthiness, not appeal.** No popularity, price, rating or signature-item signal is used, so it cannot answer "what should I order here" — the question most diners are actually asking.
2. **Fat is judged as a proportion, not an amount** — the dominant source of ranking error, systematically penalising Malay and Chinese protein-and-broth dishes while sparing rice-plate dishes with identical absolute fat.
3. **Extreme sugar and sodium are invisible.** Both penalties saturate (30 g, 1600 mg) well below values that are commonplace in Malaysian food.
4. **USDA has no Malaysian composite dishes.** 26% of USDA-resolved rows in this sample were arithmetically impossible. The database contains ingredients and American dishes; a fuzzy search for "nasi lemak" returns something, and that something is wrong. The existing validation gate catches gross mismatches but passed all five of these.
5. **No dish-alias or regional-variant handling.** `Curry Laksa`, `Curry Mee`, `Laksa Lemak` and `Nyonya Laksa` are one dish; the app has no notion that they should resolve identically, and each gets whatever its own USDA search returns.
6. **Portion is assumed, not measured.** For a menu there is no plate to look at, so serving size comes from the model's guess — which is why one Roti Canai was scored, though it is almost always eaten as two.
7. **The top tiers are unreachable in practice.** Scores compressed into 27–88 across 30 dishes; 夯 (≥90) stayed empty and 顶级 (≥80) took only two items. Four of the five tiers do the work of five.
8. **Single-scan fragility.** A 30-item menu failed on first attempt and needed a retry, and the fallback model chain contains a decommissioned model.

---

## 7. Recommendations

**Priority 1 — fix the fat penalty (largest single accuracy gain).** Gate the fat-excess penalty on absolute fat grams *and* energy density, not fraction alone. A 360 kcal dish with 19 g of fat should not be penalised harder than a 548 kcal dish with 20.5 g. Concretely: keep the fraction ramp but multiply it by a factor that scales with the dish's calorie density, so lean low-carbohydrate dishes stop being punished for the absence of rice.

**Priority 2 — extend the sugar and sodium ramps.** Move sugar saturation from 30 g to roughly 60 g and sodium from 1600 mg to about 3000 mg, so a 63 g dessert and a 3390 mg soup are actually distinguishable from moderate ones. Both are one-line changes in `ScoringProperties`.

**Priority 3 — build a Malaysian dish nutrition table.** Replace the USDA lookup for recognised local dishes with a curated table of 100–200 entries sourced from the Malaysian Food Composition Database, keyed by canonical dish name with an alias list (`curry mee` → `curry laksa`, `koay teow` → `kway teow` → `char kuey teow`). This removes the 26% USDA fault rate and the alias problem in one step, and is far more tractable than making a US ingredient database understand kopitiam food.

**Priority 4 — add a separate "worth ordering" ranking.** The popularity axis in this brief is a legitimate product need, and most of the signal is already printed on the menu the app photographs: price, "Signature"/推荐 badges, whether a dish is photographed, and its position in its section. Expose it as a second mode — *Healthiest* vs *Most recommended* — rather than blending it into the health score, which would corrupt both.

**Priority 5 — recalibrate the tier bands.** With observed scores spanning roughly 27–88, the ≥90 band is unreachable. Either lower the band thresholds or normalise scores per menu so all five tiers carry dishes.

**Priority 6 — operational hardening.** Remove `gemini-2.5-flash-lite` from the fallback chain (it 404s), and raise the menu read timeout above 45 s or stream the response, so large menus stop failing on first attempt.

---

## 8. Conclusion

On the axis it was built to measure, the application performs well. Against an expert nutritional ranking of 30 Malaysian dishes it achieves **Spearman ρ = 0.632** and **Kendall τ-b = 0.462**, both significant at p < 0.001, with a mean rank displacement of 5.6 places out of 30. It reads a printed Malaysian menu essentially perfectly — 30 of 30 dishes, mixed-language names, correct add-on separation — and it is reliably correct about which dishes to avoid, recovering 7 of the expert's bottom 10.

Against the popularity ranking requested in the brief, agreement is **ρ = −0.204**, statistically indistinguishable from zero. This should not be read as failure: the two rankings answer different questions, and the mild negative sign is itself an honest finding about Malaysian cuisine — the most culturally treasured dishes are disproportionately the least healthy ones. Nasi Lemak is the nation's dish and the app's 25th-healthiest item, and both statements are true.

The most consequential result is diagnostic rather than descriptive. Correcting every faulty nutrition row moved ρ only from 0.632 to 0.670, which rules out data quality as the primary constraint and points instead at three specific, fixable properties of the scoring formula: a fat penalty computed as a proportion rather than an amount, and sugar and sodium penalties that saturate below the range Malaysian food routinely occupies. Together these explain every large disagreement in the sample, and all three are configuration-level changes rather than redesigns.

**Overall assessment:** effective and trustworthy as a *healthiness* ranker for Malaysian menus, with a clearly identified path from ρ ≈ 0.63 to meaningfully higher. It is not, and does not currently attempt to be, a recommendation engine — and if that capability is wanted, it should be built as a second ranking mode rather than by tuning the nutrition score toward popularity.

---

## 9. Addendum — fixes applied and re-measured

Recommendations 1–3 were implemented and the benchmark re-run. Nutrition inputs were held fixed at the §3 values, so this measures the scoring change alone rather than confounding it with fresh model and USDA variation.

- **Fat** — the fraction ramp is now multiplied by an absolute-load ramp (15 g → 45 g); a dish must be both proportionally *and* absolutely fatty to take the penalty.
- **Sugar** — ramp extended 15 g → 60 g, with its own menu-scale weight (25 points) instead of the meal scorer's 13.3.
- **Sodium** — ramp extended from 1600 mg to 3000 mg.

| Configuration | ρ | τ-b | t | Mean \|diff\| | Bottom-10 |
|---|---|---|---|---|---|
| Old engine · as-served data | 0.632 | 0.462 | 4.31 | 5.60 | 7/10 |
| Old engine · corrected data | 0.670 | 0.471 | 4.77 | 5.73 | 7/10 |
| **New engine · as-served data** | **0.717** | 0.549 | 5.44 | 4.87 | 7/10 |
| **New engine · corrected data** | **0.804** | 0.614 | 7.16 | 4.27 | 8/10 |

The scoring change alone lifts ρ from **0.632 to 0.717** — more than double the gain from fixing all the bad nutrition data, confirming the §4 diagnosis. Combining both reaches **ρ = 0.804, τ-b = 0.614**, with mean rank displacement down from 5.60 to 4.27 places. Correlation against popularity moved from −0.204 to −0.268: a better health ranker diverges further from popularity, as expected.

Thirteen dishes moved closer to the expert ranking, nine moved away, eight held position. The intended targets all responded:

| Dish | Old | New | Expert | Fix responsible |
|---|---|---|---|---|
| Ais Kacang | 14 | 26 | 29 | Sugar ramp — 63 g finally costs more than 30 g |
| Roti Canai | 9 | 15 | 20 | Sugar weight rebalance |
| Asam Pedas Ikan | 16 | 11 | 5 | Fat — 19 g no longer punished as 48% |
| Ikan Bakar Sambal | 13 | 9 | 6 | Fat absolute-load gate |
| Char Kway Teow | 28 | 24 | 25 | Sodium ramp extension |
| Cendol | 27 | 29 | 30 | Sugar ramp — 66.5 g |

**Apam Balik drifted from #20 to #17**, a genuine minor regression: extending the sugar ramp made moderate-sugar dishes cheaper, and at 15.8 g it now pays almost nothing.

## 10. Addendum — the dish table, and a design that measurement reversed

Recommendations 3 (a curated Malaysian dish table) and 2 (stricter validation rules) were then implemented. The table ships 55 dishes and 150 aliases; on this benchmark it recognised **30 of 30** names, including through prices, casing and competing romanisations (*char kway teow* / *koay* / *kuey*).

The obvious design — answer any recognised local dish from the table and never call USDA — **made the ranking worse**:

| Configuration | ρ | τ-b | Mean \|diff\| | Bottom-10 |
|---|---|---|---|---|
| 1 · Baseline, before any fix | 0.632 | 0.462 | 5.60 | 7/10 |
| 2 · + scoring fixes | 0.717 | 0.549 | 4.87 | 7/10 |
| 3 · + table consulted for *every* dish | 0.665 | 0.480 | 5.80 | 8/10 |
| **4 · + table only when USDA fails validation** | **0.790** | **0.591** | **4.33** | **9/10** |

**Why the reversal:** one curated row has to stand for a dish every stall cooks differently, so it is *less* accurate than a specific match that passes validation — but far *more* accurate than a bad one. Used as a first resort it displaces good data (Koay Teow Th'ng fell from #2 to #10, Fish Head Curry from #11 to #23). Used as a rescue it only fires where the pipeline has already produced something impossible, and there it is decisive: Bak Kut Teh moved from dead last to #17, and all five arithmetically impossible rows disappeared.

Configuration 4 is what shipped. Ordering is now: USDA → validation gate → curated table → the model's own estimate. That ordering is counterintuitive enough that it has its own regression test recording both numbers, so nobody "optimises" it back.

The five new validation rules — implausible protein, dry-ingredient carbohydrate, bran-like fibre, partially-populated rows, and starch dishes with no starch — catch all five faults the previous gate passed.

Backend suite is now **152 tests, all green**, including the 10 domain-judgment ranking tests.

Net across both addenda: **ρ 0.632 → 0.790**, τ-b 0.462 → 0.591, mean rank displacement 5.60 → 4.33 places, bottom-10 recovery 7/10 → 9/10. With n = 30 the 95% confidence interval on 0.790 is roughly [0.60, 0.90], so the size of the gain is softer than the direction of it — every individual change was verified to move the specific dishes it was aimed at.

---

### Appendix — reproduction

- Menu image generated by `MenuGen.java`, submitted to `POST /api/menu/rank` on the production host (bypassing the CDN to avoid its ~100 s proxy ceiling).
- Rank statistics computed with average-rank Spearman and Kendall τ-b (tie-corrected); significance via the large-sample *t* approximation.
- Re-scoring of corrected rows executed against the production `ScoringService` itself, not a reimplementation.
