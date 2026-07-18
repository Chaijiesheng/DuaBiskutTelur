package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WaterTodayResponse;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.WaterEntity;
import com.duabiskuttelur.persistence.WaterRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A running total per user per calendar day, not an append-only log — there's
 * nothing to trend or bucket here, just "how much today, against what target."
 */
@Service
public class WaterService {

    private static final int DEFAULT_TARGET_ML = 2000;
    private static final int MIN_ML = 0;
    private static final int MAX_ML = 8000;
    private static final int MIN_TARGET_ML = 500;
    private static final int MAX_TARGET_ML = 5000;

    private final WaterRepository repository;

    public WaterService(WaterRepository repository) {
        this.repository = repository;
    }

    public WaterTodayResponse today(UserEntity user) {
        int totalMl = repository.findByUserIdAndDate(user.getId(), today())
                .map(WaterEntity::getTotalMl)
                .orElse(0);
        return new WaterTodayResponse(totalMl, targetFor(user));
    }

    @Transactional
    public WaterTodayResponse adjust(UserEntity user, int deltaMl) {
        WaterEntity entity = repository.findByUserIdAndDate(user.getId(), today())
                .orElseGet(() -> {
                    WaterEntity fresh = new WaterEntity();
                    fresh.setUserId(user.getId());
                    fresh.setDate(today());
                    fresh.setTotalMl(0);
                    return fresh;
                });
        entity.setTotalMl(Math.max(MIN_ML, Math.min(MAX_ML, entity.getTotalMl() + deltaMl)));
        repository.save(entity);
        return new WaterTodayResponse(entity.getTotalMl(), targetFor(user));
    }

    @Transactional
    public WaterTodayResponse reset(UserEntity user) {
        return repository.findByUserIdAndDate(user.getId(), today())
                .map(entity -> {
                    entity.setTotalMl(0);
                    repository.save(entity);
                    return new WaterTodayResponse(0, targetFor(user));
                })
                .orElseGet(() -> new WaterTodayResponse(0, targetFor(user)));
    }

    public static void validateTarget(int targetMl) {
        if (targetMl < MIN_TARGET_ML || targetMl > MAX_TARGET_ML) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Water target must be between %d and %dml".formatted(MIN_TARGET_ML, MAX_TARGET_ML));
        }
    }

    private int targetFor(UserEntity user) {
        return user.getWaterTargetMl() != null ? user.getWaterTargetMl() : DEFAULT_TARGET_ML;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }
}
