package com.onlinehomework.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class CorsWhitelistConfiguration {
    private static final String INVALID_ORIGINS_MESSAGE =
            "CORS_ALLOWED_ORIGINS must contain comma-separated exact HTTP(S) origins without paths or wildcards.";

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") String configuredOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseAllowedOrigins(configuredOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static List<String> parseAllowedOrigins(String configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            return List.of();
        }

        List<String> origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();
        for (String origin : origins) {
            validateExactOrigin(origin);
        }
        return origins;
    }

    private static void validateExactOrigin(String origin) {
        if ("*".equals(origin)) {
            throw invalidOrigins();
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            if ((!"http".equals(scheme) && !"https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || (path != null && !path.isEmpty())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() > 65_535) {
                throw invalidOrigins();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidOrigins();
        }
    }

    private static IllegalStateException invalidOrigins() {
        return new IllegalStateException(INVALID_ORIGINS_MESSAGE);
    }
}
