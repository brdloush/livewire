package net.brdloush.livewire;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Registers the Livewire SQL tracer as Hibernate's StatementInspector via
 * an early-bound environment property, compatible with all Spring Boot versions.
 *
 * Works by injecting:
 *   spring.jpa.properties.hibernate.session_factory.statement_inspector
 *     = net.brdloush.livewire.LivewireSqlTracer
 *
 * into the environment before the Spring context is built, so that
 * Spring Boot's JPA auto-configuration picks it up when constructing the
 * SessionFactory. This replaces the old HibernatePropertiesCustomizer approach
 * which was removed in Spring Boot 4.
 *
 * Only activates when livewire.enabled=true and Hibernate is on the classpath.
 */
public class LivewireEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String STATEMENT_INSPECTOR_PROPERTY =
            "spring.jpa.properties.hibernate.session_factory.statement_inspector";

    private static final String LIVEWIRE_ENABLED = "livewire.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!"true".equalsIgnoreCase(environment.getProperty(LIVEWIRE_ENABLED))) {
            return;
        }

        try {
            Class.forName("org.hibernate.resource.jdbc.spi.StatementInspector");
        } catch (ClassNotFoundException e) {
            return;
        }

        // addLast = lowest priority, so any user-defined value in application
        // properties still wins naturally.
        environment.getPropertySources().addLast(
                new MapPropertySource(
                        "livewire-hibernate-tracing",
                        Map.of(STATEMENT_INSPECTOR_PROPERTY, LivewireSqlTracer.class.getName())
                )
        );
    }
}
