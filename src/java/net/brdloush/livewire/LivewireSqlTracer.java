package net.brdloush.livewire;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LivewireSqlTracer implements StatementInspector {

    private static final ThreadLocal<Queue<String>> threadLocalQueue = new ThreadLocal<>();
    private static volatile Queue<String> globalQueue = null;

    public static void startThreadLocalTrace() {
        threadLocalQueue.set(new ConcurrentLinkedQueue<>());
    }

    public static List<String> stopThreadLocalTrace() {
        Queue<String> queue = threadLocalQueue.get();
        threadLocalQueue.remove();
        return queue == null ? new ArrayList<>() : new ArrayList<>(queue);
    }

    public static void startGlobalTrace() {
        globalQueue = new ConcurrentLinkedQueue<>();
    }

    public static List<String> stopGlobalTrace() {
        Queue<String> queue = globalQueue;
        globalQueue = null;
        return queue == null ? new ArrayList<>() : new ArrayList<>(queue);
    }

    @Override
    public String inspect(String sql) {
        // Capture thread-local SQL
        Queue<String> tlq = threadLocalQueue.get();
        if (tlq != null) {
            tlq.add(sql);
        }

        // Capture global SQL
        Queue<String> gq = globalQueue;
        if (gq != null) {
            gq.add(sql);
        }

        return sql; // Return unmodified
    }
}
