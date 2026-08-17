package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface WorkoutSetLogRepository extends JpaRepository<WorkoutSetLogEntity, Long> {

    List<WorkoutSetLogEntity> findBySessionId(Long sessionId);

    boolean existsBySessionIdAndExercisePositionAndSetIndex(Long sessionId, Integer position, Integer setIndex);

    @Modifying
    @Transactional
    @Query("delete from WorkoutSetLogEntity e where e.sessionId = :sessionId "
            + "and e.exercisePosition = :position and e.setIndex = :setIndex")
    int deleteOne(@Param("sessionId") Long sessionId,
                  @Param("position") Integer position,
                  @Param("setIndex") Integer setIndex);

    /** Clears the logs when a slot is swapped — see {@code WorkoutService.replaceExercise}. */
    @Modifying
    @Transactional
    @Query("delete from WorkoutSetLogEntity e where e.sessionId = :sessionId and e.exercisePosition = :position")
    int deleteBySessionIdAndExercisePosition(@Param("sessionId") Long sessionId,
                                             @Param("position") Integer position);

    /** Account deletion, reached through the user's sessions — this table has no user_id. */
    @Modifying
    @Transactional
    @Query("delete from WorkoutSetLogEntity e where e.sessionId in :sessionIds")
    int deleteBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);
}
