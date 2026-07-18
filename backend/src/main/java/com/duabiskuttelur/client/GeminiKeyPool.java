package com.duabiskuttelur.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-key rate-limit cooldowns and decides which configured Gemini API
 * key to use next. Keys are always tried in priority order (primary first);
 * a key that's cooling down is skipped until its cooldown expires, at which
 * point it's preferred again ahead of any backup key currently in use.
 */
class GeminiKeyPool {

    private final List<String> keys;
    private final Clock clock;
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();

    GeminiKeyPool(List<String> keys) {
        this(keys, Clock.systemUTC());
    }

    GeminiKeyPool(List<String> keys, Clock clock) {
        this.keys = List.copyOf(keys);
        this.clock = clock;
    }

    List<String> keys() {
        return keys;
    }

    boolean isEmpty() {
        return keys.isEmpty();
    }

    /** First configured key, in priority order, that isn't currently cooling down. */
    Optional<String> nextAvailableKey() {
        return keys.stream().filter(k -> !isCoolingDown(k)).findFirst();
    }

    boolean isCoolingDown(String key) {
        Instant until = cooldownUntil.get(key);
        return until != null && clock.instant().isBefore(until);
    }

    void markRateLimited(String key, Duration cooldown) {
        cooldownUntil.put(key, clock.instant().plus(cooldown));
    }
}
