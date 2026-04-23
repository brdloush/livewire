package net.brdloush.livewire.attach;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bencode nREPL client — speaks to the nREPL server started inside the target JVM.
 * <p>
 * Deliberately kept separate from AttachHelpers so Part 2's rich REPL loop
 * can use Client directly without going through the reflection layer.
 * <p>
 * Thread-safety: not thread-safe. Designed for single-threaded interactive use.
 */
public class Client {

    private static final int DEFAULT_PORT    = 7888;
    private static final int SOCKET_TIMEOUT  = 30_000; // ms

    private final String host;
    private final int    port;

    private Socket              socket;
    private PushbackInputStream in;
    private OutputStream        out;
    private String              session;
    private int                 nextId = 1;

    // ─── constructors ─────────────────────────────────────────────────────────

    public Client()                       { this("127.0.0.1", DEFAULT_PORT); }
    public Client(int port)               { this("127.0.0.1", port); }
    public Client(String host, int port)  { this.host = host; this.port = port; }

    // ─── lifecycle ────────────────────────────────────────────────────────────

    /**
     * Opens the TCP socket and clones an nREPL session.
     * The session is reused across all subsequent eval() calls.
     */
    public void connect() throws Exception {
        socket = new Socket(host, port);
        socket.setSoTimeout(SOCKET_TIMEOUT);
        in  = new PushbackInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        session = cloneSession();
    }

    public boolean isConnected() { return socket != null && !socket.isClosed(); }
    public String  getSession()  { return session; }

    // ─── public nREPL ops ─────────────────────────────────────────────────────

