package com.onlinehomework;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(OutputCaptureExtension.class)
class DemoModeProductionStartupIntegrationTest {
    @Test
    void nonTestAppEnvironmentRejectsDemoModeBeforeOpeningPortOrMigratingDatabase(CapturedOutput output)
            throws Exception {
        assertStartupRejectedBeforeSideEffects(
                "production", "test", "DEMO_MODE=true", Map.of(), output);
    }

    @Test
    void prodSpringProfileRejectsDemoModeEvenWhenAppEnvironmentIsTest(CapturedOutput output) throws Exception {
        assertStartupRejectedBeforeSideEffects(
                "test", "prod", "without an active prod/production Spring profile", Map.of(), output);
    }

    @Test
    void actualProdProfileConfigurationRejectsDemoModeBeforeOpeningPortOrMigratingDatabase(CapturedOutput output)
            throws Exception {
        assertStartupRejectedBeforeSideEffects("production", "prod", "DEMO_MODE=true", Map.of(), output);
    }

    @Test
    void missingDemoSecretFailsBeforeOpeningPortOrMigratingDatabase(CapturedOutput output) throws Exception {
        assertStartupRejectedBeforeSideEffects("test", "test", "DEMO_TEACHER_PASSWORD",
                Map.of("app.demo.teacher-password", ""), output);
    }

    @Test
    void weakDemoSecretFailsBeforeOpeningPortOrMigratingDatabase(CapturedOutput output) throws Exception {
        String teacherSecret = randomSecret();
        String weakStudentSecret = UUID.randomUUID().toString().substring(0, 8);
        assertStartupRejectedBeforeSideEffects("test", "test", "DEMO_STUDENT_PASSWORD",
                Map.of("app.demo.teacher-password", teacherSecret,
                        "app.demo.student-password", weakStudentSecret), output,
                teacherSecret, weakStudentSecret);
    }

    private static void assertStartupRejectedBeforeSideEffects(String appEnvironment, String springProfile,
                                                               String expectedError,
                                                               Map<String, Object> secretProperties,
                                                               CapturedOutput output, String... sensitiveValues)
            throws Exception {
        List<String> activeProfilesAtInitialization = new java.util.ArrayList<>();
        int port = unusedPort();
        String databaseUrl = "jdbc:h2:mem:demo_guard_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String[] arguments = {
                "--spring.profiles.active=" + springProfile,
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--app.environment=" + appEnvironment,
                "--app.demo-mode=true",
                "--server.port=" + port,
                "--spring.datasource.url=" + databaseUrl
        };

        Throwable failure = catchThrowable(() -> {
            SpringApplication application = new SpringApplication(OnlineHomeworkApplication.class);
            application.setLogStartupInfo(false);
            application.addInitializers(context -> {
                for (String profile : context.getEnvironment().getActiveProfiles()) {
                    activeProfilesAtInitialization.add(profile);
                }
                context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("in-memory-demo-test-secrets", secretProperties));
            });
            try (ConfigurableApplicationContext ignored = application.run(arguments)) {
                // A successful context would mean the startup guard failed open.
            }
        });

        assertThat(activeProfilesAtInitialization).contains(springProfile);
        if ("prod".equals(springProfile)) {
            assertThat(activeProfilesAtInitialization).doesNotContain("test");
        }

        Throwable root = rootCause(failure);
        if (!(root instanceof IllegalStateException) || root.getMessage() == null
                || !root.getMessage().contains(expectedError)) {
            throw new AssertionError("DEMO startup guard did not reject the unsafe configuration as expected.");
        }
        if ("prod".equals(springProfile)) {
            assertThat(root.getMessage()).contains("DEMO_MODE=true", "prod/production Spring profile");
        }
        assertNoSensitiveText(root.getMessage(), sensitiveValues);
        assertNoSensitiveText(output.getOut() + output.getErr(), sensitiveValues);

        try (ServerSocket probe = new ServerSocket(port)) {
            assertThat(probe.isBound()).as("DEMO startup failure leaves the port unbound").isTrue();
        }

        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from information_schema.tables "
                             + "where lower(table_name) in ('user_accounts', 'flyway_schema_history')");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).as("DEMO startup failure precedes app tables and Flyway history").isZero();
        }
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Throwable rootCause(Throwable failure) {
        if (failure == null) {
            throw new AssertionError("Unsafe DEMO configuration unexpectedly started successfully.");
        }
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    private static void assertNoSensitiveText(String text, String... sensitiveValues) {
        if (text == null) {
            return;
        }
        for (String sensitiveValue : sensitiveValues) {
            if (sensitiveValue != null && !sensitiveValue.isEmpty() && text.contains(sensitiveValue)) {
                throw new AssertionError("DEMO startup diagnostic or output contained a sensitive value.");
            }
        }
    }

    private static String randomSecret() {
        return UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
    }
}
