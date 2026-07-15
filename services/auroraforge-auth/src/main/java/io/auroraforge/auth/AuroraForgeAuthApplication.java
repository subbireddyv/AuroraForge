package io.auroraforge.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AuroraForge Auth Service entry point.
 *
 * When deployed as a standalone service ({@code auroraforge.auth.server.enabled=true}),
 * this is the auth server that issues JWTs and exposes the JWKS endpoint.
 *
 * When packaged as a library (included by other modules), only
 * {@link AuroraForgeSecurityAutoConfiguration} is active — this main class is never invoked.
 */
@SpringBootApplication
@EnableScheduling
public class AuroraForgeAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuroraForgeAuthApplication.class, args);
    }
}
