package com.onlinehomework.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

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
        String studentSecret = "A-strong-student-demo-secret-2026";
        contextRunner.withPropertyValues(
                        "app.demo-mode=true",
                        "app.environment=test",
                        "app.demo.student-password=" + studentSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertRootCauseContains(context.getStartupFailure(), "DEMO_TEACHER_PASSWORD");
                    assertThat(context.getStartupFailure().getMessage()).doesNotContain(studentSecret);
                });
    }

    @Test
    void rejectsWeakDemoSecretWithoutEchoingIt() {
        String weakSecret = "short-password";
        String teacherSecret = "A-strong-teacher-demo-secret-2026";
        contextRunner.withPropertyValues(
                        "app.demo-mode=true",
                        "app.environment=test",
                        "app.demo.teacher-password=" + teacherSecret,
                        "app.demo.student-password=" + weakSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertRootCauseContains(context.getStartupFailure(), "DEMO_STUDENT_PASSWORD");
                    assertThat(context.getStartupFailure().getMessage())
                            .doesNotContain(weakSecret).doesNotContain(teacherSecret);
                });
    }

    private static void assertRootCauseContains(Throwable failure, String expectedText) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class);
        assertThat(root.getMessage()).contains(expectedText);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DemoModeStartupGuard.class)
    static class GuardOnlyConfiguration {
    }
}
