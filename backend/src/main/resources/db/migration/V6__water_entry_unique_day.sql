-- One row per user per calendar day was always the intent (see WaterEntity), but
-- nothing enforced it. WaterService read the day's row, added the delta, and
-- saved it back, so two concurrent first-taps of a day both saw "no row yet" and
-- both inserted. From then on findByUserIdAndDate matched two rows for an
-- Optional return type, and every water endpoint threw
-- IncorrectResultSizeDataAccessException — WaterController has no exception
-- handler, so the whole water feature returned 500 for the rest of that day and
-- only manual SQL could clear it.
--
-- The constraint below makes that state unrepresentable. WaterService now
-- applies the delta with a single atomic UPDATE and only inserts when no row
-- exists yet, replaying its delta onto the winner's row if it loses that insert
-- race. The constraint's backing index also covers the findByUserIdAndDate
-- lookup, which was a full table scan before.

-- Collapse any duplicates already in the table onto the lowest id of each group,
-- keeping the largest total: the duplicate rows are rival running totals of the
-- same day's taps, so the largest is the closest surviving record of what the
-- user actually logged. Under-counting a day beats resurrecting a 500.
UPDATE water_entry
   SET total_ml = (SELECT MAX(dup.total_ml)
                     FROM water_entry dup
                    WHERE dup.user_id = water_entry.user_id
                      AND dup.date = water_entry.date)
 WHERE id IN (SELECT MIN(keep.id)
                FROM water_entry keep
               GROUP BY keep.user_id, keep.date
              HAVING COUNT(*) > 1);

DELETE FROM water_entry
 WHERE id NOT IN (SELECT MIN(keep.id)
                    FROM water_entry keep
                   GROUP BY keep.user_id, keep.date);

ALTER TABLE water_entry
    ADD CONSTRAINT uk_water_entry_user_date UNIQUE (user_id, date);
