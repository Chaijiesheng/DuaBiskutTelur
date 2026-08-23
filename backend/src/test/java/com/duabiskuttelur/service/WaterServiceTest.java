package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WaterTodayResponse;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.WaterEntity;
import com.duabiskuttelur.persistence.WaterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Covers the daily-upsert, clamping, and target-default logic. */
class WaterServiceTest {

    private static UserEntity user(Long id, Integer waterTargetMl) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setWaterTargetMl(waterTargetMl);
        return u;
    }

    @Test
    void adjustAccumulatesWithinTheSameDay() {
        WaterService service = new WaterService(new FakeRepository());
        UserEntity u = user(1L, null);

        service.adjust(u, 250);
        WaterTodayResponse result = service.adjust(u, 500);

        assertEquals(750, result.totalMl());
        assertEquals(2000, result.targetMl(), "no target set -> falls back to the 2000ml default");
    }

    @Test
    void adjustClampsAtZeroAndAtTheUpperCeiling() {
        WaterService service = new WaterService(new FakeRepository());
        UserEntity u = user(1L, null);

        WaterTodayResponse belowZero = service.adjust(u, -500);
        assertEquals(0, belowZero.totalMl(), "shouldn't go negative from a stray correction");

        service.adjust(u, 8000);
        WaterTodayResponse aboveCeiling = service.adjust(u, 5000);
        assertEquals(8000, aboveCeiling.totalMl(), "shouldn't exceed the 8000ml sanity ceiling");
    }

    @Test
    void resetZeroesTodayWithoutTouchingTheTarget() {
        WaterService service = new WaterService(new FakeRepository());
        UserEntity u = user(1L, 3000);

        service.adjust(u, 1500);
        WaterTodayResponse result = service.reset(u);

        assertEquals(0, result.totalMl());
        assertEquals(3000, result.targetMl());
    }

    @Test
    void todayUsesTheUsersCustomTargetWhenSet() {
        WaterService service = new WaterService(new FakeRepository());
        UserEntity u = user(1L, 3500);

        assertEquals(3500, service.today(u).targetMl());
    }

    @Test
    void validateTargetRejectsOutOfRangeValues() {
        assertThrows(ResponseStatusException.class, () -> WaterService.validateTarget(100));
        assertThrows(ResponseStatusException.class, () -> WaterService.validateTarget(10_000));
    }

    /**
     * Two taps racing on the first drink of the day both used to find no row and
     * both insert one, after which findByUserIdAndDate matched two rows for an
     * Optional and every water endpoint threw for the rest of the day. The
     * unique constraint (V6) now rejects the losing insert; this asserts the
     * loser recovers by applying its delta to the winner's row instead of
     * dropping the tap or blowing up.
     */
    @Test
    void adjustRecoversWhenItLosesTheFirstTapInsertRace() {
        FakeRepository repository = new FakeRepository();
        WaterService service = new WaterService(repository);
        UserEntity u = user(1L, null);

        // The competing request lands its own 250ml row in the window between
        // our adjustTotal finding nothing and our insert reaching the database.
        repository.beforeInsert = () -> repository.insertDirectly(1L, 250);

        WaterTodayResponse result = service.adjust(u, 500);

        assertEquals(750, result.totalMl(), "the losing tap's delta must land on the winner's row");
        assertEquals(1, repository.entries.size(), "the constraint must leave exactly one row for the day");
    }

    /**
     * In-memory fake so we don't need a real Spring context. Enforces
     * uk_water_entry_user_date the way the database does, so the service's
     * race-recovery path is exercised rather than assumed.
     */
    private static class FakeRepository implements WaterRepository {

        /** Unused here: this fake serves the daily water tracker only. */
        @Override
        public java.util.List<com.duabiskuttelur.persistence.WaterEntity> findByUserIdAndDateBetween(
                Long userId, java.time.LocalDate from, java.time.LocalDate to) {
            return java.util.List.of();
        }

        final List<WaterEntity> entries = new ArrayList<>();
        /** Hook for simulating a competing request inserting first. */
        Runnable beforeInsert = () -> { };

        @Override
        public Optional<WaterEntity> findByUserIdAndDate(Long userId, LocalDate date) {
            return entries.stream()
                    .filter(e -> e.getUserId().equals(userId) && date.equals(e.getDate()))
                    .findFirst();
        }

        @Override
        public int adjustTotal(Long userId, LocalDate date, int deltaMl, int minMl, int maxMl) {
            return findByUserIdAndDate(userId, date)
                    .map(e -> {
                        e.setTotalMl(Math.max(minMl, Math.min(maxMl, e.getTotalMl() + deltaMl)));
                        return 1;
                    })
                    .orElse(0);
        }

        void insertDirectly(Long userId, int totalMl) {
            WaterEntity e = new WaterEntity();
            e.setUserId(userId);
            e.setDate(LocalDate.now(ZoneId.systemDefault()));
            e.setTotalMl(totalMl);
            entries.add(e);
        }

        @Override public <S extends WaterEntity> S saveAndFlush(S entity) {
            beforeInsert.run();
            beforeInsert = () -> { };
            boolean duplicate = entries.stream().anyMatch(e -> e != entity
                    && e.getUserId().equals(entity.getUserId())
                    && entity.getDate().equals(e.getDate()));
            if (duplicate) {
                throw new DataIntegrityViolationException("uk_water_entry_user_date");
            }
            return save(entity);
        }

        @Override public <S extends WaterEntity> S save(S entity) {
            entries.removeIf(e -> e == entity);
            entries.add(entity);
            return entity;
        }
        @Override public int deleteByUserId(Long userId) { throw new UnsupportedOperationException(); }
        @Override public List<WaterEntity> findByUserIdOrderByDateDesc(Long userId) { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public Optional<WaterEntity> findById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public List<WaterEntity> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<WaterEntity> findAllById(Iterable<Long> longs) { throw new UnsupportedOperationException(); }
        @Override public long count() { throw new UnsupportedOperationException(); }
        @Override public void deleteById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public void delete(WaterEntity entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> longs) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends WaterEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public void flush() { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<WaterEntity> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public WaterEntity getOne(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public WaterEntity getById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public WaterEntity getReferenceById(Long aLong) { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends WaterEntity, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public List<WaterEntity> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<WaterEntity> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
    }
}
