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
    private static final String MODEL = "gemini-flash-latest";
    private static final String OTHER_MODEL = "gemini-2.0-flash";

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

        assertEquals(KEY_1, pool.nextAvailableKey(MODEL).orElseThrow());
    }

    @Test
    void fallsBackToNextKeyWhenPrimaryIsRateLimited() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3));

        pool.markRateLimited(KEY_1, MODEL, Duration.ofSeconds(60));

        assertTrue(pool.isCoolingDown(KEY_1, MODEL));
        assertEquals(KEY_2, pool.nextAvailableKey(MODEL).orElseThrow());
    }

    @Test
    void fallsBackToThirdKeyWhenFirstTwoAreRateLimited() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3));

        pool.markRateLimited(KEY_1, MODEL, Duration.ofSeconds(60));
        pool.markRateLimited(KEY_2, MODEL, Duration.ofSeconds(60));

        assertEquals(KEY_3, pool.nextAvailableKey(MODEL).orElseThrow());
    }

    @Test
    void noKeyAvailableWhenAllAreRateLimited() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3));

        pool.markRateLimited(KEY_1, MODEL, Duration.ofSeconds(60));
        pool.markRateLimited(KEY_2, MODEL, Duration.ofSeconds(60));
        pool.markRateLimited(KEY_3, MODEL, Duration.ofSeconds(60));

        assertTrue(pool.nextAvailableKey(MODEL).isEmpty());
    }

    @Test
    void primaryKeyIsAutomaticallyPreferredAgainOnceItsCooldownExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3), clock);

        // Primary hits its per-minute limit -> we fall back to the backup key
        pool.markRateLimited(KEY_1, MODEL, Duration.ofSeconds(60));
        assertEquals(KEY_2, pool.nextAvailableKey(MODEL).orElseThrow(), "should use backup while primary cools down");

        // Time passes, but not enough for the primary's limit to reset yet
        clock.advance(Duration.ofSeconds(30));
        assertTrue(pool.isCoolingDown(KEY_1, MODEL));
        assertEquals(KEY_2, pool.nextAvailableKey(MODEL).orElseThrow(), "primary still cooling down");

        // Once the primary's cooldown has fully elapsed, it's preferred again
        // over the backup that was actively serving requests in the meantime
        clock.advance(Duration.ofSeconds(31));
        assertFalse(pool.isCoolingDown(KEY_1, MODEL));
        assertEquals(KEY_1, pool.nextAvailableKey(MODEL).orElseThrow(), "primary should be restored automatically");
    }

    @Test
    void retryAfterHeaderCanShortenOrLengthenTheDefaultCooldown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2), clock);

        pool.markRateLimited(KEY_1, MODEL, Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(6));

        assertFalse(pool.isCoolingDown(KEY_1, MODEL), "short server-specified cooldown should already have elapsed");
        assertEquals(KEY_1, pool.nextAvailableKey(MODEL).orElseThrow());
    }

    /**
     * Reproduces a real outage: gemini-2.0-flash exhausted its per-project daily
     * quota, so every key 429'd on it. Because cooldowns were tracked per key
     * rather than per (key, model), that benched all three keys on EVERY model —
     * including the primary they were still serving fine — and the next request
     * failed instantly with "all keys unavailable".
     */
    @Test
    void exhaustingOneModelLeavesTheSameKeysUsableOnAnotherModel() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of(KEY_1, KEY_2, KEY_3));

        pool.markRateLimited(KEY_1, OTHER_MODEL, Duration.ofSeconds(60));
        pool.markRateLimited(KEY_2, OTHER_MODEL, Duration.ofSeconds(60));
        pool.markRateLimited(KEY_3, OTHER_MODEL, Duration.ofSeconds(60));

        assertTrue(pool.nextAvailableKey(OTHER_MODEL).isEmpty(), "the exhausted model has no key left");
        assertFalse(pool.isCoolingDown(KEY_1, MODEL), "quota is metered per model, so the primary is unaffected");
        assertEquals(KEY_1, pool.nextAvailableKey(MODEL).orElseThrow(),
                "the primary model should still get the primary key");
    }

    @Test
    void emptyPoolHasNoAvailableKey() {
        GeminiKeyPool pool = new GeminiKeyPool(List.of());

        assertTrue(pool.isEmpty());
        assertTrue(pool.nextAvailableKey(MODEL).isEmpty());
    }
}
