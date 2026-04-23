package net.brdloush.livewire.attach;

/**
 * Thin wrappers over {@link Client} that back the top-level jshell helper
 * methods (info, beans, eval, sql, demo, detach).
 * <p>
 * All methods return {@link String} so jshell can print them without needing
 * to know about bundle-side types. The output path is deliberately plain-text
 * so Part 2 can swap in a proper EDN pretty-printer without touching each method.
 */
public class AttachHelpers {

    private final Client client;

    // ─── Clojure eval code for each command ───────────────────────────────────
    // Stored as constants to keep the method bodies readable.

    private static final String INFO_CODE =
        "(let [ctx (net.brdloush.livewire.core/ctx)\n"
        + "      env (net.brdloush.livewire.core/bean \"environment\")]\n"
        + "  {:application-name (or (.getProperty env \"spring.application.name\")\n"
        + "                         (.getDisplayName ctx)\n"
        + "                         \"unknown\")\n"
        + "   :spring-boot      (try (org.springframework.boot.SpringBootVersion/getVersion)\n"
        + "                         (catch Throwable _ \"?\"))\n"
        + "   :hibernate        (try (org.hibernate.Version/getVersionString)\n"
        + "                         (catch Throwable _ \"?\"))\n"
        + "   :java             (System/getProperty \"java.version\")\n"
        + "   :datasource       (try\n"
        + "                       (let [ds (net.brdloush.livewire.core/bean javax.sql.DataSource)]\n"
        + "                         (with-open [c (.getConnection ds)]\n"
        + "                           (let [md (.getMetaData c)]\n"
        + "                             {:db-product (str (.getDatabaseProductName md)\n"
        + "                                               \" \" (.getDatabaseProductVersion md))\n"
        + "                              :jdbc-url   (.getURL md)})))\n"
        + "                       (catch Throwable _ nil))})";

    private static final String DEMO_CODE =
        "(let [repo (net.brdloush.livewire.core/bean \"bookRepository\")\n"
        + "      res  (trace/trace-sql (.count repo))]\n"
        + "  (str \"Book count: \" (:result res)\n"
        + "       \"  (\" (:count res) \" SQL \"\n"
        + "       (if (= 1 (:count res)) \"query\" \"queries\")\n"
        + "       \", \" (:duration-ms res) \"ms)\"))";

    // ─── constructor ──────────────────────────────────────────────────────────

    public AttachHelpers(Client client) {
        this.client = client;
    }

    // ─── public API ───────────────────────────────────────────────────────────

    /**
     * Returns an EDN map with runtime, datasource, and framework version info.
     * Delegates to Livewire's live Spring context via nREPL.
     */
    public String info() throws Exception {
        return client.eval(INFO_CODE);
    }

    /**
     * Returns a sorted list of Spring bean names matching {@code pattern} (regex).
     */
    public String beans(String pattern) throws Exception {
        String code =
            "(str (sort (filter #(re-matches (re-pattern \""
            + escapeClj(pattern)
            + "\") %)\n"
            + "            (vec (.getBeanDefinitionNames (net.brdloush.livewire.core/ctx))))))";
        return client.eval(code);
    }

    /**
     * Evaluates arbitrary Clojure code against the live nREPL session.
     */
    public String eval(String code) throws Exception {
        return client.eval(code);
    }

    /**
     * Runs a read-only SQL query through the live DataSource and returns
     * a vector of maps (EDN), one per row.
     */
    public String sql(String query) throws Exception {
        return client.eval("(q/sql \"" + escapeClj(query) + "\")");
    }

    /**
     * Runs a trace-sql demo: counts books and reports the SQL query count.
     * Requires a {@code bookRepository} bean in the target application.
     */
    public String demo() throws Exception {
        return client.eval(DEMO_CODE);
    }

    /**
     * Closes the nREPL session and the underlying socket.
     * Returns a status message for jshell to print.
     */
    public String detach() {
        client.close();
        return "[livewire] detached ✓";
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    /** Escapes backslashes and double-quotes for use inside a Clojure string literal. */
    private static String escapeClj(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
