package com.duabiskuttelur.model;

/**
 * A barcode scan the user has committed to logging.
 *
 * <p>Carried in a request body rather than a URL because this lookup writes a
 * meal into history, and a GET that writes is reachable by any cross-site
 * top-level navigation — SameSite=Lax sends the session cookie on those, and
 * the app runs without CSRF tokens on the strength of that cookie alone.
 *
 * <p>{@code servings} and {@code lang} are boxed so an omitted field is
 * distinguishable from a supplied zero or empty string; both fall back to a
 * default rather than being rejected.
 */
public record BarcodeLookupRequest(String code, Double servings, String lang) {
}
