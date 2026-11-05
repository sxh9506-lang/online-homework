package com.onlinehomework.common.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public final class DemoModeStartupGuard implements BeanFactoryPostProcessor, EnvironmentAware {
    private static final Set<String> ALLOWED_DEMO_ENVIRONMENTS = Set.of("test");
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (environment == null) {
            throw new IllegalStateException("Application environment is unavailable; refusing to start.");
        }
        if (!environment.getProperty("app.demo-mode", Boolean.class, false)) {
            return;
        }

        String appEnvironment = environment.getProperty("app.environment", "production");
        String normalizedEnvironment = appEnvironment.trim().toLowerCase(Locale.ROOT);
        boolean productionProfileActive = Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(PRODUCTION_PROFILES::contains);
        if (!ALLOWED_DEMO_ENVIRONMENTS.contains(normalizedEnvironment) || productionProfileActive) {
            throw new IllegalStateException(
                    "DEMO_MODE=true is allowed only with APP_ENV=test "
                            + "and without an active prod/production Spring profile.");
        }

        validateSecret("DEMO_TEACHER_PASSWORD", environment.getProperty("app.demo.teacher-password", ""));
        validateSecret("DEMO_STUDENT_PASSWORD", environment.getProperty("app.demo.student-password", ""));
    }

    private static void validateSecret(String name, String password) {
        int characters = password.codePointCount(0, password.length());
        int utf8Bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.isBlank() || characters < 16 || utf8Bytes > 72) {
            throw new IllegalStateException(
                    name + " must be injected as a Secret with at least 16 characters and at most 72 UTF-8 bytes.");
        }
    }
}
