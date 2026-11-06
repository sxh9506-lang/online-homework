package com.onlinehomework;

import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Import(ProdProfileTestConfiguration.class)
class DemoModeProdProfileUnsetTest {
    @Autowired private Environment environment;
    @Autowired private UserAccountRepository users;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;

    @Test
    void unsetDemoModeStartsFullProdProfileAndDefaultsOff() throws Exception {
        assertThat(Arrays.asList(environment.getActiveProfiles())).contains("prod");
        assertThat(environment.getProperty("app.environment")).isEqualTo("production");
        assertThat(environment.getProperty("DEMO_MODE")).isNull();
        assertThat(environment.getProperty("app.demo-mode", Boolean.class, true)).isFalse();
        assertThat(port).isBetween(1, 65_535);
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class)).isEqualTo(1);
        assertThat(users.count()).isZero();

        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }
}
