package com.duabiskuttelur.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /**
     * Ordered Gemini API keys, primary first. GeminiClient always prefers the
     * earliest key in this list that isn't currently rate-limited, so once a
     * key's cooldown expires it's automatically preferred again over any
     * backup keys currently in use.
     */
    private List<String> geminiApiKeys = new ArrayList<>();
    private String geminiBaseUrl = "https://generativelanguage.googleapis.com";

    /**
     * Ordered vision/feedback model lists, preferred first. When Google returns
     * a 5xx for a model ("experiencing high demand"), GeminiClient falls back to
     * the next model in the list — overload is model-side, so switching API keys
     * wouldn't help there.
     */
    private List<String> geminiVisionModels = new ArrayList<>(List.of(
            "gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash"));
    private List<String> geminiFeedbackModels = new ArrayList<>(List.of(
            "gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-2.0-flash-lite"));
    /**
     * Menu scans use their own, shorter chain rather than the vision list. Each
     * attempt can burn the full menuReadTimeoutMs, so models x timeout has to
     * stay under the ~100s ceiling the CDN in front of this app will hold a
     * proxied request open for (2 x 45s = 90s). gemini-2.0-flash is left out
     * deliberately: it is the oldest model with the smallest free-tier daily
     * quota, so it is the likeliest to be exhausted, and spending 45s
     * discovering that is not worth it.
     */
    private List<String> geminiMenuModels = new ArrayList<>(List.of(
            "gemini-flash-latest", "gemini-2.5-flash"));

    /**
     * Origins allowed to make credentialed cross-origin API calls. Only the
     * Vite dev server needs this — in production the frontend is served
     * same-origin behind nginx (which proxies /api), so no extra origin is
     * required there. Override via app.cors-allowed-origins / the
     * CORS_ALLOWED_ORIGINS env var if the frontend ever moves off-origin.
     */
    private List<String> corsAllowedOrigins = new ArrayList<>(List.of(
            "http://localhost:5173", "http://127.0.0.1:5173"));
    private String usdaApiKey = "";
    private String usdaBaseUrl = "https://api.nal.usda.gov";
    private String openFoodFactsBaseUrl = "https://world.openfoodfacts.org";
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 30_000;

    /**
     * Read timeout for menu scans only. A menu sends a higher-resolution image
     * and asks the model to emit a much larger JSON array (dozens of dishes,
     * ~13 fields each), which regularly takes longer than a single-plate photo.
     * At the shared readTimeoutMs those calls were cut off mid-generation and
     * surfaced as "analyzer is busy" while the model and every key were healthy.
     */
    private int menuReadTimeoutMs = 45_000;
    private int usdaRetries = 1;

    /**
     * Read timeout for USDA specifically. It used to share readTimeoutMs with
     * Gemini, which is sized for a model composing a paragraph — wildly
     * generous for a keyword search that normally answers in well under a
     * second, and the dominant term in how long a cold menu scan can take
     * (one lookup per dish, up to 60 of them).
     */
    private int usdaReadTimeoutMs = 5_000;

    /**
     * How many dishes on one menu are resolved at a time. Menu scans used to
     * resolve strictly one after another, so a cold 60-dish menu was 60 serial
     * round trips and could outlast the gateway timeout on its own. Bounded
     * rather than unlimited: 60 simultaneous lookups would be a burst against
     * a rate-limited third party and a burst of threads here.
     */
    private int menuResolveParallelism = 8;

    /**
     * Total wall-clock ceiling for one Gemini call chain — every model x key
     * attempt plus the backoff sleeps between rounds, not a per-call timeout
     * (that's readTimeoutMs). Without a ceiling the retry schedule multiplies
     * out to roughly 18 minutes of a held request thread against a provider
     * that accepts connections but never answers, long after the gateway has
     * already given up on the client.
     */
    private int geminiBudgetMs = 60_000;

    /**
     * Same ceiling for the feedback call, deliberately smaller: FeedbackService
     * falls back to deterministic rule-based text when the call fails, so a
     * struggling provider shouldn't get the full vision budget a second time
     * for prose that has a working substitute. Vision + feedback must together
     * stay inside the gateway's own read timeout.
     */
    private int geminiFeedbackBudgetMs = 25_000;

    /**
     * And a larger ceiling for menus, because their per-call timeout is larger.
     * The chain is two models at menuReadTimeoutMs each; under the meal budget
     * the second attempt could never start, quietly undoing the failover the
     * chain exists to provide. 90s still clears the ~100s proxy ceiling.
     */
    private int geminiMenuBudgetMs = 90_000;

    /**
     * How many Gemini calls may be in flight at once across the whole JVM. The
     * per-call budget bounds how long one request holds a servlet thread, but
     * not how many do so simultaneously — during a provider slowdown a large
     * enough burst still drains Tomcat's pool and takes unrelated endpoints
     * (sign-in, history, dashboards) down with it. Callers past this limit are
     * shed as ANALYZER_BUSY within a second instead of joining the queue, so
     * the pool stays available for everything that isn't waiting on Gemini.
     */
    private int geminiMaxConcurrentCalls = 16;

    /**
     * Pin each dish's resolved nutrition to its first resolution
     * (NutritionCacheService) so repeat scans of the same dish can't re-roll the
     * USDA-match/model-estimate lottery. Turn off to force every scan to resolve
     * afresh — e.g. when a batch of entries was pinned during a USDA outage.
     */
    private boolean nutritionCacheEnabled = true;

    /** Configured Gemini keys with blanks removed, in priority order. */
    public List<String> nonBlankGeminiApiKeys() {
        return geminiApiKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .toList();
    }

    public boolean hasGeminiKey() {
        return !nonBlankGeminiApiKeys().isEmpty();
    }

    public boolean hasUsdaKey() {
        return usdaApiKey != null && !usdaApiKey.isBlank();
    }

    public List<String> getGeminiApiKeys() { return geminiApiKeys; }
    public void setGeminiApiKeys(List<String> v) { this.geminiApiKeys = v; }
    public String getGeminiBaseUrl() { return geminiBaseUrl; }
    public void setGeminiBaseUrl(String v) { this.geminiBaseUrl = v; }
    public List<String> getGeminiVisionModels() { return geminiVisionModels; }
    public void setGeminiVisionModels(List<String> v) { this.geminiVisionModels = v; }
    public List<String> getGeminiFeedbackModels() { return geminiFeedbackModels; }
    public void setGeminiFeedbackModels(List<String> v) { this.geminiFeedbackModels = v; }
    public List<String> getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(List<String> v) { this.corsAllowedOrigins = v; }
    public String getUsdaApiKey() { return usdaApiKey; }
    public void setUsdaApiKey(String v) { this.usdaApiKey = v; }
    public String getUsdaBaseUrl() { return usdaBaseUrl; }
    public void setUsdaBaseUrl(String v) { this.usdaBaseUrl = v; }
    public String getOpenFoodFactsBaseUrl() { return openFoodFactsBaseUrl; }
    public void setOpenFoodFactsBaseUrl(String v) { this.openFoodFactsBaseUrl = v; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
    public int getUsdaRetries() { return usdaRetries; }
    public void setUsdaRetries(int v) { this.usdaRetries = v; }
    public int getUsdaReadTimeoutMs() { return usdaReadTimeoutMs; }
    public void setUsdaReadTimeoutMs(int v) { this.usdaReadTimeoutMs = v; }
    public int getMenuResolveParallelism() { return menuResolveParallelism; }
    public void setMenuResolveParallelism(int v) { this.menuResolveParallelism = v; }
    public int getGeminiBudgetMs() { return geminiBudgetMs; }
    public void setGeminiBudgetMs(int v) { this.geminiBudgetMs = v; }
    public List<String> getGeminiMenuModels() { return geminiMenuModels; }
    public void setGeminiMenuModels(List<String> v) { this.geminiMenuModels = v; }
    public int getMenuReadTimeoutMs() { return menuReadTimeoutMs; }
    public void setMenuReadTimeoutMs(int v) { this.menuReadTimeoutMs = v; }
    public int getGeminiMenuBudgetMs() { return geminiMenuBudgetMs; }
    public void setGeminiMenuBudgetMs(int v) { this.geminiMenuBudgetMs = v; }
    public int getGeminiFeedbackBudgetMs() { return geminiFeedbackBudgetMs; }
    public void setGeminiFeedbackBudgetMs(int v) { this.geminiFeedbackBudgetMs = v; }
    public int getGeminiMaxConcurrentCalls() { return geminiMaxConcurrentCalls; }
    public void setGeminiMaxConcurrentCalls(int v) { this.geminiMaxConcurrentCalls = v; }
    public boolean isNutritionCacheEnabled() { return nutritionCacheEnabled; }
    public void setNutritionCacheEnabled(boolean v) { this.nutritionCacheEnabled = v; }
}