    /**
     * Evaluate Clojure code in the persistent session.
     * Returns a formatted result string ready for display — value, stdout,
     * and any error lines merged in natural order.
     */
    public String eval(String code) throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("code",    code);
        msg.put("id",      String.valueOf(nextId++));
        msg.put("op",      "eval");
        msg.put("session", session);
        send(msg);
        return formatEvalResult(readUntilDone());
    }

    /** Describe the nREPL server capabilities. Returns the raw response map. */
    public Map<String, Object> describe() throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", String.valueOf(nextId++));
        msg.put("op", "describe");
        send(msg);
        List<Map<String, Object>> responses = readUntilDone();
        return responses.isEmpty() ? Collections.emptyMap() : responses.get(0);
    }

    /** Interrupt the currently-running eval in the session. */
    public void interrupt() throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id",      String.valueOf(nextId++));
        msg.put("op",      "interrupt");
        msg.put("session", session);
        send(msg);
        readUntilDone(); // consume the ack
    }

    /**
     * Close the nREPL session and the underlying TCP socket.
     * Safe to call more than once.
     */
    public void close() {
        if (session != null) {
            try {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("id",      String.valueOf(nextId++));
                msg.put("op",      "close");
                msg.put("session", session);
                send(msg);
                readUntilDone();
            } catch (Exception ignored) {}
            session = null;
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    // ─── private: session clone ───────────────────────────────────────────────

    private String cloneSession() throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", String.valueOf(nextId++));
        msg.put("op", "clone");
        send(msg);
        for (Map<String, Object> r : readUntilDone()) {
            Object ns = r.get("new-session");
            if (ns != null) return ns.toString();
        }
        throw new IOException("nREPL clone did not return a session ID");
    }

    // ─── private: response accumulation ──────────────────────────────────────

    /** Read response dicts until one carries status "done". */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readUntilDone() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        while (true) {
            Object decoded = decode();
            if (!(decoded instanceof Map)) continue;
            Map<String, Object> response = (Map<String, Object>) decoded;
            results.add(response);
            Object status = response.get("status");
            if (status instanceof List && ((List<?>) status).contains("done")) break;
        }
        return results;
    }

    // ─── private: result formatting ──────────────────────────────────────────

    private String formatEvalResult(List<Map<String, Object>> responses) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder value  = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        StringBuilder ex     = new StringBuilder();

        for (Map<String, Object> r : responses) {
            if (r.containsKey("out"))   stdout.append(r.get("out"));
            if (r.containsKey("value")) value.append(r.get("value"));
            if (r.containsKey("err"))   stderr.append(r.get("err"));
            if (r.containsKey("ex"))    ex.append(r.get("ex"));
        }

        StringBuilder result = new StringBuilder();
        if (stdout.length() > 0) result.append(stdout);
        if (value.length()  > 0) result.append(value);
        if (stderr.length() > 0) result.append("\n[err] ").append(stderr.toString().strip());
        if (ex.length()     > 0) result.append("\n[ex]  ").append(ex.toString().strip());
        return result.toString().strip();
    }

    // ─── private: bencode encoding ────────────────────────────────────────────

    private void send(Map<String, Object> msg) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        encodeDict(msg, buf);
        out.write(buf.toByteArray());
        out.flush();
    }

    private void encode(Object obj, OutputStream os) throws IOException {
        if (obj instanceof String) {
            byte[] bytes = ((String) obj).getBytes(StandardCharsets.UTF_8);
            os.write((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
            os.write(bytes);
        } else if (obj instanceof Long || obj instanceof Integer) {
            os.write(("i" + obj + "e").getBytes(StandardCharsets.UTF_8));
        } else if (obj instanceof Map) {
            encodeDict((Map<?, ?>) obj, os);
        } else if (obj instanceof List) {
            os.write('l');
            for (Object item : (List<?>) obj) encode(item, os);
            os.write('e');
        } else if (obj != null) {
            encode(obj.toString(), os);
        }
    }

    /** Keys must be sorted lexicographically per the bencode spec. */
    private void encodeDict(Map<?, ?> map, OutputStream os) throws IOException {
        os.write('d');
        List<String> keys = new ArrayList<>();
        for (Object k : map.keySet()) keys.add(k.toString());
        Collections.sort(keys);
        for (String k : keys) {
            encode(k, os);
            encode(map.get(k), os);
        }
        os.write('e');
    }

    // ─── private: bencode decoding ────────────────────────────────────────────

    /**
     * Decode one bencode value from the input stream.
     * <ul>
     *   <li>{@code d...e} → {@code LinkedHashMap<String,Object>}</li>
     *   <li>{@code l...e} → {@code ArrayList<Object>}</li>
     *   <li>{@code i...e} → {@code Long}</li>
     *   <li>{@code N:...} → {@code String} (UTF-8)</li>
     * </ul>
     */
    private Object decode() throws Exception {
        int b = in.read();
        if (b < 0) throw new EOFException("nREPL connection closed unexpectedly");

        if (b == 'd') {
            // dict: alternating string keys and values, terminated by 'e'
            Map<String, Object> map = new LinkedHashMap<>();
            while (true) {
                int peek = in.read();
                if (peek < 0) throw new EOFException("unexpected EOF inside bencode dict");
                if (peek == 'e') break;
                // dict keys are always bencode strings; peek is the first digit of the length
                String key = readString((byte) peek);
                Object val = decode();
                map.put(key, val);
            }
            return map;
        }

        if (b == 'l') {
            // list: any values, terminated by 'e'
            List<Object> list = new ArrayList<>();
            while (true) {
                int peek = in.read();
                if (peek < 0) throw new EOFException("unexpected EOF inside bencode list");
                if (peek == 'e') break;
                in.unread(peek); // put back — decode() will re-read it
                list.add(decode());
            }
            return list;
        }

        if (b == 'i') {
            // integer: digits terminated by 'e'
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != 'e') {
                if (c < 0) throw new EOFException("unexpected EOF inside bencode integer");
                sb.append((char) c);
            }
            return Long.parseLong(sb.toString());
        }

        // string: b is the first digit of the length prefix
        if (b >= '0' && b <= '9') {
            return readString((byte) b);
        }

        throw new IOException("unexpected bencode type byte: " + b + " ('" + (char) b + "')");
    }

    /**
     * Read a bencode string whose length prefix has {@code firstByte} as its first character.
     * Reads the rest of the length, the ':', then exactly that many bytes.
     */
    private String readString(byte firstByte) throws Exception {
        StringBuilder lenStr = new StringBuilder();
        lenStr.append((char) (firstByte & 0xff));
        int c;
        while ((c = in.read()) != ':') {
            if (c < 0) throw new EOFException("unexpected EOF reading bencode string length");
            lenStr.append((char) c);
        }
        int len   = Integer.parseInt(lenStr.toString());
        byte[] bs = in.readNBytes(len);
        if (bs.length < len) throw new EOFException("truncated bencode string: expected "
                + len + " bytes, got " + bs.length);
        return new String(bs, StandardCharsets.UTF_8);
    }
}
