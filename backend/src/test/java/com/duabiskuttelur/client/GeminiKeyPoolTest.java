package com.duabiskuttelur.client;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the primary-first / fallback-on-limit / auto-restore rotation
 * requested: try key 1 first; if it's rate-limited, use a backup; once key 1's
 * limit has reset, prefer it again over the backups.
 */
class GeminiKeyPoolTest {

    private static final String KEY_1 = "key-1-primary";
    private static final String KEY_2 = "key-2-backup";
    private static final String KEY_3 = "key-3-backup";

    /** Test double clock so cooldown expiry can be advanced deterministically. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void primaryKeyIsPreferredWhenNothingIsRateLimited() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3));

        assertEquals(KEY_1, pool.nextAvailableKey().orElseThrow());
    }

    @Test
    void fallsBackToNextKeyWhenPrimaryIsRateLimited() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3));

        pool.markRateLimited(KEY_1, Duration.ofSeconds(60));

        assertTrue(pool.isCoolingDown(KEY_1));
        assertEquals(KEY_2, pool.nextAvailableKey().orElseThrow());
    }

    @Test
    void fallsBackToThirdKeyWhenFirstTwoAreRateLimited() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3));

        pool.markRateLimited(KEY_1, Duration.ofSeconds(60));
        pool.markRateLimited(KEY_2, Duration.ofSeconds(60));

        assertEquals(KEY_3, pool.nextAvailableKey().orElseThrow());
    }

    @Test
    void noKeyAvailableWhenAllAreRateLimited() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3));

        pool.markRateLimited(KEY_1, Duration.ofSeconds(60));
        pool.markRateLimited(KEY_2, Duration.ofSeconds(60));
        pool.markRateLimited(KEY_3, Duration.ofSeconds(60));

        assertTrue(pool.nextAvailableKey().isEmpty());
    }

    @Test
    void primaryKeyIsAutomaticallyPreferredAgainOnceItsCooldownExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3), clock);

        // Primary hits its per-minute limit -> we fall back to the backup key
        pool.markRateLimited(KEY_1, Duration.ofSeconds(60));
        assertEquals(KEY_2, pool.nextAvailableKey().orElseThrow(), "should use backup while primary cools down");

        // Time passes, but not enough for the primary's limit to reset yet
        clock.advance(Duration.ofSeconds(30));
        assertTrue(pool.isCoolingDown(KEY_1));
        assertEquals(KEY_2, pool.nextAvailableKey().orElseThrow(), "primary still cooling down");

        // Once the primary's cooldown has fully elapsed, it's preferred again
        // over the backup that was actively serving requests in the meantime
        clock.advance(Duration.ofSeconds(31));
        assertFalse(pool.isCoolingDown(KEY_1));
        assertEquals(KEY_1, pool.nextAvailableKey().orElseThrow(), "primary should be restored automatically");
    }

    @Test
    void retryAfterHeaderCanShortenOrLengthenTheDefaultCooldown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2), clock);

        pool.markRateLimited(KEY_1, Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(6));

        assertFalse(pool.isCoolingDown(KEY_1), "short server-specified cooldown should already have elapsed");
        assertEquals(KEY_1, pool.nextAvailableKey().orElseThrow());
    }

    @Test
    void emptyPoolHasNoAvailableKey() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of());

        assertTrue(pool.isEmpty());
        assertTrue(pool.nextAvailableKey().isEmpty());
    }
}
