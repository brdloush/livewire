package net.brdloush.livewire;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LivewireSqlTracer implements StatementInspector {

    public static class TraceEntry {
        public final String sql;
        public final String caller;
        
        public TraceEntry(String sql, String caller) {
            this.sql = sql;
            this.caller = caller;
        }
    }

    private static final ThreadLocal<Queue<TraceEntry>> threadLocalQueue = new ThreadLocal<>();
    private static volatile Queue<TraceEntry> globalQueue = null;

    public static void startThreadLocalTrace() {
        threadLocalQueue.set(new ConcurrentLinkedQueue<>());
    }

    public static List<TraceEntry> stopThreadLocalTrace() {
        Queue<TraceEntry> queue = threadLocalQueue.get();
        threadLocalQueue.remove();
        return queue == null ? new ArrayList<>() : new ArrayList<>(queue);
    }

    public static void startGlobalTrace() {
        globalQueue = new ConcurrentLinkedQueue<>();
    }

    public static List<TraceEntry> stopGlobalTrace() {
        Queue<TraceEntry> queue = globalQueue;
        globalQueue = null;
        return queue == null ? new ArrayList<>() : new ArrayList<>(queue);
    }

    @Override
    public String inspect(String sql) {
        Queue<TraceEntry> tlq = threadLocalQueue.get();
        Queue<TraceEntry> gq = globalQueue;

        if (tlq != null || gq != null) {
            String caller = determineCaller(Thread.currentThread().getStackTrace());
            TraceEntry entry = new TraceEntry(sql, caller);

            if (tlq != null) {
                tlq.add(entry);
            }
            if (gq != null) {
                gq.add(entry);
            }
        }

        return sql; // Return unmodified
    }

    private String determineCaller(StackTraceElement[] stackTrace) {
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            
            // Skip known framework/internal packages to find the actual app code
            if (!className.startsWith("org.hibernate.") &&
                !className.startsWith("org.springframework.") &&
                !className.startsWith("java.") &&
                !className.startsWith("javax.") &&
                !className.startsWith("jdk.") &&
                !className.startsWith("sun.") &&
                !className.startsWith("com.sun.") &&
                !className.startsWith("org.apache.") &&
                !className.startsWith("net.brdloush.livewire.")) {
                
                // We found the app code!
                // Clean up CGLIB proxy names for readability (e.g. MyService$$EnhancerBySpringCGLIB$$ -> MyService)
                int proxyIdx = className.indexOf("$$");
                String cleanClassName = proxyIdx > 0 ? className.substring(0, proxyIdx) : className;
                
                return cleanClassName + "." + element.getMethodName() + ":" + element.getLineNumber();
            }
        }
        return "unknown";
    }
}
