package com.onlinehomework.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorsWhitelistConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CorsWhitelistConfiguration.class);

    @Test
    void acceptsExplicitHttpAndHttpsOrigins() {
        contextRunner.withPropertyValues(
                        "app.cors.allowed-origins=https://school.example,http://localhost:3000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CorsConfigurationSource.class);
                });
    }

    @Test
    void rejectsWildcardOrigin() {
        contextRunner.withPropertyValues("app.cors.allowed-origins=*").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void rejectsOriginsWithPaths() {
        contextRunner.withPropertyValues("app.cors.allowed-origins=https://school.example/app")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }
}
