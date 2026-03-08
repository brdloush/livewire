package net.brdloush.livewire;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;

/**
 * Lifecycle bean that bootstraps the Livewire Clojure namespaces and
 * manages the nREPL server alongside the Spring application context.
 *
 * On startup  : requires net.brdloush.livewire.boot, then calls boot/start!
 * On shutdown : calls boot/stop!, shuts down the Clojure agent thread pool
 */
public class LivewireBootstrapBean implements InitializingBean, DisposableBean {

    private final ApplicationContext applicationContext;
    private final int port;

    private IFn stopFn;

    public LivewireBootstrapBean(ApplicationContext applicationContext, int port) {
        this.applicationContext = applicationContext;
        this.port = port;
    }

    @Override
    public void afterPropertiesSet() {
        // Load the boot namespace (which in turn requires core)
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("net.brdloush.livewire.boot"));

        // Grab and invoke start!
        IFn startFn = Clojure.var("net.brdloush.livewire.boot", "start!");
        startFn.invoke(applicationContext, (long) port);

        // Cache stop! so destroy() doesn't need to look it up
        stopFn = Clojure.var("net.brdloush.livewire.boot", "stop!");
    }

    @Override
    public void destroy() {
        if (stopFn != null) {
            stopFn.invoke();
        }
        // Clojure's agent thread pool must be shut down explicitly,
        // otherwise the JVM won't exit cleanly.
        clojure.lang.Agent.soloExecutor.shutdown();
    }
}
