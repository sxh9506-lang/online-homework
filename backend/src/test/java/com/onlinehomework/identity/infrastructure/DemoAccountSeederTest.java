package com.onlinehomework.identity.infrastructure;

import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DemoAccountSeederTest {
    @Test
    void refusesToSeedWhenSecretIsMissingBeforeAnyDatabaseOrEncoderAccess() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        String otherSecret = "A-strong-student-secret-2026";
        DemoAccountSeeder seeder = new DemoAccountSeeder(users, passwordEncoder, Clock.systemUTC(), "", otherSecret);

        Throwable failure = catchThrowable(() -> seeder.run(new DefaultApplicationArguments()));
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEMO_TEACHER_PASSWORD");
        assertThat(failure.getMessage()).doesNotContain(otherSecret);
        verifyNoInteractions(users, passwordEncoder);
    }

    @Test
    void rejectsWeakSecretWithoutEchoingIt() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        String weakSecret = "short-password";
        DemoAccountSeeder seeder = new DemoAccountSeeder(users, passwordEncoder, Clock.systemUTC(), weakSecret, weakSecret);

        Throwable failure = catchThrowable(() -> seeder.run(new DefaultApplicationArguments()));
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEMO_TEACHER_PASSWORD");
        assertThat(failure.getMessage()).doesNotContain(weakSecret);
        verifyNoInteractions(users, passwordEncoder);
    }
}
