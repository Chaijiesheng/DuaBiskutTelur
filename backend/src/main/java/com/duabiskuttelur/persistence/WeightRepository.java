package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface WeightRepository extends JpaRepository<WeightEntity, Long> {

    List<WeightEntity> findByUserIdAndLoggedAtBetween(Long userId, Instant start, Instant end);
}
