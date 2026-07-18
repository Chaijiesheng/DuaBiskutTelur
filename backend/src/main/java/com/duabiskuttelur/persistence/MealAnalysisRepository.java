package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MealAnalysisRepository extends JpaRepository<MealAnalysisEntity, Long> {

    List<MealAnalysisEntity> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<MealAnalysisEntity> findByIdAndUserId(Long id, Long userId);

    List<MealAnalysisEntity> findByUserIdAndCreatedAtBetween(Long userId, Instant start, Instant end);

    List<MealAnalysisEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
