package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface WorkoutSessionExerciseRepository extends JpaRepository<WorkoutSessionExerciseEntity, Long> {

    List<WorkoutSessionExerciseEntity> findBySessionIdOrderByPositionAsc(Long sessionId);

    Optional<WorkoutSessionExerciseEntity> findBySessionIdAndPosition(Long sessionId, Integer position);

    /**
     * Every prescribed exercise across a set of sessions, for the "getting
     * stronger" list on the Analysis tab.
     *
     * <p>One query rather than one per session: that list spans a month of
     * training, so the per-session version would be thirty round trips to build
     * three rows.
     */
    List<WorkoutSessionExerciseEntity> findBySessionIdIn(List<Long> sessionIds);

    /**
     * Account deletion. This table carries no {@code user_id}, so erasure has to
     * reach it through the sessions being deleted — see
     * {@code AccountDataService.deleteAccount}, which is the only thing standing
     * between a closed account and orphaned rows.
     */
    @Modifying
    @Transactional
    @Query("delete from WorkoutSessionExerciseEntity e where e.sessionId in :sessionIds")
    int deleteBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);
}
