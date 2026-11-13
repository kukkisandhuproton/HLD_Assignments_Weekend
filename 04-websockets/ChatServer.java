import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exercise 04 — WebSockets, from scratch (plain sockets, no framework).
 *
 * Run:  java ChatServer.java   then open http://localhost:8084 in TWO browser tabs
 *
 * A customer and a delivery rider chat about one order:
 *   ws://localhost:8084/chat?order=ord_42&user=Ravi
 *
 * Client -> server messages:   {"type":"message","text":"I'm at the gate"}   or   {"type":"typing"}
 * Server -> client messages:   {"type":"message","from":"Ravi","text":"...","at":1730000000000}
 *                              {"type":"typing","from":"Ravi"}
 *                              {"type":"system","text":"Ravi joined","online":["Ravi","Asha"]}
 *
 * The point: a WebSocket starts life as an ordinary HTTP request with "Upgrade: websocket".
 * The server answers "101 Switching Protocols" and from then on the SAME TCP connection
 * carries small binary frames in BOTH directions, whenever either side wants.
 */
public class ChatServer {

    static final int PORT = 8084;
    static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"; // fixed by RFC 6455

    /** One connected browser/app. send() is synchronized because many threads may broadcast to it. */
    static final class Client {
        final String user, room;
        private final OutputStream out;
        Client(String user, String room, OutputStream out) { this.user = user; this.room = room; this.out = out; }
        synchronized void send(String text) throws IOException { Ws.writeFrame(out, Ws.TEXT, text.getBytes(StandardCharsets.UTF_8)); }
        synchronized void sendRaw(int opcode, byte[] payload) throws IOException { Ws.writeFrame(out, opcode, payload); }
        @Override public String toString() { return user + "@" + room; }
    }

