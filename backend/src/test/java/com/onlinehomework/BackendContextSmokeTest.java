package com.onlinehomework;

import com.onlinehomework.common.application.FileMetadataQuery;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import com.onlinehomework.submission.application.CurrentPublishedGradeQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import(BackendContextSmokeTest.TestStorageConfiguration.class)
class BackendContextSmokeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    void flywayCreatesMvpSchemaAndJpaMappingsStart() {
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class));

        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "select upper(table_name) from information_schema.tables "
                        + "where upper(table_schema) = 'PUBLIC' and upper(table_type) = 'BASE TABLE'",
                String.class));
        assertTrue(tables.containsAll(Set.of(
                        "USER_ACCOUNTS",
                        "EMAIL_VERIFICATION_TOKENS",
                        "CLASSROOMS",
                        "CLASS_MEMBERSHIPS",
                        "ASSIGNMENTS",
                        "UPLOAD_INTENTS",
                        "SUBMISSION_VERSIONS",
                        "GRADE_REVISIONS",
                        "FLYWAY_SCHEMA_HISTORY")),
                "Flyway should create all MVP tables; found: " + tables);
    }

    @Test
    void onlyOnePublishedGradeQueryImplementationIsRegistered() {
        assertEquals(1, context.getBeansOfType(CurrentPublishedGradeQuery.class).size());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {
        @Bean
        @Primary
        PrivateObjectStorage testPrivateObjectStorage() {
            return mock(PrivateObjectStorage.class);
        }
    }
}
