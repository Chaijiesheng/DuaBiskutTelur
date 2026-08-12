package com.duabiskuttelur.service;

import com.duabiskuttelur.model.WaterTodayResponse;
import com.duabiskuttelur.persistence.UserEntity;
import com.duabiskuttelur.persistence.UserRepository;
import com.duabiskuttelur.persistence.WaterEntity;
import com.duabiskuttelur.persistence.WaterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B2/DB2's recovery path, against a real database instead of a fake.
 *
 * <p>{@code WaterServiceTest} covers the same path with a hand-written
 * repository that enforces {@code uk_water_entry_user_date} in Java and throws
 * {@link DataIntegrityViolationException} because the test author decided it
 * should. That proves the service reacts correctly to that exception; it proves
 * nothing about whether the real stack produces it. Hibernate could surface a
 * constraint breach as a {@code JpaSystemException}, or Spring could leave it
 * untranslated — and then {@code WaterService.adjust}'s catch clause would miss,
 * {@code WaterController} has no exception handler, and a user who double-taps
 * their first drink of the day gets the 500 this whole fix exists to prevent.
 * The fake cannot fail that way, so it cannot detect it.
 *
 * <p>What is faked here is only the <em>interleaving</em> — the constraint, the
 * SQL, the exception and its translation are all real.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:water-race-test;DB_CLOSE_DELAY=-1"
})
class WaterRaceIntegrationTest {

    /** Enough threads to interleave, few enough to stay well inside the pool. */
    private static final int CONCURRENT_TAPS = 8;
    private static final int TAP_ML = 250;

    /**
     * Taps per thread in the concurrency test. One round each is not enough to
     * detect a lost update — measured: swapping {@code adjustTotal} for the
     * pre-fix read-modify-write still passed 8 single taps, because the
     * read-then-write window is narrow and rarely straddles another thread's
     * write. Thousands of rounds make hitting it a near-certainty instead of a
     * coin flip. {@code TAP_ML} drops to 1 to stay under the 8000ml ceiling,
     * which would otherwise clamp the total and hide exactly the shortfall
     * being looked for.
     */
    private static final int TAPS_PER_THREAD = 500;
    private static final int SMALL_TAP_ML = 1;

    @Autowired private WaterRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private WaterService waterService;

    private UserEntity user;

    @BeforeEach
    void freshDay() {
        repository.deleteAll();
        userRepository.deleteAll();
        UserEntity fresh = new UserEntity();
        fresh.setGoogleSub("water-race-" + System.nanoTime());
        fresh.setCreatedAt(java.time.Instant.now());
        user = userRepository.save(fresh);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.systemDefault());
    }

    private WaterEntity row(Long userId, int totalMl) {
        WaterEntity entity = new WaterEntity();
        entity.setUserId(userId);
        entity.setDate(today());
        entity.setTotalMl(totalMl);
        return entity;
    }

    /**
     * The fact {@code WaterService.adjust}'s catch clause rests on. If the real
     * stack ever stops translating this to a DataIntegrityViolationException —
     * a Hibernate or Spring upgrade is enough — the recovery silently stops
     * recovering, and nothing else in the suite would notice.
     */
    @Test
    void theConstraintSurfacesAsTheExceptionTheRecoveryPathCatches() {
        repository.saveAndFlush(row(user.getId(), TAP_ML));

        Exception thrown = assertThrows(Exception.class,
                () -> repository.saveAndFlush(row(user.getId(), TAP_ML)),
                "uk_water_entry_user_date must reject a second row for the same user and day");

        assertInstanceOf(DataIntegrityViolationException.class, thrown,
                "WaterService.adjust catches DataIntegrityViolationException specifically; a different "
                        + "type here means the catch misses and the tap 500s");
    }

    /**
     * The losing first tap, forced deterministically: the competing row is
     * inserted inside the window between {@code adjustTotal} finding nothing and
     * this request's insert reaching the database.
     */
    @Test
    void aLostFirstTapRaceLandsItsDeltaOnTheWinnersRow() {
        WaterService racing = new WaterService(repositoryThatInsertsACompetingRowFirst(400));

        WaterTodayResponse result = racing.adjust(user, TAP_ML);

        assertEquals(400 + TAP_ML, result.totalMl(),
                "the losing tap's delta must land on the winner's row, not be dropped");
        assertEquals(1, repository.findAll().size(),
                "the constraint must leave exactly one row for the day");
    }

    /**
     * The original report was "users double-tap". Nothing in the suite ran the
     * real {@code adjustTotal} concurrently, so its atomicity was assumed: a
     * read-modify-write would pass every single-threaded test and still lose
     * taps here.
     */
    @Test
    void concurrentTapsAllLandAndLeaveExactlyOneRow() throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(CONCURRENT_TAPS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> taps = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_TAPS; i++) {
            Thread tap = new Thread(() -> {
                try {
                    startTogether.await();
                    for (int n = 0; n < TAPS_PER_THREAD; n++) {
                        waterService.adjust(user, SMALL_TAP_ML);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            taps.add(tap);
            tap.start();
        }
        for (Thread tap : taps) {
            tap.join();
        }

        assertNull(failure.get(), "no tap may fail — a 500 here is the bug B2 reported: " + failure.get());
        assertEquals(1, repository.findAll().size(),
                "however the inserts interleaved, the day must end with one row");
        assertEquals(CONCURRENT_TAPS * TAPS_PER_THREAD * SMALL_TAP_ML, repository.findAll().get(0).getTotalMl(),
                "every tap must be counted — a shortfall means adjustTotal is not atomic and taps are "
                        + "being lost to a read-modify-write window");
    }

    /**
     * Delegates everything to the real repository, but inserts a competing row
     * just before this request's own insert reaches the database. Only the
     * timing is staged; the constraint that rejects the second insert, and the
     * exception it raises, are the real ones.
     */
    private WaterRepository repositoryThatInsertsACompetingRowFirst(int competingTotalMl) {
        boolean[] alreadyInserted = {false};
        return (WaterRepository) Proxy.newProxyInstance(
                WaterRepository.class.getClassLoader(),
                new Class<?>[]{WaterRepository.class},
                (proxy, method, args) -> {
                    if ("saveAndFlush".equals(method.getName()) && !alreadyInserted[0]) {
                        alreadyInserted[0] = true;
                        repository.saveAndFlush(row(user.getId(), competingTotalMl));
                    }
                    try {
                        return method.invoke(repository, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
