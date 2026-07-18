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
}
