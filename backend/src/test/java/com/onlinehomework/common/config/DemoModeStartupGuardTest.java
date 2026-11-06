package com.onlinehomework.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DemoModeStartupGuardTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GuardOnlyConfiguration.class);

    @Test
    void demoModeIsOffByDefaultEvenForProductionEnvironment() {
        contextRunner.withPropertyValues("app.environment=production").run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsDemoModeInProductionEnvironmentBeforeCheckingSecrets() {
        contextRunner.withPropertyValues("app.demo-mode=true", "app.environment=production").run(context -> {
            assertThat(context).hasFailed();
            assertRootCauseContains(context.getStartupFailure(), "DEMO_MODE=true");
        });
    }

    @Test
    void rejectsDemoModeOutsideIsolatedTestEnvironment() {
        contextRunner.withPropertyValues("app.demo-mode=true", "app.environment=development").run(context -> {
            assertThat(context).hasFailed();
            assertRootCauseContains(context.getStartupFailure(), "APP_ENV=test");
        });
    }

    @Test
    void rejectsDemoModeWhenProductionSpringProfileIsActive() {
        contextRunner.withPropertyValues("app.demo-mode=true", "app.environment=test")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertRootCauseContains(context.getStartupFailure(), "DEMO_MODE=true");
                });
    }

    @Test
    void rejectsMissingDemoSecretWithoutEchoingOtherSecret() {
        String studentSecret = randomSecret();
        contextRunner.withPropertyValues(
                        "app.demo-mode=true",
                        "app.environment=test",
                        "app.demo.student-password=" + studentSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertRootCauseContains(context.getStartupFailure(), "DEMO_TEACHER_PASSWORD");
                    assertRootCauseOmits(context.getStartupFailure(), studentSecret);
                });
    }

    @Test
    void rejectsWeakDemoSecretWithoutEchoingIt() {
        String weakSecret = UUID.randomUUID().toString().substring(0, 8);
        String teacherSecret = randomSecret();
        contextRunner.withPropertyValues(
                        "app.demo-mode=true",
                        "app.environment=test",
                        "app.demo.teacher-password=" + teacherSecret,
                        "app.demo.student-password=" + weakSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertRootCauseContains(context.getStartupFailure(), "DEMO_STUDENT_PASSWORD");
                    assertRootCauseOmits(context.getStartupFailure(), weakSecret, teacherSecret);
                });
    }

    private static void assertRootCauseContains(Throwable failure, String expectedText) {
        Throwable root = rootCause(failure);
        if (!(root instanceof IllegalStateException) || root.getMessage() == null
                || !root.getMessage().contains(expectedText)) {
            throw new AssertionError("Startup guard rejected a configuration without the expected safe diagnostic.");
        }
    }

    private static void assertRootCauseOmits(Throwable failure, String... secrets) {
        Throwable root = rootCause(failure);
        String message = root.getMessage();
        if (message == null) {
            return;
        }
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty() && message.contains(secret)) {
                throw new AssertionError("Startup guard diagnostic contained a sensitive value.");
            }
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    private static String randomSecret() {
        return UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DemoModeStartupGuard.class)
    static class GuardOnlyConfiguration {
    }
}
