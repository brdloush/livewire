package net.brdloush.livewire.attach;

import java.util.Collections;
import java.util.List;

/**
 * Wraps the JDK Attach API to list and annotate running JVMs.
 * <p>
 * This is a stub — full VirtualMachine.list() scanning and Spring Boot
 * version detection are implemented in Step 4.
 */
public class JvmScanner {

    /**
     * Returns a list of human-readable JVM descriptions for display in attach.jsh.
     * Each entry is a formatted string: "pid <pid>  <mainClass>  (<details>)".
     * <p>
     * TODO (Step 4): use com.sun.tools.attach.VirtualMachine.list(), detect
     *                Spring Boot presence via JMX system properties, filter
     *                non-attachable entries gracefully.
     */
    public static List<String> listJvms() {
        return Collections.emptyList();
    }
}
