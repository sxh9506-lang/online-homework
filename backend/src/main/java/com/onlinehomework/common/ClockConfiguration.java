package com.onlinehomework.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfiguration {
    @Bean
    Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
