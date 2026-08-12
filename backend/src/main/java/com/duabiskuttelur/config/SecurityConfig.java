package com.duabiskuttelur.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            CustomOAuth2UserService oauth2UserService,
                                            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsSource))
                // Session cookie is SameSite=Lax + same-origin, so CSRF via cross-site
                // POST is already blocked by the browser; the JSON/multipart API doesn't
                // use Spring's CSRF token flow.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        // Health for the container's HEALTHCHECK and any external
                        // monitor; prometheus for a scraper on the compose network.
                        // Neither is reachable from outside it: the backend port is
                        // not published and nginx returns 404 for /actuator.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // Everything else under /actuator is refused outright, so
                        // widening management.endpoints.web.exposure.include - by
                        // accident, or to debug something - cannot on its own put
                        // env, beans, heapdump or threaddump on the network. Both
                        // this line and the exposure list have to change together.
                        .requestMatchers("/actuator/**").denyAll()
                        // Visitors (not signed in) can still analyze a meal, scan a barcode, or rank a menu.
                        .requestMatchers(HttpMethod.POST, "/api/analyze").permitAll()
                        // Only the two side-effect-free/committing barcode routes, named
                        // individually — a blanket /api/barcode/** would silently re-open
                        // anything added under that prefix later.
                        .requestMatchers(HttpMethod.GET, "/api/barcode/*/product").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/barcode/lookup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/menu/rank").permitAll()
                        // History, profile and identity are per-user, so require login.
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo.userService(oauth2UserService))
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?login_error"))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_OK))
                        // Spring Session (spring.session.store-type=jdbc) issues a "SESSION"
                        // cookie, not the container's native JSESSIONID.
                        .deleteCookies("SESSION")
                        .invalidateHttpSession(true))
                // Unauthenticated API calls get a plain 401 (so the SPA shows the login
                // screen) instead of a redirect to Google.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/api/**")));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        // Dev-server origins by default; production is same-origin behind nginx
        // and needs no CORS entry. See AppProperties.corsAllowedOrigins.
        config.setAllowedOrigins(props.getCorsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
