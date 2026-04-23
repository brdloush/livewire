package net.brdloush.livewire.attach;

/**
 * Bencode nREPL client — speaks to the nREPL server started inside the target JVM.
 * <p>
 * Deliberately kept separate from AttachHelpers so Part 2's rich REPL loop
 * can use Client directly without going through the reflection layer.
 * <p>
 * This is a stub — full bencode encode/decode and TCP socket logic
 * are implemented in Step 3.
 */
public class Client {
    // TODO (Step 3): TCP socket, bencode encoder/decoder, clone(), eval(),
    //                describe(), interrupt(), close().
}