    /** room (order id) -> everyone connected to it */
    static final Map<String, Set<Client>> ROOMS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("Chat server on http://localhost:" + PORT + "  (open it in two tabs)");
            while (true) {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                Thread.startVirtualThread(() -> handle(socket));   // one cheap thread per connection
            }
        }
    }

    // =====================================================================
    // YOUR PART
    // =====================================================================

    /**
     * TODO(1): The handshake. The browser sends a random  Sec-WebSocket-Key.
     * Prove you speak WebSocket by returning:
     *       Base64( SHA-1( key + WEBSOCKET_GUID ) )
     * Test value from RFC 6455:  "dGhlIHNhbXBsZSBub25jZQ==" -> "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
     * Hints: MessageDigest.getInstance("SHA-1"), Base64.getEncoder()
     */
    static String acceptKey(String secWebSocketKey) throws Exception {
        if (secWebSocketKey == null || secWebSocketKey.isBlank()) throw new IllegalArgumentException("missing Sec-WebSocket-Key");
        byte[] sha1 = MessageDigest.getInstance("SHA-1")
                .digest((secWebSocketKey.trim() + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(sha1);
    }

    /**
     * TODO(2): A client finished the handshake.
     *   - add it to ROOMS (create the room's set if needed — ConcurrentHashMap.newKeySet() is thread-safe)
     *   - tell EVERYONE in the room (including the newcomer):
     *       {"type":"system","text":"<user> joined","online":[...names in the room...]}
     *   Use broadcast(...) below.
     */
    static void onOpen(Client c) {
        ROOMS.compute(c.room, (r, members) -> {
            if (members == null) members = ConcurrentHashMap.newKeySet();
            members.add(c);
            return members;
        });
        broadcast(c.room, system(c.room, c.user + " joined"), null);
    }

    /**
     * TODO(3): A text frame arrived from client c.
     *   Parse it with Json.parse(text) (you get a Map).
     *   - {"type":"message","text":"..."}  -> blank text? ignore it.
     *        otherwise send {"type":"message","from":c.user,"text":...,"at":System.currentTimeMillis()}
     *        to EVERYONE in the room (the sender's copy acts as a delivery receipt)
     *   - {"type":"typing"}                -> send {"type":"typing","from":c.user} to everyone EXCEPT c
     *   - anything else                    -> c.send({"type":"error","text":"unknown message type"})
     */
    static void onMessage(Client c, String text) throws IOException {
        Object parsed;
        try { parsed = Json.parse(text); } catch (RuntimeException e) { parsed = null; }
        Map<?, ?> in = parsed instanceof Map<?, ?> m ? m : Map.of();
        switch (String.valueOf(in.get("type"))) {
            case "message" -> {
                if (!(in.get("text") instanceof String body) || body.isBlank()) return;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("type", "message");
                out.put("from", c.user);
                out.put("text", body);
                out.put("at", System.currentTimeMillis());
                broadcast(c.room, out, null);
            }
            case "typing" -> broadcast(c.room, Map.of("type", "typing", "from", c.user), c);
            default -> c.send(Json.write(Map.of("type", "error", "text", "unknown message type")));
        }
    }

    /**
     * TODO(4): The connection is gone (closed tab, lost network, close frame...).
     *   Remove c from its room (drop the room when it's empty) and tell the others "<user> left".
     */
    static void onClose(Client c) {
        boolean[] removed = {false};
        ROOMS.computeIfPresent(c.room, (r, members) -> {
            removed[0] = members.remove(c);
            return members.isEmpty() ? null : members;
        });
        if (!removed[0]) return;
        broadcast(c.room, system(c.room, c.user + " left"), null);
    }

    // =====================================================================
    // GIVEN — read it, it's short and it's the real protocol
    // =====================================================================

    /** Sends msg (as JSON) to everyone in the room except `except` (may be null). */
    static void broadcast(String room, Map<String, Object> msg, Client except) {
        String json = Json.write(msg);
        for (Client other : ROOMS.getOrDefault(room, Set.of())) {
            if (other == except) continue;
            try {
                other.send(json);
            } catch (IOException e) {
                System.out.println("   could not reach " + other + " (" + e.getMessage() + ")");
            }
        }
    }

    static Map<String, Object> system(String room, String text) {
        List<String> online = ROOMS.getOrDefault(room, Set.of()).stream().map(x -> x.user).sorted().toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "system");
        m.put("text", text);
        m.put("online", online);
        return m;
    }

    static void handle(Socket socket) {
        Client client = null;
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            RawHttp.Request req = RawHttp.read(in);
            if (req == null) return;

            if (req.path().equals("/")) {
                RawHttp.respond(out, 200, "text/html; charset=utf-8", ChatPage.HTML);
                return;
            }
            if (!req.path().equals("/chat")) {
                RawHttp.respond(out, 404, "text/plain", "Not found");
                return;
            }
            // ---- 1. The upgrade request: still plain HTTP ----
            if (!"websocket".equalsIgnoreCase(req.headers().get("upgrade"))) {
                RawHttp.respond(out, 400, "text/plain", "This endpoint only speaks WebSocket (send Upgrade: websocket)");
                return;
            }
            String user = req.query().get("user"), room = req.query().get("order");
            if (user == null || user.isBlank() || room == null || room.isBlank()) {
                RawHttp.respond(out, 400, "text/plain", "Need ?order=...&user=...");
                return;
            }
            String accept;
            try {
                accept = acceptKey(req.headers().get("sec-websocket-key"));
            } catch (UnsupportedOperationException e) {
                RawHttp.respond(out, 501, "text/plain", e.getMessage());
                return;
            } catch (Exception e) {
                RawHttp.respond(out, 400, "text/plain", "Bad handshake: " + e);
                return;
            }
            // ---- 2. Switch protocols: after these bytes it's not HTTP anymore ----
            out.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                       "Upgrade: websocket\r\n" +
                       "Connection: Upgrade\r\n" +
                       "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            client = new Client(user.trim(), room.trim(), out);
            System.out.println("+ " + client + " connected");
            try { onOpen(client); }
            catch (UnsupportedOperationException e) { client.send(Json.write(Map.of("type", "error", "text", e.getMessage()))); }

            // ---- 3. Frames, both directions, until someone closes ----
            while (true) {
                Ws.Frame f = Ws.readFrame(in);
                if (f == null) break;                                  // TCP connection dropped
                switch (f.opcode()) {
                    case Ws.TEXT -> {
                        try { onMessage(client, new String(f.payload(), StandardCharsets.UTF_8)); }
                        catch (UnsupportedOperationException e) { client.send(Json.write(Map.of("type", "error", "text", e.getMessage()))); }
                    }
                    case Ws.PING -> client.sendRaw(Ws.PONG, f.payload());  // heartbeat: must answer
                    case Ws.CLOSE -> { client.sendRaw(Ws.CLOSE, f.payload()); return; }
                    default -> { /* binary / pong: ignore */ }
                }
            }
        } catch (IOException e) {
            // client vanished — normal for the internet
        } finally {
            if (client != null) {
                System.out.println("- " + client + " disconnected");
                try { onClose(client); } catch (UnsupportedOperationException ignored) { }
            }
        }
    }
}

