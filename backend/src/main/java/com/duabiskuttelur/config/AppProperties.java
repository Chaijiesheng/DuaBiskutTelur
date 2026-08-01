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
     * Menu scans use their own (shorter) chain rather than the vision list. Each
     * attempt can burn up to menuReadTimeoutMs, so the chain length is bounded by
     * how long the CDN in front of this app will hold a proxied request open —
     * models x menuReadTimeoutMs has to stay comfortably under that ceiling.
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
    private int readTimeoutMs = 120_000;
    /**
     * Read timeout for menu scans only. A menu sends a higher-resolution image
     * and asks the model to emit a much larger JSON array (dozens of dishes, ~13
     * fields each), which regularly takes longer than a single-plate photo — at
     * the plain readTimeoutMs those calls were being cut off mid-generation and
     * reported as an overloaded provider.
     */
    private int menuReadTimeoutMs = 45_000;
    /**
     * Whether a rejected USDA match falls back to the curated local dish table
     * before the model's own estimate.
     *
     * <p>Off by default: measured on the 30-dish benchmark it made production
     * rankings worse (rho 0.42-0.58 over three scans, against 0.63 without it).
     * The offline study that justified it assumed the table would rescue the
     * five dishes whose nutrition was arithmetically impossible; in production
     * the validator rejects 10-15 per scan, so the table displaces sound USDA
     * data far more often than intended. Re-enable once the gate rejects on
     * per-serving totals rather than per-100g density — see
     * docs/menu-ranking-evaluation.md.
     */
    private boolean localDishTableEnabled = false;
    private int usdaRetries = 2;

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
    public List<String> getGeminiMenuModels() { return geminiMenuModels; }
    public void setGeminiMenuModels(List<String> v) { this.geminiMenuModels = v; }
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
    public boolean isLocalDishTableEnabled() { return localDishTableEnabled; }
    public void setLocalDishTableEnabled(boolean v) { this.localDishTableEnabled = v; }
    public int getMenuReadTimeoutMs() { return menuReadTimeoutMs; }
    public void setMenuReadTimeoutMs(int v) { this.menuReadTimeoutMs = v; }
    public int getUsdaRetries() { return usdaRetries; }
    public void setUsdaRetries(int v) { this.usdaRetries = v; }
}
