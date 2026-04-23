package net.brdloush.livewire.attach;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Loaded into the target JVM via VirtualMachine.loadAgent().
 * <p>
 * This is a stub — full ApplicationContext discovery and nREPL bootstrap
 * are implemented in Step 4.
 */
public class LivewireAgent {

    /** Entry point used by dynamic attach (VirtualMachine.loadAgent). */
    public static void agentmain(String args, Instrumentation inst) {
        System.out.println("[livewire] agent loaded (stub — full implementation coming in Step 4)");
    }

    /** Entry point used by static -javaagent flag (not the primary path, but good practice). */
    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }
}