/** WebSocket framing (RFC 6455, section 5). Enough for text chat. */
final class Ws {
    static final int CONTINUATION = 0x0, TEXT = 0x1, BINARY = 0x2, CLOSE = 0x8, PING = 0x9, PONG = 0xA;

    record Frame(int opcode, byte[] payload) {}

    /**
     *  byte 0: FIN bit + opcode        byte 1: MASK bit + 7-bit length (126 => next 2 bytes, 127 => next 8)
     *  then 4-byte mask key (browser->server frames are always masked), then the payload XOR mask.
     */
    static Frame readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = -1;
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF); }
            if (len > 1_000_000) throw new IOException("frame too large");
            byte[] mask = masked ? in.readNBytes(4) : null;
            byte[] data = in.readNBytes((int) len);
            if (data.length < len) return null;
            if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            if (opcode >= 0x8) return new Frame(opcode, data);           // control frames are never split
            if (messageOpcode == -1) messageOpcode = opcode;               // first fragment decides the type
            message.write(data);
            if (fin) return new Frame(messageOpcode, message.toByteArray());
        }
    }

    /** Server->client frames are NOT masked. */
    static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);                      // FIN + opcode
        int n = payload.length;
        if (n < 126) out.write(n);
        else if (n < 65536) { out.write(126); out.write(n >>> 8); out.write(n & 0xFF); }
        else { out.write(127); for (int i = 7; i >= 0; i--) out.write((int) (((long) n >>> (8 * i)) & 0xFF)); }
        out.write(payload);
        out.flush();
    }
}

/** Just enough HTTP/1.1 to read the upgrade request and serve the chat page. */
final class RawHttp {
    record Request(String method, String path, Map<String, String> query, Map<String, String> headers) {}

    static Request read(InputStream in) throws IOException {
        String requestLine = line(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        Map<String, String> headers = new HashMap<>();
        for (String h = line(in); h != null && !h.isEmpty(); h = line(in)) {
            int c = h.indexOf(':');
            if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
        }
        String target = parts[1];
        int q = target.indexOf('?');
        Map<String, String> query = new HashMap<>();
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return new Request(parts[0], q >= 0 ? target.substring(0, q) : target, query, headers);
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
        }
        return b.length() == 0 ? null : b.toString();
    }

