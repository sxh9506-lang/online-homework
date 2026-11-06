package com.onlinehomework;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.infrastructure.DemoAccountSeeder;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "DEMO_TEACHER_PASSWORD", matches = ".{16,72}")
@EnabledIfEnvironmentVariable(named = "DEMO_STUDENT_PASSWORD", matches = ".{16,72}")
@SpringBootTest(properties = {
        "app.demo-mode=true",
        "spring.datasource.url=jdbc:h2:mem:demo_ci_secret_injection;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BackendContextSmokeTest.TestStorageConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class DemoCiSecretInjectionTest {
    @Autowired private Environment environment;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MockMvc mockMvc;

    @Test
    void processEnvironmentSecretsReachSeederAndRemainHashedAndPrivate(CapturedOutput output) throws Exception {
        String teacherSecret = System.getenv("DEMO_TEACHER_PASSWORD");
        String studentSecret = System.getenv("DEMO_STUDENT_PASSWORD");
        UserAccount teacher = users.findByNormalizedEmail(
                UserAccount.normalizeEmail(DemoAccountSeeder.TEACHER_EMAIL)).orElseThrow();
        UserAccount student = users.findByNormalizedEmail(
                UserAccount.normalizeEmail(DemoAccountSeeder.STUDENT_EMAIL)).orElseThrow();
        String teacherHash = teacher.getPasswordHash();
        String studentHash = student.getPasswordHash();

        require(teacherSecret.equals(environment.getProperty("app.demo.teacher-password")),
                "CI teacher Secret did not reach Spring configuration.");
        require(studentSecret.equals(environment.getProperty("app.demo.student-password")),
                "CI student Secret did not reach Spring configuration.");
        require(isOnlyStoredAsMatchingHash(teacherSecret, teacherHash)
                        && isOnlyStoredAsMatchingHash(studentSecret, studentHash),
                "CI Secret injection did not produce one-way password hashes.");

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", DemoAccountSeeder.TEACHER_EMAIL,
                                "password", teacherSecret))))
                .andExpect(status().isOk())
                .andReturn();
        String response = login.getResponse().getContentAsString();
        JsonNode responseUser = objectMapper.readTree(response).path("user");
        require(!responseUser.has("password") && !responseUser.has("passwordHash"),
                "CI-seeded login response contains a credential field.");
        require(!containsAny(response, teacherSecret, studentSecret, teacherHash, studentHash),
                "CI-seeded login response contains a Secret or password hash.");
        require(!containsAny(output.getOut() + output.getErr(),
                        teacherSecret, studentSecret, teacherHash, studentHash),
                "CI logs contain a Secret or password hash.");
    }

    private boolean isOnlyStoredAsMatchingHash(String secret, String hash) {
        return hash != null && !hash.equals(secret) && passwordEncoder.matches(secret, hash);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty() && text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String safeMessage) {
        if (!condition) {
            throw new AssertionError(safeMessage);
        }
    }
}
