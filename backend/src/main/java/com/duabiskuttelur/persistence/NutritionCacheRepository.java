package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NutritionCacheRepository extends JpaRepository<NutritionCacheEntity, Long> {

    Optional<NutritionCacheEntity> findByCanonicalName(String canonicalName);
}
