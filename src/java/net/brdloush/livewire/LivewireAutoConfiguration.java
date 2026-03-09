package net.brdloush.livewire;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;

/**
 * Spring Boot auto-configuration for Livewire.

 *
 * Activated automatically when:
 *   1. The library JAR is on the classpath, AND
 *   2. The property livewire.enabled=true is set
 *
 * Add to whichever local/dev properties file your project uses, e.g.:
 *   # application-local.properties
 *   livewire.enabled=true
 *
 * The nREPL port is configurable via the property:
 *   livewire.nrepl.port (default: 7888)
 */
@AutoConfiguration
@ConditionalOnProperty(name = "livewire.enabled", havingValue = "true")
public class LivewireAutoConfiguration {

    @Bean
    public LivewireBootstrapBean livewireBootstrapBean(
            ApplicationContext applicationContext,
            Environment environment) {

        int port = environment.getProperty("livewire.nrepl.port", Integer.class, 7888);
        return new LivewireBootstrapBean(applicationContext, port);
    }

    @Bean
    public HibernatePropertiesCustomizer livewireHibernateCustomizer() {
        return (properties) -> properties.put("hibernate.session_factory.statement_inspector", new LivewireSqlTracer());
    }
}
