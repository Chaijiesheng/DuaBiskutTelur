package com.duabiskuttelur.client;

/**
 * Thrown when the AI provider keeps rate-limiting us after all retries
 * (free-tier quota). Mapped to HTTP 503 so the frontend can show a
 * "try again in a minute" state.
 */
public class ProviderBusyException extends RuntimeException {

    public ProviderBusyException(String message) {
        super(message);
    }
}
