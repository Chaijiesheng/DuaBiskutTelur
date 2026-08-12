package com.duabiskuttelur.persistence;

import com.duabiskuttelur.model.MenuHistoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface MenuScanRepository extends JpaRepository<MenuScanEntity, Long> {

    /**
     * The menu history list, without {@code result_json} — same trade as
     * {@code MealAnalysisRepository.findHistoryEntries}: the list renders a
     * thumbnail so that CLOB is needed, the stored ranking is not, and it was
     * being read and thrown away fifty rows at a time.
     */
    @Query("""
            select new com.duabiskuttelur.model.MenuHistoryEntry(e.id, e.createdAt, e.dishCount,
                                                                 e.truncated, e.summary, e.thumbnail)
              from MenuScanEntity e
             where e.userId = :userId
             order by e.createdAt desc
            """)
    List<MenuHistoryEntry> findHistoryEntries(@Param("userId") Long userId, Pageable page);

    Optional<MenuScanEntity> findByIdAndUserId(Long id, Long userId);

    /** Uncapped, unlike the 50-row history list — a data export has to be complete to be one. */
    List<MenuScanEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Bulk delete for account deletion. A derived {@code deleteByUserId} would
     * load every row into the persistence context and remove them one at a
     * time; this is one statement, and the row count gives the deletion
     * something to log.
     */
    @Modifying
    @Transactional
    @Query("delete from MenuScanEntity e where e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
