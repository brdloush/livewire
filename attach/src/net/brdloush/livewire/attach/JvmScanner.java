package net.brdloush.livewire.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.util.*;

/**
 * Wraps the JDK Attach API to list and annotate running JVMs.
 * <p>
 * Caches the most recent scan so {@link #getPid(int)} can be called after
 * {@link #listJvms()} without re-scanning.
 */
public class JvmScanner {

    /** Immutable snapshot of one JVM entry from the last scan. */
    static final class VmEntry {
        final String pid;
        final String displayName;
        final String details;

        VmEntry(String pid, String displayName, String details) {
            this.pid         = pid;
            this.displayName = displayName;
            this.details     = details;
        }
    }

    /** 1-based; populated by the most recent call to listJvms(). */
    private static final List<VmEntry> LAST_SCAN = new ArrayList<>();

    // ─── public API ───────────────────────────────────────────────────────────

    /**
     * Scans all attachable JVMs on this host, skipping the current process.
     * Returns one formatted display line per JVM. The list is also stored
     * internally for later lookup via {@link #getPid(int)}.
     */
    public static List<String> listJvms() {
        LAST_SCAN.clear();
        List<String> lines = new ArrayList<>();
        String selfPid = String.valueOf(ProcessHandle.current().pid());
        int index = 1;

        for (VirtualMachineDescriptor vmd : VirtualMachine.list()) {
            if (vmd.id().equals(selfPid)) continue;

            String name = vmd.displayName();
            if (name == null || name.isBlank()) name = "(unknown)";

            // jshell's tool JVM appears in the list because jshell runs user code
            // in a remote execution engine with a different PID — selfPid only skips
            // the execution engine, not the tool JVM.  Skip it explicitly.
            if (name.toLowerCase().contains("jshell")) continue;

            String details = probeDetails(vmd);
            LAST_SCAN.add(new VmEntry(vmd.id(), name, details));

            lines.add(String.format("  [%d] pid %-7s  %-50s %s",
                    index, vmd.id(), truncate(name, 50), details));
            index++;
        }
        return lines;
    }

    /**
     * Returns the PID string for a 1-based index from the most recent scan.
     * Throws {@link IllegalArgumentException} if the index is out of range.
     */
    public static String getPid(int index) {
        if (index < 1 || index > LAST_SCAN.size()) {
            throw new IllegalArgumentException(
                    "No JVM at index " + index + ". Last scan found "
                            + LAST_SCAN.size() + " JVM(s). "
                            + "Re-run: LIVEWIRE_BUNDLE_PATH=... jshell attach.jsh");
        }
        return LAST_SCAN.get(index - 1).pid;
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    /**
     * Briefly attaches to the JVM to read its system properties, then detaches.
     * Falls back to a heuristic label if the attach fails for any reason.
     */
    private static String probeDetails(VirtualMachineDescriptor vmd) {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(vmd);
            Properties sys = vm.getSystemProperties();
            String javaVer = sys.getProperty("java.version", "?");
            String major   = majorVersion(javaVer);

            // Spring Boot Maven/Gradle plugins set this system property.
            String bootVer = sys.getProperty("spring.boot.version");
            if (bootVer != null) {
                return "(Spring Boot " + bootVer + ", Java " + major + ")";
            }
            // Heuristic on display name — common Spring Boot class naming conventions.
            String dn = vmd.displayName();
            boolean looksSpringy = dn != null
                    && (dn.contains("Application") || dn.contains("Service")
                    || dn.contains("Worker")      || dn.contains("Server")
                    || dn.contains("Boot"));
            return looksSpringy
                    ? "(looks like Spring Boot, Java " + major + ")"
                    : "(Java " + major + ")";

        } catch (AttachNotSupportedException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("native") || msg.contains("graal")) {
                return "(native image — Livewire attach not supported)";
            }
            return "(attach not supported — try sudo?)";
        } catch (IOException e) {
            return "(cannot read — " + e.getMessage() + ")";
        } catch (Exception e) {
            return "(? — " + e.getClass().getSimpleName() + ")";
        } finally {
            if (vm != null) try { vm.detach(); } catch (Exception ignored) {}
        }
    }

    /** "21.0.4" → "21",  "17.0.9" → "17",  "1.8.0_202" → "8" */
    private static String majorVersion(String full) {
        if (full == null || full.isEmpty()) return "?";
        if (full.startsWith("1.")) {
            int second = full.indexOf('.', 2);
            return second > 0 ? full.substring(2, second) : full.substring(2);
        }
        int dot = full.indexOf('.');
        return dot > 0 ? full.substring(0, dot) : full;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
