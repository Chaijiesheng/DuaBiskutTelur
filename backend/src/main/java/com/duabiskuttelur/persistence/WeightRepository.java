package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface WeightRepository extends JpaRepository<WeightEntity, Long> {

    List<WeightEntity> findByUserIdAndLoggedAtBetween(Long userId, Instant start, Instant end);

    /** Every weight the user has logged, for the data export. */
    List<WeightEntity> findByUserIdOrderByLoggedAtDesc(Long userId);

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
