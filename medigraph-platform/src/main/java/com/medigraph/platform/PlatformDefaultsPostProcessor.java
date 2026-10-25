package com.medigraph.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Adds {@code medigraph-platform-defaults.properties} as the lowest-priority property source, so every service gets
 * the same actuator/probe/metrics/tracing/problem-detail defaults and can still override any of them.
 */
public final class PlatformDefaultsPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE = "medigraphPlatformDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (env.getPropertySources().contains(SOURCE)) {
            return;
        }
        try {
            var props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("medigraph-platform-defaults.properties"));
            env.getPropertySources().addLast(new org.springframework.core.env.PropertiesPropertySource(SOURCE, props));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
