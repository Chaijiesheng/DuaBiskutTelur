package com.duabiskuttelur.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks rate-limit cooldowns and decides which configured Gemini API key to
 * use next. Keys are always tried in priority order (primary first); a key
 * that's cooling down is skipped until its cooldown expires, at which point
 * it's preferred again ahead of any backup key currently in use.
 *
 * <p>Cooldowns are scoped to a (key, model) pair because that's how Google
 * meters quota — "GenerateRequestsPerDayPerProjectPerModel". Benching a key
 * outright on a 429 used to take the whole pool down whenever one model ran
 * dry: an exhausted legacy fallback would mark every key rate-limited, so the
 * next request skipped the primary model those same keys were still happily
 * serving.
 */
class GeminiKeyPool {

    /** Quota is metered per model, so a key cools down only for the model that rejected it. */
    private record KeyModel(String key, String model) {
    }

    private final List<String> keys;
    private final Clock clock;
    private final Map<KeyModel, Instant> cooldownUntil = new ConcurrentHashMap<>();

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

    /** First configured key, in priority order, that isn't cooling down for this model. */
    Optional<String> nextAvailableKey(String model) {
        return keys.stream().filter(k -> !isCoolingDown(k, model)).findFirst();
    }

    boolean isCoolingDown(String key, String model) {
        Instant until = cooldownUntil.get(new KeyModel(key, model));
        return until != null && clock.instant().isBefore(until);
    }

    void markRateLimited(String key, String model, Duration cooldown) {
        cooldownUntil.put(new KeyModel(key, model), clock.instant().plus(cooldown));
    }
}
