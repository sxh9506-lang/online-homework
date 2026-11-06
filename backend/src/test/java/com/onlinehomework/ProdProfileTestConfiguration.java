package com.onlinehomework;

import com.onlinehomework.file.storage.PrivateObjectStorage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
class ProdProfileTestConfiguration {
    @Bean
    @Primary
    PrivateObjectStorage testPrivateObjectStorage() {
        return mock(PrivateObjectStorage.class);
    }
}
