-- Every per-user read in the app was a full table scan: nothing outside the
-- primary keys and two unique constraints was indexed, and meal_analysis /
-- menu_scan carry two CLOB columns each, so a scan drags kilobytes per row
-- through the buffer just to find one user's rows. This is the whole hot path
-- — the History tab, the dashboard (which also runs on every analysis, to work
-- out the remaining budget for goal-aware feedback), the achievements catalog,
-- and the weight trend.
--
-- water_entry needs nothing here: V6's UNIQUE(user_id, date) already creates
-- the index its only query shape needs.
--
-- All ascending on purpose, despite the ORDER BY ... DESC these serve. The
-- direction only matters for mixed-direction sorts; with user_id pinned by
-- equality and a single sort column after it, the engine scans this index
-- backwards for the same cost, and an ascending definition stays portable to
-- the Postgres exit path.

-- findTop50ByUserIdOrderByCreatedAtDesc (history list),
-- findByUserIdOrderByCreatedAtDesc (achievements),
-- findByUserIdAndCreatedAtBetween (today's dashboard) — one index serves all
-- three: equality on user_id, then either a range or an ordering on created_at.
CREATE INDEX idx_meal_analysis_user_created ON meal_analysis (user_id, created_at);

-- findTop50ByUserIdOrderByCreatedAtDesc on the menu-scan history list.
CREATE INDEX idx_menu_scan_user_created ON menu_scan (user_id, created_at);

-- findByUserIdAndLoggedAtBetween — the trailing 8-week weight window.
CREATE INDEX idx_weight_entry_user_logged ON weight_entry (user_id, logged_at);
