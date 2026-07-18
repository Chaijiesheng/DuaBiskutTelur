package com.duabiskuttelur;

import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.sql.SQLException;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DuaBiskutTelurApplication {

    private static final Logger log = LoggerFactory.getLogger(DuaBiskutTelurApplication.class);

    /** Matches the default in application.yml — keep the two in sync. */
    static final String OAUTH_PLACEHOLDER = "local-dev-placeholder";

    // The placeholder default (application.yml) is what lets mock mode boot
    // without OAuth env vars, but it must never reach production silently —
    // there it means every sign-in attempt dead-ends at Google.
    @Bean
    ApplicationRunner warnWhenGoogleOAuthUnconfigured(
            @Value("${spring.security.oauth2.client.registration.google.client-id}") String googleClientId) {
        return args -> {
            if (OAUTH_PLACEHOLDER.equals(googleClientId)) {
                log.warn("GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are not set — the app runs in mock-friendly "
                        + "mode but Google sign-in WILL FAIL. Fine for local UI development; set both env vars "
                        + "before deploying.");
            }
        };
    }

    public static void main(String[] args) throws SQLException {
        // When H2_TCP_PORT is set (production/docker), run a real H2 TCP server so
        // external SQL tools (DBeaver) can connect over an SSH tunnel. Both this
        // app and DBeaver then connect as ordinary TCP clients. Loopback-only +
        // tunnel-gated at the host, so tcpAllowOthers is safe here.
        String tcpPort = System.getenv("H2_TCP_PORT");
        if (tcpPort != null && !tcpPort.isBlank()) {
            String baseDir = System.getenv().getOrDefault("H2_TCP_BASEDIR", "./data");
            Server.createTcpServer(
                    "-tcpPort", tcpPort,
                    "-tcpAllowOthers",
                    "-baseDir", baseDir,
                    "-ifNotExists").start();
            log.info("H2 TCP server started on port {} (baseDir {})", tcpPort, baseDir);
        }
        SpringApplication.run(DuaBiskutTelurApplication.class, args);
    }
}
