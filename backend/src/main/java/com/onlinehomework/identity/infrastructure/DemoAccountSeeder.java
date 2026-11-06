package com.onlinehomework.identity.infrastructure;

import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.domain.UserRole;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app", name = "demo-mode", havingValue = "true")
public class DemoAccountSeeder implements ApplicationRunner {
    public static final String TEACHER_EMAIL = "demo-teacher@online-homework.invalid";
    public static final String STUDENT_EMAIL = "demo-student@online-homework.invalid";

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String teacherPassword;
    private final String studentPassword;

    public DemoAccountSeeder(UserAccountRepository users,
                             PasswordEncoder passwordEncoder,
                             Clock clock,
                             @Value("${app.demo.teacher-password:}") String teacherPassword,
                             @Value("${app.demo.student-password:}") String studentPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.teacherPassword = teacherPassword;
        this.studentPassword = studentPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        validateSecret("DEMO_TEACHER_PASSWORD", teacherPassword);
        validateSecret("DEMO_STUDENT_PASSWORD", studentPassword);
        seedAccounts(List.of(
                new SeedAccountDefinition(TEACHER_EMAIL, "Demo Teacher", UserRole.TEACHER, teacherPassword),
                new SeedAccountDefinition(STUDENT_EMAIL, "Demo Student", UserRole.STUDENT, studentPassword)));
    }

    @Transactional
    public void seedAccounts(List<SeedAccountDefinition> accounts) {
        for (SeedAccountDefinition account : accounts) {
            seedAccount(account.email(), account.displayName(), account.role(), account.password());
        }
    }

    void seedAccount(String email, String displayName, UserRole role, String password) {
        validateSeedEmail(email);

        UserAccount existing = users.findByNormalizedEmail(UserAccount.normalizeEmail(email)).orElse(null);
        if (existing != null) {
            boolean isExpectedDemoAccount = existing.getRole() == role
                    && existing.isEnabled()
                    && existing.isEmailVerified()
                    && passwordEncoder.matches(password, existing.getPasswordHash());
            if (!isExpectedDemoAccount) {
                throw new IllegalStateException(
                        "Refusing to alter a reserved demo account that does not match the injected credentials.");
            }
            return;
        }

        UserAccount account = new UserAccount(UUID.randomUUID(), email, passwordEncoder.encode(password),
                role, true, true, displayName, clock.instant());
        users.saveAndFlush(account);
    }

    static void validateSeedEmail(String email) {
        String normalizedEmail = UserAccount.normalizeEmail(email);
        int separator = normalizedEmail.lastIndexOf('@');
        boolean reservedDomain = separator > 0
                && separator < normalizedEmail.length() - 1
                && normalizedEmail.substring(separator + 1).endsWith(".invalid");
        if (!reservedDomain) {
            throw new IllegalStateException("Demo seed email addresses must use the reserved .invalid domain.");
        }
    }

    private static void validateSecret(String name, String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(name + " must be injected as an environment Secret when DEMO_MODE=true.");
        }
        int characters = password.codePointCount(0, password.length());
        int utf8Bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (characters < 16 || utf8Bytes > 72) {
            throw new IllegalStateException(name + " must contain at least 16 characters and at most 72 UTF-8 bytes.");
        }
    }

    public record SeedAccountDefinition(String email, String displayName, UserRole role, String password) {
    }
}
