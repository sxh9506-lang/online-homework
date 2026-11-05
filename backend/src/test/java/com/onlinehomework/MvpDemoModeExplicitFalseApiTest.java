package com.onlinehomework;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "app.demo-mode=false",
        "spring.datasource.url=jdbc:h2:mem:demo_mode_explicit_false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MvpDemoModeExplicitFalseApiTest extends DemoModeDisabledApiAssertions {
}
