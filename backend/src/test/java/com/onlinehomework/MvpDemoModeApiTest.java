package com.onlinehomework;

import tools.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
    private DemoAccountSeeder demoAccountSeeder;

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

        assertDemoEmail(teacher.getEmail());
        assertDemoEmail(student.getEmail());
        long reservedDomainAccounts = users.findAll().stream()
                .filter(account -> UserAccount.normalizeEmail(account.getEmail()).endsWith(".invalid"))
                .count();
        assertThat(reservedDomainAccounts).as("DEMO-02 only seeds reserved identities").isEqualTo(2L);
        assertThat(teacher.getRole()).isEqualTo(UserRole.TEACHER);
        assertThat(student.getRole()).isEqualTo(UserRole.STUDENT);
        assertThat(teacher.isEmailVerified()).isTrue();
        assertThat(student.isEmailVerified()).isTrue();
        assertThat(teacher.getPasswordHash() != null && !teacher.getPasswordHash().equals(TEACHER_PASSWORD))
                .as("DEMO-03 teacher seed stores a one-way hash").isTrue();
        assertThat(student.getPasswordHash() != null && !student.getPasswordHash().equals(STUDENT_PASSWORD))
                .as("DEMO-03 student seed stores a one-way hash").isTrue();
        assertThat(passwordEncoder.matches(TEACHER_PASSWORD, teacher.getPasswordHash()))
                .as("DEMO-03 teacher hash matches only in process").isTrue();
        assertThat(passwordEncoder.matches(STUDENT_PASSWORD, student.getPasswordHash()))
                .as("DEMO-03 student hash matches only in process").isTrue();
    }

    @Test
    void laterNonReservedSeedRollsBackEarlierFlushedAccountInTheRealDatabase() {
        long countBefore = users.count();
        String validEmail = "rollback-" + UUID.randomUUID() + "@online-homework.invalid";
        String invalidEmail = "rollback-" + UUID.randomUUID() + "@example.test";
        List<DemoAccountSeeder.SeedAccountDefinition> batch = List.of(
                new DemoAccountSeeder.SeedAccountDefinition(
                        validEmail, "Fictional rollback student", UserRole.STUDENT, randomSecret()),
                new DemoAccountSeeder.SeedAccountDefinition(
                        invalidEmail, "Rejected fictional student", UserRole.STUDENT, randomSecret()));

        Throwable failure = catchThrowable(() -> demoAccountSeeder.seedAccounts(batch));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".invalid domain");
        assertThat(users.findByNormalizedEmail(UserAccount.normalizeEmail(validEmail)))
                .as("DEMO-02 must roll back the account flushed before the invalid address")
                .isEmpty();
        assertThat(users.count())
                .as("DEMO-02 transaction leaves the pre-existing database state unchanged")
                .isEqualTo(countBefore);
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
                .andReturn();
        String seededLoginBody = seededLogin.getResponse().getContentAsString();
        assertLoginResponseOmitsCredentials(seededLoginBody, "DEMO-03 seeded login");
        assertNoSensitiveText(seededLoginBody, TEACHER_PASSWORD, teacher.getPasswordHash());
        assertThat(emailSender.messageCount()).isZero();

        Instant start = clock.instant();
        String expiredEmail = testEmail("expired");
        String expiredPassword = randomSecret();
        String expiredResponse = register(expiredEmail, expiredPassword);
        String expiredToken = emailSender.tokenFor(expiredEmail);
        UserAccount expiredUser = findUser(expiredEmail);
        assertThat(expiredUser.isEmailVerified()).isFalse();
        assertNoSensitiveText(expiredResponse, expiredPassword, expiredToken, expiredUser.getPasswordHash());

        MvcResult invalidVerification = mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("token", "invalid-token"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_TOKEN_INVALID"))
                .andReturn();
        assertNoSensitiveText(invalidVerification.getResponse().getContentAsString(), "invalid-token");
        MvcResult unverifiedLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", expiredEmail,
                                "password", expiredPassword))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andReturn();
        assertNoSensitiveText(unverifiedLogin.getResponse().getContentAsString(),
                expiredPassword, expiredUser.getPasswordHash());
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());

        clock.set(start.plus(Duration.ofMinutes(31)));
        MvcResult expiredVerification = mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("token", expiredToken))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_TOKEN_EXPIRED"))
                .andReturn();
        assertNoSensitiveText(expiredVerification.getResponse().getContentAsString(),
                expiredToken, expiredPassword, expiredUser.getPasswordHash());
        assertThat(findUser(expiredEmail).isEmailVerified()).isFalse();

        String validEmail = testEmail("verified");
        String validPassword = randomSecret();
        String validResponse = register(validEmail, validPassword);
        String validToken = emailSender.tokenFor(validEmail);
        String otherPendingEmail = testEmail("still-pending");
        String otherPendingPassword = randomSecret();
        String otherPendingResponse = register(otherPendingEmail, otherPendingPassword);
        String otherPendingToken = emailSender.tokenFor(otherPendingEmail);
        UserAccount otherPendingUser = findUser(otherPendingEmail);
        assertNoSensitiveText(otherPendingResponse, otherPendingPassword, otherPendingToken,
                otherPendingUser.getPasswordHash());

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("token", validToken))))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(verified.getResponse().getContentAsString().isEmpty())
                .as("DEMO-05 verification returns no token or credential body").isTrue();
        assertThat(findUser(validEmail).isEmailVerified()).isTrue();
        assertThat(findUser(otherPendingEmail).isEmailVerified()).isFalse();
        assertThat(findUser(expiredEmail).isEmailVerified()).isFalse();
        assertNoSensitiveText(validResponse, validPassword, validToken, findUser(validEmail).getPasswordHash());

        MvcResult verifiedLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", validEmail,
                                "password", validPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andReturn();

        String loginBody = verifiedLogin.getResponse().getContentAsString();
        assertLoginResponseOmitsCredentials(loginBody, "DEMO-05 verified login");
        assertNoSensitiveText(loginBody, validPassword, findUser(validEmail).getPasswordHash(), validToken);
        assertNoSecretsInOutput(output.getOut() + output.getErr(), TEACHER_PASSWORD, STUDENT_PASSWORD,
                expiredPassword, validPassword, otherPendingPassword, expiredToken, validToken, otherPendingToken,
                teacher.getPasswordHash(), expiredUser.getPasswordHash(), findUser(validEmail).getPasswordHash(),
                otherPendingUser.getPasswordHash());
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
        String responseBody = result.getResponse().getContentAsString();
        assertRegistrationResponseOmitsCredentials(responseBody);
        assertNoSensitiveText(responseBody, password);
        return responseBody;
    }

    private void assertLoginResponseOmitsCredentials(String responseBody, String testCase) throws Exception {
        JsonNode user = objectMapper.readTree(responseBody).path("user");
        assertThat(user.has("password")).as(testCase + " omits password").isFalse();
        assertThat(user.has("passwordHash")).as(testCase + " omits password hash").isFalse();
    }

    private void assertRegistrationResponseOmitsCredentials(String responseBody) throws Exception {
        JsonNode registration = objectMapper.readTree(responseBody);
        assertThat(registration.has("password")).as("DEMO-05 registration omits password").isFalse();
        assertThat(registration.has("passwordHash")).as("DEMO-05 registration omits password hash").isFalse();
    }

    private UserAccount findUser(String email) {
        return users.findByNormalizedEmail(UserAccount.normalizeEmail(email)).orElseThrow();
    }

    private static String testEmail(String label) {
        return label + "-" + UUID.randomUUID() + "@example.test";
    }

    private static void assertNoSecretsInOutput(String output, String... secrets) {
        assertNoSensitiveText(output, secrets);
    }

    private static void assertNoSensitiveText(String text, String... sensitiveValues) {
        if (text == null) {
            return;
        }
        for (String value : sensitiveValues) {
            if (value != null && !value.isEmpty() && text.contains(value)) {
                throw new AssertionError("DEMO API output contained a sensitive value.");
            }
        }
    }

    private static void assertDemoEmail(String email) {
        String normalized = UserAccount.normalizeEmail(email);
        int separator = normalized.lastIndexOf('@');
        if (separator < 1 || !normalized.substring(separator + 1).endsWith(".invalid")) {
            throw new AssertionError("DEMO-02 found an email outside the reserved test domain.");
        }
    }

    private static String randomSecret() {
        return UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
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
