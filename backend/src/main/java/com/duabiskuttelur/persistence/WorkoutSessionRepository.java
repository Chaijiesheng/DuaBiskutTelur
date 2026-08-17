package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkoutSessionRepository extends JpaRepository<WorkoutSessionEntity, Long> {

    Optional<WorkoutSessionEntity> findByUserIdAndSessionDate(Long userId, LocalDate sessionDate);

    /** The dashboard's week strip, and the window the coach reads to say something true. */
    List<WorkoutSessionEntity> findByUserIdAndSessionDateBetweenOrderBySessionDateAsc(
            Long userId, LocalDate start, LocalDate end);

    /** Session ids for one user, so the child tables can be cleared on account deletion. */
    @Query("select e.id from WorkoutSessionEntity e where e.userId = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("delete from WorkoutSessionEntity e where e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
