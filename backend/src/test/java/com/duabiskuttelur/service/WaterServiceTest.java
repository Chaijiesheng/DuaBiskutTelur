package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WaterTodayResponse;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.WaterEntity;
import com.duabiskuttelur.persistence.WaterRepository;
import org.junit.jupiter.api.Test;
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

    /** In-memory fake so we don't need a real Spring context. */
    private static class FakeRepository implements WaterRepository {
        final List<WaterEntity> entries = new ArrayList<>();

        @Override
        public Optional<WaterEntity> findByUserIdAndDate(Long userId, LocalDate date) {
            return entries.stream()
                    .filter(e -> e.getUserId().equals(userId) && date.equals(e.getDate()))
                    .findFirst();
        }

        @Override public <S extends WaterEntity> S save(S entity) {
            entries.removeIf(e -> e == entity);
            entries.add(entity);
            return entity;
        }
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
        @Override public <S extends WaterEntity> S saveAndFlush(S entity) { throw new UnsupportedOperationException(); }
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
