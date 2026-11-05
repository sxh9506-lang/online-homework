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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import(BackendContextSmokeTest.TestStorageConfiguration.class)
class BackendContextSmokeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    void flywayCreatesMvpSchemaAndJpaMappingsStart() {
        assertEquals(0, jdbc.queryForObject("select count(*) from user_accounts", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from classrooms", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from assignments", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from upload_intents", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from submission_versions", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from grade_revisions", Integer.class));
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
