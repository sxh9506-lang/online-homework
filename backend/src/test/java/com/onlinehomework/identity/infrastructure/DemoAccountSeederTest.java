package com.onlinehomework.identity.infrastructure;

import com.onlinehomework.identity.domain.UserRole;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DemoAccountSeederTest {
    @Test
    void refusesToSeedWhenSecretIsMissingBeforeAnyDatabaseOrEncoderAccess() {
        AtomicInteger databaseCalls = new AtomicInteger();
        AtomicInteger encoderCalls = new AtomicInteger();
        UserAccountRepository users = mockRepository(databaseCalls);
        PasswordEncoder passwordEncoder = mockEncoder(encoderCalls);
        String otherSecret = randomSecret();
        DemoAccountSeeder seeder = new DemoAccountSeeder(users, passwordEncoder, Clock.systemUTC(), "", otherSecret);

        Throwable failure = catchFailure(() -> seeder.run(new DefaultApplicationArguments()));
        assertThat(databaseCalls.get()).as("missing Secret rejected before database access").isZero();
        assertThat(encoderCalls.get()).as("missing Secret rejected before password encoding").isZero();
        assertFailureCode(failure, "DEMO_TEACHER_PASSWORD");
        assertNoSensitiveText(failure.getMessage(), otherSecret);
    }

    @Test
    void rejectsWeakSecretWithoutEchoingItOrAccessingDatabase() {
        AtomicInteger databaseCalls = new AtomicInteger();
        AtomicInteger encoderCalls = new AtomicInteger();
        UserAccountRepository users = mockRepository(databaseCalls);
        PasswordEncoder passwordEncoder = mockEncoder(encoderCalls);
        String weakSecret = UUID.randomUUID().toString().substring(0, 8);
        DemoAccountSeeder seeder = new DemoAccountSeeder(users, passwordEncoder, Clock.systemUTC(), weakSecret,
                randomSecret());

        Throwable failure = catchFailure(() -> seeder.run(new DefaultApplicationArguments()));
        assertThat(databaseCalls.get()).as("weak Secret rejected before database access").isZero();
        assertThat(encoderCalls.get()).as("weak Secret rejected before password encoding").isZero();
        assertFailureCode(failure, "DEMO_TEACHER_PASSWORD");
        assertNoSensitiveText(failure.getMessage(), weakSecret);
    }

    @Test
    void rejectsNonReservedSeedEmailBeforeDatabaseOrEncoderAccess() {
        AtomicInteger databaseCalls = new AtomicInteger();
        AtomicInteger encoderCalls = new AtomicInteger();
        UserAccountRepository users = mockRepository(databaseCalls);
        PasswordEncoder passwordEncoder = mockEncoder(encoderCalls);
        DemoAccountSeeder seeder = new DemoAccountSeeder(users, passwordEncoder, Clock.systemUTC(), randomSecret(),
                randomSecret());

        Throwable failure = catchFailure(() -> seeder.seedAccount(
                "fictional-student@example.test", "Demo Student", UserRole.STUDENT, randomSecret()));
        assertThat(databaseCalls.get()).as("non-reserved email rejected before database access").isZero();
        assertThat(encoderCalls.get()).as("non-reserved email rejected before password encoding").isZero();
        assertFailureCode(failure, ".invalid domain");
    }

    private static UserAccountRepository mockRepository(AtomicInteger calls) {
        return mock(UserAccountRepository.class, invocation -> {
            calls.incrementAndGet();
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static PasswordEncoder mockEncoder(AtomicInteger calls) {
        return mock(PasswordEncoder.class, invocation -> {
            calls.incrementAndGet();
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static Throwable catchFailure(Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("Expected the invalid demo seed input to be rejected.");
    }

    private static void assertFailureCode(Throwable failure, String expectedText) {
        if (!(failure instanceof IllegalStateException) || failure.getMessage() == null
                || !failure.getMessage().contains(expectedText)) {
            throw new AssertionError("Demo seed rejected an input without the expected safe diagnostic.");
        }
    }

    private static void assertNoSensitiveText(String output, String... values) {
        if (output == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isEmpty() && output.contains(value)) {
                throw new AssertionError("Demo seed diagnostic contained a sensitive value.");
            }
        }
    }

    private static String randomSecret() {
        return UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
    }
}
