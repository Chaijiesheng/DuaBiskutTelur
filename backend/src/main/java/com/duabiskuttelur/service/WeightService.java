package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WeightHistoryResponse;
import com.duabiskuttelur.model.WeightWeekPoint;
import com.duabiskuttelur.persistence.WeightEntity;
import com.duabiskuttelur.persistence.WeightRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Weigh-ins are logged whenever the user wants — no fixed cadence — and
 * averaged per ISO week (Monday start) on read, so the trend is readable
 * without being noisy from day-to-day water-weight swings.
 */
@Service
public class WeightService {

    private static final int WEEKS_WINDOW = 8;
    private static final double MIN_WEIGHT_KG = 30;
    private static final double MAX_WEIGHT_KG = 250;

    private final WeightRepository repository;

    public WeightService(WeightRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void logWeight(Long userId, Double weightKg) {
        if (weightKg == null || weightKg < MIN_WEIGHT_KG || weightKg > MAX_WEIGHT_KG) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Weight must be between %.0f and %.0f kg".formatted(MIN_WEIGHT_KG, MAX_WEIGHT_KG));
        }
        WeightEntity entity = new WeightEntity();
        entity.setUserId(userId);
        entity.setWeightKg(weightKg);
        entity.setLoggedAt(Instant.now());
        repository.save(entity);
    }

    public WeightHistoryResponse history(Long userId) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate thisWeekMonday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate windowStart = thisWeekMonday.minusWeeks(WEEKS_WINDOW - 1L);
        Instant start = windowStart.atStartOfDay(zone).toInstant();
        Instant end = Instant.now();

        List<WeightEntity> entries = repository.findByUserIdAndLoggedAtBetween(userId, start, end);

        Map<LocalDate, List<Double>> byWeek = new TreeMap<>();
        for (WeightEntity entry : entries) {
            LocalDate weekStart = entry.getLoggedAt().atZone(zone).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            byWeek.computeIfAbsent(weekStart, k -> new ArrayList<>()).add(entry.getWeightKg());
        }

        List<WeightWeekPoint> weeks = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Double>> e : byWeek.entrySet()) {
            double avg = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            weeks.add(new WeightWeekPoint(e.getKey().toString(), round1(avg), e.getValue().size()));
        }
        return new WeightHistoryResponse(weeks);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
