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
                        // Visitors (not signed in) can still analyze a meal or scan a barcode.
                        .requestMatchers(HttpMethod.POST, "/api/analyze").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/barcode/**").permitAll()
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
                        .deleteCookies("JSESSIONID")
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
