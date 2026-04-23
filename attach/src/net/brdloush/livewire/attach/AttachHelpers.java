package net.brdloush.livewire.attach;

/**
 * Thin wrappers over Client that back the top-level jshell helper methods
 * (info, beans, eval, sql, demo, detach).
 * <p>
 * All methods return String so jshell can print them without needing to
 * know about bundle-side types. The output path is designed to be swappable
 * so Part 2 can plug in a proper EDN pretty-printer without touching each method.
 * <p>
 * This is a stub — full implementations are added in Step 5.
 */
public class AttachHelpers {
    // TODO (Step 5): inject a Client instance, implement info(), beans(String),
    //                eval(String), sql(String), demo(), detach().
}
