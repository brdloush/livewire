package net.brdloush.livewire;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Spring Boot auto-configuration for Livewire.
 *
 * Activated automatically when:
 *   1. The library JAR is on the classpath, AND
 *   2. The active Spring profile includes "dev"
 *
 * The nREPL port is configurable via the property:
 *   livewire.nrepl.port (default: 7888)
 */
@AutoConfiguration
@Profile("dev")
public class LivewireAutoConfiguration {

    @Bean
    public LivewireBootstrapBean livewireBootstrapBean(
            ApplicationContext applicationContext,
            Environment environment) {

        int port = environment.getProperty("livewire.nrepl.port", Integer.class, 7888);
        return new LivewireBootstrapBean(applicationContext, port);
    }
}
