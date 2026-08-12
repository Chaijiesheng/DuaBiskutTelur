-- The portion bracket (gramsLow/gramsHigh) and the cooking method now have to be
-- pinned alongside the nutrients. Both feed what the user sees -- the bracket
-- becomes the displayed calorie range, the method decides the fried penalty -- so
-- leaving either un-pinned would reintroduce exactly the per-scan variance the
-- nutrition cache exists to remove.
--
-- Existing rows: gramsLow/gramsHigh default to grams, which collapses the range
-- onto the point estimate. That is the honest reading, and FoodItem treats a
-- collapsed range as "no range to show" rather than as a zero-width claim.
alter table nutrition_cache add column grams_low double precision not null default 0;
alter table nutrition_cache add column grams_high double precision not null default 0;
alter table nutrition_cache add column cooking_method varchar(32);

update nutrition_cache set grams_low = grams, grams_high = grams;

-- Carry the old boolean forward. "deep-fried" is the closest honest reading of a
-- flag whose prompt was "true if deep-fried or visibly oily" -- it keeps the full
-- penalty these rows were already scored with, rather than quietly reducing it.
update nutrition_cache set cooking_method = 'deep-fried' where fried = true;
