package com.duabiskuttelur.persistence;

import com.duabiskuttelur.model.DailyMealFact;
import com.duabiskuttelur.model.HistoryEntry;
import com.duabiskuttelur.model.RecentMealPoint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MealAnalysisRepository extends JpaRepository<MealAnalysisEntity, Long> {

    /**
     * The history list, without {@code result_json}. The list shows a thumbnail,
     * so that CLOB has to come along; the ~3 KB of stored analysis per row does
     * not, and at the fifty-row cap that was ~150 KB read and discarded on every
     * History tab open. {@code Pageable} rather than {@code findTop50By…}
     * because JPQL has no LIMIT — the cap is applied by the caller.
     */
    @Query("""
            select new com.duabiskuttelur.model.HistoryEntry(e.id, e.createdAt, e.score, e.grade,
                                                             e.calories, e.summary, e.thumbnail, e.source)
              from MealAnalysisEntity e
             where e.userId = :userId
             order by e.createdAt desc
            """)
    List<HistoryEntry> findHistoryEntries(@Param("userId") Long userId, Pageable page);

    Optional<MealAnalysisEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * Today's meals reduced to the three numbers the dashboard sums, touching
     * neither CLOB. The hottest read in the app: every dashboard load, and every
     * analysis, since goal-aware feedback needs the remaining budget.
     */
    @Query("""
            select new com.duabiskuttelur.model.DailyMealFact(e.id, e.score, e.calories, e.protein)
              from MealAnalysisEntity e
             where e.userId = :userId and e.createdAt between :start and :end
            """)
    List<DailyMealFact> findDailyFacts(@Param("userId") Long userId,
                                       @Param("start") Instant start,
                                       @Param("end") Instant end);

    /**
     * {@code result_json} for named rows only — the fallback for meals written
     * before V2 added the denormalized {@code protein} column. Keyed by id
     * rather than by "protein is null" so it runs only when such a row is
     * actually in the window, instead of unconditionally on every dashboard
     * load. (The achievements pair below still pays that unconditional cost;
     * see the handover.)
     */
    @Query("select e.resultJson from MealAnalysisEntity e where e.id in :ids")
    List<String> findResultJsonByIds(@Param("ids") List<Long> ids);

    List<MealAnalysisEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Achievement inputs for rows that carry the denormalized columns, reading
     * only those columns — the entity's two CLOBs are never touched.
     */
    @Query("""
            select e.createdAt as createdAt, e.grade as grade, e.summary as summary,
                   e.vegetableCount as vegetableCount, e.hasFruit as hasFruit,
                   e.beverageOnly as beverageOnly, e.coffeeOnly as coffeeOnly
              from MealAnalysisEntity e
             where e.userId = :userId and e.vegetableCount is not null
             order by e.createdAt desc
            """)
    List<AchievementFacts> findAchievementFacts(@Param("userId") Long userId);

    /**
     * The pre-V2 remainder, where the same facts have to be parsed back out of
     * {@code result_json}. Separate query so that CLOB is read only for the rows
     * that have no alternative, instead of for every row on every request.
     */
    @Query("""
            select e.createdAt as createdAt, e.grade as grade, e.summary as summary,
                   e.resultJson as resultJson
              from MealAnalysisEntity e
             where e.userId = :userId and e.vegetableCount is null
             order by e.createdAt desc
            """)
    List<LegacyAchievementFacts> findLegacyAchievementFacts(@Param("userId") Long userId);

    /**
     * Bulk delete for account deletion. A derived {@code deleteByUserId} would
     * load every row into the persistence context and remove them one at a
     * time; this is one statement, and the row count gives the deletion
     * something to log.
     */
    @Modifying
    @Transactional
    @Query("delete from MealAnalysisEntity e where e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /**
     * Every meal in a time window, uncapped but only three columns wide — the
     * inputs to the weekly trend. Uncapped is the whole point: the fifty-row
     * limit on the history list silently truncated those numbers. Constructor
     * expression rather than an entity read so neither CLOB comes along.
     */
    @Query("""
            select new com.duabiskuttelur.model.RecentMealPoint(e.id, e.createdAt, e.calories)
              from MealAnalysisEntity e
             where e.userId = :userId and e.createdAt >= :from
             order by e.createdAt desc
            """)
    List<RecentMealPoint> findPointsSince(@Param("userId") Long userId, @Param("from") Instant from);
}
