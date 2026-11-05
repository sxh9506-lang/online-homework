package com.onlinehomework;

import tools.jackson.databind.ObjectMapper;
import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.infrastructure.DemoAccountSeeder;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class DemoModeDisabledApiAssertions {
    @Autowired
    private Environment environment;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void disabledModeHasNoSeedsAndDemoCredentialsCannotAuthenticate() throws Exception {
        assertThat(environment.getProperty("app.demo-mode", Boolean.class, false)).isFalse();
        assertThat(users.count()).isZero();
        assertThat(users.findByNormalizedEmail(
                UserAccount.normalizeEmail(DemoAccountSeeder.TEACHER_EMAIL))).isEmpty();
        assertThat(users.findByNormalizedEmail(
                UserAccount.normalizeEmail(DemoAccountSeeder.STUDENT_EMAIL))).isEmpty();

        String attemptedPassword = "Unseeded-Test-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", DemoAccountSeeder.TEACHER_EMAIL,
                                "password", attemptedPassword))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(attemptedPassword);
    }
}
