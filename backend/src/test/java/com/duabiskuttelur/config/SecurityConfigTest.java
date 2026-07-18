package com.duabiskuttelur.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSRF protection is disabled app-wide (see SecurityConfig), which is only
 * safe because the session cookie is SameSite=Lax + HttpOnly + Secure — a
 * cross-site POST can't ride along with it. Nothing else enforces that
 * invariant, so a future change to server.servlet.session.cookie.* could
 * silently reopen the CSRF hole with no other signal. This test is that signal.
 *
 * Needs a real embedded server (not MockMvc's mock dispatcher) because the
 * Secure/HttpOnly/SameSite attributes are written by the servlet container's
 * cookie processor, which MockMvc never actually invokes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.datasource.url=jdbc:h2:mem:security-config-test;DB_CLOSE_DELAY=-1"
})
class SecurityConfigTest {

    @LocalServerPort
    private int port;

    @Test
    void sessionCookieIsSameSiteLaxHttpOnlyAndSecureBehindTls() throws Exception {
        // Hitting the OAuth2 authorization endpoint stores the pending
        // authorization request in the session before redirecting to Google —
        // that's enough to make the container issue a JSESSIONID cookie
        // without needing real Google credentials or a completed login.
        // Redirects are followed manually (NEVER here) so we inspect our own
        // server's first response, not wherever the 3xx points afterward.
        // X-Forwarded-Proto: https simulates what Cloudflare/nginx actually
        // send in production (see docker-compose.yml); forward-headers-strategy:
        // framework is what turns that into request.isSecure()=true, which is
        // what makes the container mark the cookie Secure.
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/oauth2/authorization/google"))
                .header("X-Forwarded-Proto", "https")
                .GET()
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        List<String> setCookie = response.headers().allValues("Set-Cookie");
        String jsessionCookie = setCookie.stream()
                .filter(c -> c.toUpperCase().startsWith("JSESSIONID"))
                .findFirst()
                .orElse(null);
        assertNotNull(jsessionCookie, "expected a JSESSIONID cookie: " + setCookie);

        String lower = jsessionCookie.toLowerCase();
        assertTrue(lower.contains("httponly"), "missing HttpOnly: " + jsessionCookie);
        assertTrue(lower.contains("samesite=lax"), "missing SameSite=Lax: " + jsessionCookie);
        assertTrue(lower.contains("secure"), "missing Secure: " + jsessionCookie);
    }
}
