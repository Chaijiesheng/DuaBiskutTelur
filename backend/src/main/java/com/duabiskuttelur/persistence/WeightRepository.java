package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WeightRepository extends JpaRepository<WeightEntity, Long> {

    List<WeightEntity> findByUserIdAndLoggedAtBetween(Long userId, Instant start, Instant end);

    /** Every weight the user has logged, for the data export. */
    List<WeightEntity> findByUserIdOrderByLoggedAtDesc(Long userId);

    /**
     * The most recent weigh-in. The workout dashboard shows one number, and
     * loading a user's whole weight history to take the first row of it would be
     * a table scan for a single tile.
     */
    Optional<WeightEntity> findFirstByUserIdOrderByLoggedAtDesc(Long userId);

    /**
     * Bulk delete for account deletion. A derived {@code deleteByUserId} would
     * load every row into the persistence context and remove them one at a
     * time; this is one statement, and the row count gives the deletion
     * something to log.
     */
    @Modifying
    @Transactional
    @Query("delete from WeightEntity e where e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