    static void respond(OutputStream out, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = switch (status) { case 200 -> "OK"; case 400 -> "Bad Request"; case 404 -> "Not Found"; default -> "Error"; };
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length +
                   "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}

final class ChatPage {
    static final String HTML = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>QuickBite Rider Chat</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#12002b;color:#fff;max-width:560px;margin:24px auto;padding:0 16px}
          h1{background:linear-gradient(90deg,#00e5ff,#d500f9);-webkit-background-clip:text;color:transparent;margin-bottom:8px}
          input,button{font:inherit;border-radius:10px;border:0;padding:10px 12px}
          input{background:#2a1050;color:#fff}button{background:#ff3d00;color:#fff;font-weight:700;cursor:pointer}
          #join input{width:120px}#log{height:340px;overflow:auto;background:#1c0840;border-radius:14px;padding:12px;margin:12px 0}
          .msg{margin:6px 0;padding:8px 12px;border-radius:14px;max-width:80%;width:fit-content}
          .me{background:#00c853;margin-left:auto}.them{background:#2979ff}.sys{color:#ffea00;font-size:13px;text-align:center}
          .err{color:#ff5252;font-size:13px}#typing{height:18px;font-size:13px;color:#18ffff}
          #state{display:inline-block;padding:3px 10px;border-radius:999px;background:#555;font-size:12px}
          .on{background:#00e676!important;color:#002}
        </style></head><body>
        <h1>Rider ⇄ Customer chat</h1>
        <div id="join">
          <input id="order" value="ord_42" placeholder="order id">
          <input id="user" placeholder="your name">
          <button id="go">Join</button> <span id="state">offline</span>
        </div>
        <div id="log"></div><div id="typing"></div>
        <form id="send"><input id="text" style="width:75%" placeholder="Type a message" autocomplete="off"> <button>Send</button></form>
        <script>
          let ws, me, typingTimer;
          const $ = id => document.getElementById(id);
          function add(cls, text) { const d = document.createElement('div'); d.className = cls; d.textContent = text; $('log').appendChild(d); $('log').scrollTop = 1e9; }
          $('user').value = ['Ravi (rider)', 'Asha (customer)'][Math.floor(Math.random() * 2)];
          $('go').onclick = () => {
            me = $('user').value.trim();
            ws = new WebSocket(`ws://${location.host}/chat?order=${encodeURIComponent($('order').value)}&user=${encodeURIComponent(me)}`);
            ws.onopen = () => { $('state').textContent = 'connected'; $('state').className = 'on'; };
            ws.onclose = () => { $('state').textContent = 'offline'; $('state').className = ''; };
            ws.onmessage = e => {
              const m = JSON.parse(e.data);
              if (m.type === 'message') { $('typing').textContent = ''; add('msg ' + (m.from === me ? 'me' : 'them'), (m.from === me ? '' : m.from + ': ') + m.text); }
              else if (m.type === 'system') add('sys', `${m.text} · online: ${m.online.join(', ')}`);
              else if (m.type === 'typing') { $('typing').textContent = `${m.from} is typing...`; clearTimeout(typingTimer); typingTimer = setTimeout(() => $('typing').textContent = '', 1500); }
              else if (m.type === 'error') add('err', m.text);
            };
          };
          $('text').oninput = () => ws && ws.readyState === 1 && ws.send(JSON.stringify({type: 'typing'}));
          $('send').onsubmit = e => { e.preventDefault(); if (ws && ws.readyState === 1) ws.send(JSON.stringify({type: 'message', text: $('text').value})); $('text').value = ''; };
        </script></body></html>
        """;
}

// =====================================================================
// Helpers below — you don't need to change anything here.
// =====================================================================

final class Http {
    static final class BadRequest extends RuntimeException {
        BadRequest(String m) { super(m); }
    }

    /** Wraps a handler: TODOs become 501, bad JSON becomes 400, bugs become 500. */
    static HttpHandler safe(HttpHandler h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (UnsupportedOperationException e) {
                trySend(ex, 501, Map.of("error", e.getMessage()));
            } catch (BadRequest e) {
                trySend(ex, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                trySend(ex, 500, Map.of("error", "Server bug: " + e));
            } finally {
                ex.close();
            }
        };
    }

    private static void trySend(HttpExchange ex, int status, Object body) {
        try { send(ex, status, body); } catch (Exception ignored) { /* response already started */ }
    }

    static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) ex.getResponseHeaders().set("Content-Type", "application/json");
        System.out.printf("%-6s %-40s -> %d%n", ex.getRequestMethod(), ex.getRequestURI(), status);
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    static void methodNotAllowed(HttpExchange ex, String allow) throws IOException {
        ex.getResponseHeaders().set("Allow", allow);
        send(ex, 405, Map.of("error", ex.getRequestMethod() + " not allowed here. Allowed: " + allow));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(HttpExchange ex) throws IOException {
        return parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String raw) {
        if (raw.isBlank()) throw new BadRequest("Request body is required");
        Object parsed;
        try { parsed = Json.parse(raw); } catch (RuntimeException e) { throw new BadRequest("Malformed JSON: " + e.getMessage()); }
        if (!(parsed instanceof Map)) throw new BadRequest("Body must be a JSON object");
        return (Map<String, Object>) parsed;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                  kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return q;
    }
}

/** A tiny JSON reader/writer so the exercise needs nothing but the JDK. */
final class Json {
    static Object parse(String s) {
        Parser p = new Parser(s);
        try {
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) throw new IllegalArgumentException("Unexpected text at position " + p.i);
            return v;
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
    }

    static String write(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return quote(s);
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Enum<?> e) return quote(e.name());
        if (o instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            m.forEach((k, v) -> j.add(quote(String.valueOf(k)) + ":" + write(v)));
            return j.toString();
        }
        if (o instanceof Collection<?> c) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (Object v : c) j.add(write(v));
            return j.toString();
        }
        if (o instanceof Record r) {
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                for (var rc : r.getClass().getRecordComponents()) {
                    var accessor = rc.getAccessor();
                    accessor.setAccessible(true);
                    m.put(rc.getName(), accessor.invoke(r));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return write(m);
        }
        return quote(o.toString());
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + i);
            String n = s.substring(st, i);
            return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : Double.parseDouble(n);
        }

        String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        void expect(char c) {
            ws();
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("Expected '" + c + "' at position " + i);
            i++;
        }
    }
}
