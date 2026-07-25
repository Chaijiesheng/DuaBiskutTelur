package com.duabiskuttelur.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuScanRepository extends JpaRepository<MenuScanEntity, Long> {

    List<MenuScanEntity> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<MenuScanEntity> findByIdAndUserId(Long id, Long userId);
}
