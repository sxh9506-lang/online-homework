package com.onlinehomework;

import tools.jackson.databind.ObjectMapper;
import com.onlinehomework.identity.application.VerificationEmailSender;
import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.domain.UserRole;
import com.onlinehomework.identity.infrastructure.DemoAccountSeeder;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MvpDemoModeApiTest.DemoTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class MvpDemoModeApiTest {
    private static final String TEACHER_PASSWORD = randomSecret();
    private static final String STUDENT_PASSWORD = randomSecret();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private CapturingVerificationEmailSender emailSender;

    @DynamicPropertySource
    static void demoEnvironmentProperties(DynamicPropertyRegistry registry) {
        registry.add("DEMO_MODE", () -> "true");
        registry.add("APP_ENV", () -> "test");
        registry.add("DEMO_TEACHER_PASSWORD", () -> TEACHER_PASSWORD);
        registry.add("DEMO_STUDENT_PASSWORD", () -> STUDENT_PASSWORD);
        registry.add("CORS_ALLOWED_ORIGINS", () -> "https://web.example.test,https://staff.example.test");
    }

    @BeforeEach
    void resetTestMailAndClock() {
        emailSender.clear();
        clock.set(Instant.now());
    }

    @Test
    void seedsOnlyFictionalVerifiedAccountsWithPasswordHashes() {
        UserAccount teacher = users.findByNormalizedEmail(UserAccount.normalizeEmail(DemoAccountSeeder.TEACHER_EMAIL))
                .orElseThrow();
        UserAccount student = users.findByNormalizedEmail(UserAccount.normalizeEmail(DemoAccountSeeder.STUDENT_EMAIL))
                .orElseThrow();

        assertThat(teacher.getEmail()).endsWith(".invalid");
        assertThat(student.getEmail()).endsWith(".invalid");
        assertThat(teacher.getRole()).isEqualTo(UserRole.TEACHER);
        assertThat(student.getRole()).isEqualTo(UserRole.STUDENT);
        assertThat(teacher.isEmailVerified()).isTrue();
        assertThat(student.isEmailVerified()).isTrue();
        assertThat(teacher.getPasswordHash()).isNotEqualTo(TEACHER_PASSWORD);
        assertThat(student.getPasswordHash()).isNotEqualTo(STUDENT_PASSWORD);
        assertThat(passwordEncoder.matches(TEACHER_PASSWORD, teacher.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(STUDENT_PASSWORD, student.getPasswordHash())).isTrue();
    }

    @Test
    void seededAccountsWorkWithoutSmtpAndRegistrationStillRequiresEmailVerification(CapturedOutput output)
            throws Exception {
        UserAccount teacher = users.findByNormalizedEmail(UserAccount.normalizeEmail(DemoAccountSeeder.TEACHER_EMAIL))
                .orElseThrow();
        MvcResult seededLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", DemoAccountSeeder.TEACHER_EMAIL,
                                "password", TEACHER_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("TEACHER"))
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();
        assertThat(seededLogin.getResponse().getContentAsString())
                .doesNotContain(TEACHER_PASSWORD).doesNotContain(teacher.getPasswordHash());
        assertThat(emailSender.messageCount()).isZero();

        Instant start = clock.instant();
        String expiredEmail = testEmail("expired");
        String expiredPassword = randomSecret();
        String expiredResponse = register(expiredEmail, expiredPassword);
        String expiredToken = emailSender.tokenFor(expiredEmail);
        UserAccount expiredUser = findUser(expiredEmail);
        assertThat(expiredUser.isEmailVerified()).isFalse();
        assertThat(expiredResponse).doesNotContain(expiredPassword).doesNotContain(expiredToken);

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("token", "invalid-token"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_TOKEN_INVALID"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", expiredEmail,
                                "password", expiredPassword))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());

        clock.set(start.plus(Duration.ofMinutes(31)));
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("token", expiredToken))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_TOKEN_EXPIRED"));
        assertThat(findUser(expiredEmail).isEmailVerified()).isFalse();

        String validEmail = testEmail("verified");
        String validPassword = randomSecret();
        String validResponse = register(validEmail, validPassword);
        String validToken = emailSender.tokenFor(validEmail);
        String otherPendingEmail = testEmail("still-pending");
        String otherPendingPassword = randomSecret();
        register(otherPendingEmail, otherPendingPassword);
        String otherPendingToken = emailSender.tokenFor(otherPendingEmail);

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("token", validToken))))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(verified.getResponse().getContentAsString()).isEmpty();
        assertThat(findUser(validEmail).isEmailVerified()).isTrue();
        assertThat(findUser(otherPendingEmail).isEmailVerified()).isFalse();
        assertThat(findUser(expiredEmail).isEmailVerified()).isFalse();
        assertThat(validResponse).doesNotContain(validPassword).doesNotContain(validToken);

        MvcResult verifiedLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", validEmail,
                                "password", validPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        String loginBody = verifiedLogin.getResponse().getContentAsString();
        assertThat(loginBody).doesNotContain(validPassword)
                .doesNotContain(findUser(validEmail).getPasswordHash())
                .doesNotContain(validToken);
        assertNoSecretsInOutput(output.getOut() + output.getErr(), TEACHER_PASSWORD, STUDENT_PASSWORD,
                expiredPassword, validPassword, otherPendingPassword, expiredToken, validToken, otherPendingToken,
                teacher.getPasswordHash(), findUser(validEmail).getPasswordHash());
    }

    @Test
    void corsAllowsConfiguredOriginsAndRejectsOthers() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://web.example.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://web.example.test"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://unlisted.example.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private String register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", email,
                                "password", password,
                                "displayName", "Fictional Test Student"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.verificationRequired").value(true))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private UserAccount findUser(String email) {
        return users.findByNormalizedEmail(UserAccount.normalizeEmail(email)).orElseThrow();
    }

    private static String testEmail(String label) {
        return label + "-" + UUID.randomUUID() + "@example.test";
    }

    private static void assertNoSecretsInOutput(String output, String... secrets) {
        for (String secret : secrets) {
            assertThat(output).doesNotContain(secret);
        }
    }

    private static String randomSecret() {
        return "Generated-Test-" + UUID.randomUUID() + "-Only";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DemoTestConfiguration {
        @Bean
        @Primary
        MutableTestClock demoTestClock() {
            return new MutableTestClock(Instant.now());
        }

        @Bean
        @Primary
        CapturingVerificationEmailSender capturingVerificationEmailSender() {
            return new CapturingVerificationEmailSender();
        }
    }

    static class CapturingVerificationEmailSender implements VerificationEmailSender {
        private final Map<String, String> tokensByRecipient = new ConcurrentHashMap<>();

        @Override
        public void send(String recipient, String token) {
            tokensByRecipient.put(recipient, token);
        }

        String tokenFor(String recipient) {
            return tokensByRecipient.get(recipient);
        }

        int messageCount() {
            return tokensByRecipient.size();
        }

        void clear() {
            tokensByRecipient.clear();
        }
    }

    static class MutableTestClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableTestClock(Instant initial) {
            this(new AtomicReference<>(initial));
        }

        private MutableTestClock(AtomicReference<Instant> current) {
            this.current = current;
        }

        void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableTestClock(current);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
