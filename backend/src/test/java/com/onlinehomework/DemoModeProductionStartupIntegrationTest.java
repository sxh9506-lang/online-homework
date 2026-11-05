package com.onlinehomework;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(OutputCaptureExtension.class)
class DemoModeProductionStartupIntegrationTest {
    private static final String VALID_STUDENT_SECRET = "A-strong-student-demo-secret-2026";
    private static final String VALID_TEACHER_SECRET = "A-strong-teacher-demo-secret-2026";
    private static final String WEAK_SECRET = "short-password";

    @Test
    void productionDemoModeFailsBeforeOpeningPortOrMigratingDatabase(CapturedOutput output) throws Exception {
        assertStartupRejectedBeforeSideEffects("production", "DEMO_MODE=true", output);
    }

    @Test
    void missingDemoSecretFailsBeforeOpeningPortOrMigratingDatabase(CapturedOutput output) throws Exception {
        assertStartupRejectedBeforeSideEffects("test", "DEMO_TEACHER_PASSWORD", output,
                "--app.demo.teacher-password=",
                "--app.demo.student-password=" + VALID_STUDENT_SECRET);
    }

    @Test
    void weakDemoSecretFailsBeforeOpeningPortOrMigratingDatabase(CapturedOutput output) throws Exception {
        assertStartupRejectedBeforeSideEffects("test", "DEMO_STUDENT_PASSWORD", output,
                "--app.demo.teacher-password=" + VALID_TEACHER_SECRET,
                "--app.demo.student-password=" + WEAK_SECRET);
    }

    private static void assertStartupRejectedBeforeSideEffects(String appEnvironment, String expectedError,
                                                               CapturedOutput output, String... secretArguments)
            throws Exception {
        int port = unusedPort();
        String databaseUrl = "jdbc:h2:mem:demo_guard_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=test",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--app.environment=" + appEnvironment,
                "--app.demo-mode=true",
                "--server.port=" + port,
                "--spring.datasource.url=" + databaseUrl));
        arguments.addAll(List.of(secretArguments));

        Throwable failure = catchThrowable(() -> {
            SpringApplication application = new SpringApplication(OnlineHomeworkApplication.class);
            application.setLogStartupInfo(false);
            try (ConfigurableApplicationContext ignored = application.run(arguments.toArray(String[]::new))) {
                // A successful context would mean the startup guard failed open.
            }
        });

        Throwable root = rootCause(failure);
        assertThat(root).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedError)
                .hasMessageNotContaining("password");
        String outputText = output.getOut() + output.getErr();
        assertThat(outputText).doesNotContain(VALID_STUDENT_SECRET)
                .doesNotContain(VALID_TEACHER_SECRET)
                .doesNotContain(WEAK_SECRET);

        try (ServerSocket probe = new ServerSocket(port)) {
            assertThat(probe.isBound()).isTrue();
        }

        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from information_schema.tables where lower(table_name) = 'user_accounts'");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Throwable rootCause(Throwable failure) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }
}
