package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface WorkoutProfileRepository extends JpaRepository<WorkoutProfileEntity, Long> {

    Optional<WorkoutProfileEntity> findByUserId(Long userId);

    /** Account deletion. One statement, and the count gives the erasure something to log. */
    @Modifying
    @Transactional
    @Query("delete from WorkoutProfileEntity e where e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
