package com.duabiskuttelur.client;

import com.duabiskuttelur.config.AppProperties;
import com.duabiskuttelur.model.IdentifiedFood;
import com.duabiskuttelur.model.FeedbackResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Google Gemini implementation of the vision and feedback provider interfaces.
 * Vision uses the multimodal model; feedback uses the cheaper text-only model.
 * API keys never leave this backend.
 *
 * Supports multiple API keys (primary + backups, in priority order). On each
 * call, the first key that isn't currently rate-limited is used. A 429 puts
 * that key into a cooldown and the request immediately falls through to the
 * next key in priority order — no need to wait if a backup is free. Once a
 * key's cooldown expires it's automatically preferred again over any backup
 * still in use, since every call re-scans from the front of the list.
 *
 * If every key is cooling down, the call retries with exponential backoff
 * (2s, 4s, 8s); once exhausted a ProviderBusyException is thrown and
 * surfaced to the frontend as a 503.
 */
@Component
public class GeminiClient implements VisionAnalysisClient, FeedbackClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private static final long[] BACKOFF_MS = {2_000, 4_000, 8_000};
    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final RestClient menuRestClient;
    private final GeminiKeyPool keyPool;

    private static final String VISION_SYSTEM_PROMPT = """
            You are a nutrition vision analyst for a Malaysian food-tracking app. \
            You identify every distinct food and drink item visible in a meal photo, \
            including local dishes such as nasi lemak, roti canai, teh tarik, char kway teow, \
            satay, laksa, rendang, mee goreng and kuih. \
            Respond with STRICT JSON ONLY: a single JSON array, no prose, no markdown fences.""";

    private static final String VISION_USER_PROMPT = """
            Identify every distinct food and drink item in this photo. Return a JSON array where each element has exactly these fields:
            {
              "name": string (dish name, include local name and English gloss if applicable),
              "estimatedPortion": string (household measure AND grams, e.g. "1 cup / ~200g"),
              "grams": number (estimated weight in grams),
              "usdaSearchTerm": string (closest generic equivalent likely to exist in USDA FoodData Central, e.g. "coconut rice" for nasi lemak base),
              "fallbackCaloriesPer100g": number,
              "fallbackProteinPer100g": number,
              "fallbackCarbsPer100g": number,
              "fallbackFatPer100g": number,
              "fallbackFiberPer100g": number,
              "fallbackSugarPer100g": number,
              "fallbackSodiumPer100g": number (milligrams),
              "foodGroup": one of "protein" | "grain" | "vegetable" | "fruit" | "dairy" | "fat" | "sweet" | "beverage",
              "fried": boolean (true if deep-fried or visibly oily),
              "confidence": number between 0 and 1
            }
            If no food is visible, return an empty array []. Return ONLY the JSON array.""";

    private static final String MENU_SYSTEM_PROMPT = """
            You are a nutrition vision analyst for a Malaysian food-tracking app. \
            You read restaurant and cafe MENUS — printed or handwritten lists of dishes available \
            to order, not photos of plated food — and extract every distinct dish listed, including \
            local dishes such as nasi lemak, roti canai, teh tarik, char kway teow, satay, laksa, \
            rendang, mee goreng and kuih. Menus may mix English, Chinese and Malay on the same page; \
            read every language present. Respond with STRICT JSON ONLY: a single JSON array, no prose, \
            no markdown fences.""";

    private static final String MENU_USER_PROMPT = """
            This photo is a MENU (a list of dishes available to order), not a plate of food that has \
            already been served. Identify every distinct dish or drink listed — do not invent dishes \
            that aren't printed, and do not skip a dish just because its price is illegible. If the same \
            dish name repeats in multiple languages on one line (e.g. "炒粿条 / Char Kway Teow"), treat it \
            as ONE entry, not two. Ignore the restaurant name/address, opening hours, and other non-food \
            text. If a price is legibly printed next to a dish, fold it into that dish's "name" field as \
            a trailing parenthetical (e.g. "Nasi Lemak (RM8.50)").

            Use the menu's own section headings to classify each item's "kind":
            - "addon" — extras, sides and condiments sold to accompany something else, typically under a \
            heading like "Add On", "Extra", "Tambah" or "Side", and typically cheap (plain rice, a fried \
            egg, sambal, kaya, butter, an extra piece of chicken).
            - "drink" — anything you drink: coffee, tea, juice, soft drinks, water.
            - "main" — everything else, i.e. a dish someone would order as their meal.
            When a section heading is unclear, judge by whether the item is a meal in its own right.

            For each dish, assume a single typical serving size for that dish as it is normally served at \
            a restaurant (there is no plate to measure, since this is a menu) and return a JSON array where \
            each element has exactly these fields:
            {
              "name": string (dish name, include local name and English gloss if the menu shows both; fold in price if legible, see above),
              "estimatedPortion": string (typical household measure AND grams for one serving, e.g. "1 plate / ~350g"),
              "grams": number (typical serving weight in grams for this dish),
              "usdaSearchTerm": string (closest generic equivalent likely to exist in USDA FoodData Central),
              "fallbackCaloriesPer100g": number,
              "fallbackProteinPer100g": number,
              "fallbackCarbsPer100g": number,
              "fallbackFatPer100g": number,
              "fallbackFiberPer100g": number,
              "fallbackSugarPer100g": number,
              "fallbackSodiumPer100g": number (milligrams),
              "foodGroup": one of "protein" | "grain" | "vegetable" | "fruit" | "dairy" | "fat" | "sweet" | "beverage",
              "fried": boolean (true if the dish is typically deep-fried, based on its name/description),
              "confidence": number between 0 and 1,
              "kind": one of "main" | "addon" | "drink" (see the classification rules above)
            }
            If this photo is not a menu at all (e.g. it's a photo of a plated meal, a receipt, or something \
            unrelated), return an empty array []. Return ONLY the JSON array, with at most 60 entries — if \
            the menu has more than 60 dishes, return only the first 60 encountered reading top-to-bottom, \
            left-to-right.""";

    public GeminiClient(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.keyPool = new GeminiKeyPool(props.nonBlankGeminiApiKeys());
        this.restClient = clientWithReadTimeout(props, props.getReadTimeoutMs());
        // Menu scans get their own client purely for the longer read timeout —
        // see app.menu-read-timeout-ms for why they need one.
        this.menuRestClient = clientWithReadTimeout(props, props.getMenuReadTimeoutMs());
    }

    private static RestClient clientWithReadTimeout(AppProperties props, int readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder()
                .baseUrl(props.getGeminiBaseUrl())
                .requestFactory(factory)
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Override
    public List<IdentifiedFood> identifyFoods(byte[] imageBytes, String mediaType) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", VISION_SYSTEM_PROMPT))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(
                                Map.of("inlineData", Map.of(
                                        "mimeType", mediaType,
                                        "data", Base64.getEncoder().encodeToString(imageBytes))),
                                Map.of("text", VISION_USER_PROMPT)))),
                "generationConfig", Map.of(
                        "maxOutputTokens", 4096,
                        "responseMimeType", "application/json"));

        String text = callWithRetry(props.getGeminiVisionModels(), body, restClient);
        String json = extractJson(text, '[', ']');
        try {
            return mapper.readerForListOf(IdentifiedFood.class).readValue(json);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse food list from Gemini response", e);
        }
    }

    @Override
    public List<IdentifiedFood> identifyMenuDishes(byte[] imageBytes, String mediaType) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", MENU_SYSTEM_PROMPT))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(
                                Map.of("inlineData", Map.of(
                                        "mimeType", mediaType,
                                        "data", Base64.getEncoder().encodeToString(imageBytes))),
                                Map.of("text", MENU_USER_PROMPT)))),
                "generationConfig", Map.of(
                        // Much higher than identifyFoods' 4096: a 60-dish array of ~14 fields
                        // each is already large, and this budget is shared with the model's
                        // own reasoning before it emits a single character. At 8192 a real
                        // 21-item kopitiam menu came back cut off mid-array.
                        "maxOutputTokens", 24576,
                        "responseMimeType", "application/json"));

        String text = callWithRetry(props.getGeminiMenuModels(), body, menuRestClient);
        String json = extractJson(text, '[', ']');
        try {
            return mapper.readerForListOf(IdentifiedFood.class).readValue(json);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse menu dish list from Gemini response", e);
        }
    }

    @Override
    public FeedbackResult generateFeedback(String mealContext) {
        String prompt = """
                You are a friendly, encouraging Malaysian nutrition coach. Here is a meal a user just logged, \
                already scored by a deterministic engine:

                %s

                Respond with STRICT JSON ONLY (no markdown fences) with exactly these fields:
                {
                  "highlights": [2-3 short strings about what is good in this meal],
                  "concerns": [1-3 short strings about the downside of this combination; mention specific numbers like sodium mg or sugar g when relevant],
                  "suggestions": [2-3 concrete, practical suggestions for the next meal, tailored to Malaysian food where natural],
                  "encouragement": one warm sentence matched to the grade
                }""".formatted(mealContext);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "maxOutputTokens", 1024,
                        "responseMimeType", "application/json"));

        String text = callWithRetry(props.getGeminiFeedbackModels(), body, restClient);
        String json = extractJson(text, '{', '}');
        try {
            return mapper.readValue(json, FeedbackResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse feedback from Gemini response", e);
        }
    }

    /**
     * Tries each fallback model in order; within a model, tries each API key
     * (priority order, skipping keys in cooldown). Two failure modes are
     * handled differently:
     *
     * - 429 (rate limit) is key-specific: cool that key down and try the next
     *   key on the same model.
     * - 5xx ("model experiencing high demand", outages) is model-specific and
     *   key-independent: skip straight to the next fallback model.
     *
     * Only when a full pass over every model finds nothing usable do we sleep
     * (exponential backoff) and try again. Throws ProviderBusyException once
     * the backoff schedule is exhausted — surfaced to the app as a friendly
     * "analyzer is busy" instead of a generic failure.
     *
     * <p>A model that timed out or returned 5xx is written off for the rest of
     * THIS call: waiting doesn't un-hang it, and re-dialling a model that just
     * burned the full read timeout would multiply this request's latency by the
     * number of backoff rounds — enough to blow past the CDN's proxy timeout
     * and turn a slow answer into no answer. Backing off only helps when some
     * model is still live and merely waiting on a key cooldown, so when every
     * model is written off we fail fast instead of sleeping first.
     */
    private String callWithRetry(List<String> models, Map<String, Object> body, RestClient client) {
        if (keyPool.isEmpty()) {
            throw new IllegalStateException("No Gemini API key configured");
        }
        if (models.isEmpty()) {
            throw new IllegalStateException("No Gemini model configured");
        }
        Set<String> deadModels = new HashSet<>();
        for (int round = 0; ; round++) {
            for (String model : models) {
                if (deadModels.contains(model)) {
                    continue;
                }
                for (String key : keyPool.keys()) {
                    if (keyPool.isCoolingDown(key, model)) {
                        continue;
                    }
                    try {
                        return callAndExtractText(model, body, key, client);
                    } catch (HttpStatusCodeException e) {
                        if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                            Duration cooldown = retryAfter(e).orElse(DEFAULT_COOLDOWN);
                            keyPool.markRateLimited(key, model, cooldown);
                            log.info("Gemini 429 on {} for {}; cooling that key down {}s for this model, trying next key",
                                    model, maskedKey(key), cooldown.toSeconds());
                        } else if (e.getStatusCode().is5xxServerError()) {
                            log.warn("Gemini {} on model {} (server-side overload/outage); trying next fallback model",
                                    e.getStatusCode().value(), model);
                            deadModels.add(model);
                            break; // next model — this failure isn't key-related
                        } else {
                            throw e;
                        }
                    } catch (ResourceAccessException e) {
                        // Connect/read timeout — an overloaded model that hangs is
                        // the same situation as an explicit 503: try the next model.
                        log.warn("Gemini I/O timeout on model {} ({}); trying next fallback model",
                                model, e.getMessage());
                        deadModels.add(model);
                        break;
                    } catch (RestClientException e) {
                        // A timeout while reading/deserializing the response body
                        // (after the connection succeeded) surfaces as this broader
                        // supertype rather than ResourceAccessException — same
                        // "give up on this model" situation as the branch above.
                        if (isTimeout(e)) {
                            log.warn("Gemini I/O timeout (response read) on model {} ({}); trying next fallback model",
                                    model, e.getMessage());
                            deadModels.add(model);
                            break;
                        }
                        throw e;
                    }
                }
            }
            boolean everyModelDead = deadModels.size() == models.size();
            if (everyModelDead || round >= BACKOFF_MS.length) {
                throw new ProviderBusyException(
                        "Gemini unavailable after retries across " + models.size()
                                + " model(s) and " + keyPool.keys().size() + " key(s)");
            }
            long waitMs = BACKOFF_MS[round];
            log.info("All Gemini models/keys unavailable; retrying in {}ms (round {}/{})",
                    waitMs, round + 1, BACKOFF_MS.length);
            sleep(waitMs);
        }
    }

    /**
     * Walks the cause chain looking for a timeout. Checks the broader
     * {@link InterruptedIOException} (which {@link java.net.SocketTimeoutException}
     * extends) rather than just the socket-specific subtype, and defensively
     * matches by simple class name for differently-named timeout exceptions
     * (e.g. Netty's ReadTimeoutException) that don't share that supertype, in
     * case the underlying HTTP client ever changes.
     */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException || t.getClass().getSimpleName().equals("ReadTimeoutException")) {
                return true;
            }
        }
        return false;
    }

    /** Reads the Retry-After header (seconds form) from a 429 response, if present. */
    private static Optional<Duration> retryAfter(HttpStatusCodeException e) {
        String header = e.getResponseHeaders() != null
                ? e.getResponseHeaders().getFirst("Retry-After") : null;
        if (header == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private String callAndExtractText(String model, Map<String, Object> body, String key, RestClient client) {
        long startedAt = System.nanoTime();
        JsonNode response = client.post()
                .uri("/v1beta/models/{model}:generateContent", model)
                .header("x-goog-api-key", key)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new IllegalStateException("Empty response from Gemini API");
        }
        // Prompt vs output tokens is the difference between "the image is too
        // big" and "we asked for too many fields" — without it, tuning either
        // one is guesswork. Images dominate promptTokenCount.
        JsonNode usage = response.path("usageMetadata");
        // finishReason is the tell for a response that stopped mid-JSON:
        // MAX_TOKENS means what came back is a fragment, not an answer. Thinking
        // models spend part of the same budget before emitting any text, so the
        // visible output token count alone doesn't reveal how close we came.
        log.info("Gemini {} answered in {}ms (promptTokens={}, outputTokens={}, thoughtTokens={}, finish={})",
                model, (System.nanoTime() - startedAt) / 1_000_000,
                usage.path("promptTokenCount").asInt(-1),
                usage.path("candidatesTokenCount").asInt(-1),
                usage.path("thoughtsTokenCount").asInt(-1),
                response.path("candidates").path(0).path("finishReason").asText("?"));
        String blockReason = response.path("promptFeedback").path("blockReason").asText("");
        if (!blockReason.isEmpty()) {
            throw new IllegalStateException("Gemini blocked the request: " + blockReason);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (part.has("text")) {
                sb.append(part.path("text").asText());
            }
        }
        if (sb.isEmpty()) {
            log.warn("Gemini response had no text parts: finishReason={}",
                    response.path("candidates").path(0).path("finishReason").asText(""));
            throw new IllegalStateException("No text content in Gemini response");
        }
        return sb.toString();
    }

    /** Never log full API keys — just enough to tell keys apart in logs. */
    private static String maskedKey(String key) {
        return key.length() > 4 ? "…" + key.substring(key.length() - 4) : "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ProviderBusyException("Interrupted while waiting out Gemini rate limit");
        }
    }

    /**
     * Defensive JSON extraction: strips markdown fences and any prose around the
     * outermost open/close pair.
     *
     * <p>An array that stopped mid-generation (a long menu against the output
     * token budget) has an opening bracket and no closing one. Rather than lose
     * the whole scan, the complete elements are salvaged and the array closed —
     * 40 dishes read off a menu beats an error page because the 41st was cut in
     * half. Anything with no recoverable element still fails loudly.
     */
    static String extractJson(String text, char open, char close) {
        String cleaned = text.replaceAll("(?s)```(?:json)?", "").trim();
        int start = cleaned.indexOf(open);
        int end = cleaned.lastIndexOf(close);
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        if (start >= 0 && open == '[') {
            String salvaged = salvageTruncatedArray(cleaned, start);
            if (salvaged != null) {
                log.warn("Model response was cut off mid-array; salvaged the complete entries");
                return salvaged;
            }
        }
        throw new IllegalStateException("No JSON payload found in model response (length="
                + cleaned.length() + ", starts with: "
                + cleaned.substring(0, Math.min(120, cleaned.length())).replace('\n', ' ') + ")");
    }

    /**
     * Walks a truncated array and cuts it back to the last element that closed
     * cleanly. Returns null when not even one element survived.
     */
    private static String salvageTruncatedArray(String text, int start) {
        int depth = 0;
        int lastCompleteElement = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '[' || c == '{') {
                    depth++;
                } else if (c == ']' || c == '}') {
                    depth--;
                    // Back to depth 1 means an element of the outer array just closed.
                    if (depth == 1) {
                        lastCompleteElement = i;
                    }
                }
            }
        }
        return lastCompleteElement < 0 ? null : text.substring(start, lastCompleteElement + 1) + "]";
    }
}
