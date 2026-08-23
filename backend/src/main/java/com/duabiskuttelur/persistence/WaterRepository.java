package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WaterRepository extends JpaRepository<WaterEntity, Long> {

    /** Safe to return a single Optional: uk_water_entry_user_date (V6) makes duplicates impossible. */
    Optional<WaterEntity> findByUserIdAndDate(Long userId, LocalDate date);

    /**
     * Adds a delta to one day's running total in a single statement, clamped in
     * SQL. Read-modify-write in Java lost concurrent taps — two requests reading
     * the same total and each saving their own sum meant one tap silently
     * vanished — and its "no row yet, insert one" branch could double-insert.
     * Doing the arithmetic in the UPDATE removes that window entirely.
     *
     * @return 1 when the day's row was updated, 0 when no row exists yet
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            update WaterEntity w
               set w.totalMl = case
                   when w.totalMl + :deltaMl < :minMl then :minMl
                   when w.totalMl + :deltaMl > :maxMl then :maxMl
                   else w.totalMl + :deltaMl
               end
             where w.userId = :userId and w.date = :date
            """)
    int adjustTotal(@Param("userId") Long userId,
                    @Param("date") LocalDate date,
                    @Param("deltaMl") int deltaMl,
                    @Param("minMl") int minMl,
                    @Param("maxMl") int maxMl);

    /** Every day the user has logged, for the data export. */
    List<WaterEntity> findByUserIdOrderByDateDesc(Long userId);

    /**
     * Water inside a reporting window. Bounded by date rather than reading the
     * user's whole history, and served by the UNIQUE(user_id, date) index that
     * V6 already created -- equality then a range, the same shape as the meal
     * query above.
     */
    List<WaterEntity> findByUserIdAndDateBetween(Long userId, LocalDate from, LocalDate to);

    /**
     * Bulk delete for account deletion. A derived {@code deleteByUserId} would
     * load every row into the persistence context and remove them one at a
     * time; this is one statement, and the row count gives the deletion
     * something to log.
     */
    @Modifying
    @Transactional
    @Query("delete from WaterEntity e where e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
