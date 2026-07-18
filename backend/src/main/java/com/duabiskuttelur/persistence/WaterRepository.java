package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface WaterRepository extends JpaRepository<WaterEntity, Long> {

    Optional<WaterEntity> findByUserIdAndDate(Long userId, LocalDate date);
}
