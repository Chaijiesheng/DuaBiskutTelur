package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WeightHistoryResponse;
import com.duabiskuttelur.model.WeightWeekPoint;
import com.duabiskuttelur.persistence.WeightEntity;
import com.duabiskuttelur.persistence.WeightRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Covers the week-bucketing/averaging logic — the fiddly date math this feature depends on. */
class WeightServiceTest {

    private static WeightEntity entry(Long userId, double kg, Instant loggedAt) {
        WeightEntity e = new WeightEntity();
        e.setUserId(userId);
        e.setWeightKg(kg);
        e.setLoggedAt(loggedAt);
        return e;
    }

    @Test
    void averagesMultipleEntriesInTheSameWeek() {
        // Anchored to last week's Monday (rather than "now minus N days") so the
        // two entries always land in the same ISO-week bucket, and are always in
        // the past relative to history()'s "now" upper bound, regardless of which
        // day of the week the test happens to run on.
        ZoneId zone = ZoneId.systemDefault();
        LocalDate lastMonday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        Instant mondayMorning = lastMonday.atTime(10, 0).atZone(zone).toInstant();
        Instant wednesdayMorning = lastMonday.plusDays(2).atTime(10, 0).atZone(zone).toInstant();
        FakeRepository repo = new FakeRepository();
        repo.entries.add(entry(1L, 70.0, mondayMorning));
        repo.entries.add(entry(1L, 69.0, wednesdayMorning));

        WeightHistoryResponse history = new WeightService(repo).history(1L);

        assertEquals(1, history.weeks().size());
        WeightWeekPoint week = history.weeks().get(0);
        assertEquals(69.5, week.avgWeightKg());
        assertEquals(2, week.entryCount());
    }

    @Test
    void separatesEntriesAcrossDifferentWeeks() {
        Instant now = Instant.now();
        FakeRepository repo = new FakeRepository();
        repo.entries.add(entry(1L, 70.0, now.minus(20, ChronoUnit.DAYS)));
        repo.entries.add(entry(1L, 68.0, now.minus(1, ChronoUnit.DAYS)));

        WeightHistoryResponse history = new WeightService(repo).history(1L);

        assertEquals(2, history.weeks().size(), "entries three weeks apart should land in separate buckets");
        // oldest first
        assertEquals(70.0, history.weeks().get(0).avgWeightKg());
        assertEquals(68.0, history.weeks().get(1).avgWeightKg());
    }

    @Test
    void logWeightRejectsOutOfRangeValues() {
        WeightService service = new WeightService(new FakeRepository());
        assertThrows(ResponseStatusException.class, () -> service.logWeight(1L, 5.0));
        assertThrows(ResponseStatusException.class, () -> service.logWeight(1L, 500.0));
        assertThrows(ResponseStatusException.class, () -> service.logWeight(1L, null));
    }

    /** In-memory fake so we don't need a real Spring context. */
    private static class FakeRepository implements WeightRepository {
        final List<WeightEntity> entries = new ArrayList<>();

        @Override
        public List<WeightEntity> findByUserIdAndLoggedAtBetween(Long userId, Instant start, Instant end) {
            return entries.stream()
                    .filter(e -> e.getUserId().equals(userId))
                    .filter(e -> !e.getLoggedAt().isBefore(start) && !e.getLoggedAt().isAfter(end))
                    .toList();
        }

        @Override public <S extends WeightEntity> S save(S entity) { entries.add(entity); return entity; }
        @Override public <S extends WeightEntity> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public Optional<WeightEntity> findById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public List<WeightEntity> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<WeightEntity> findAllById(Iterable<Long> longs) { throw new UnsupportedOperationException(); }
        @Override public long count() { throw new UnsupportedOperationException(); }
        @Override public void deleteById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public void delete(WeightEntity entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> longs) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends WeightEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public void flush() { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity> S saveAndFlush(S entity) { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<WeightEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public WeightEntity getOne(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public WeightEntity getById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public WeightEntity getReferenceById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends WeightEntity, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public List<WeightEntity> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<WeightEntity> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
    }
}
