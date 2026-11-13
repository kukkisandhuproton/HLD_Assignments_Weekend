import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exercise 03 — Server-Sent Events (SSE).
 *
 * Run:  java SseServer.java     then open http://localhost:8083 in a browser
 *
 *   GET  /                          -> live order-tracker page (uses the browser's EventSource)
 *   POST /orders  {"stepMs":3000}   -> 201
 *   GET  /orders/{id}/events        -> text/event-stream, one event per status change (TODO 1, 2)
 *        header Last-Event-ID: 3  or  ?lastEventId=3   -> resume after event 3
 *
 * Same kitchen as Exercise 02 — but now ONE request stays open and the server pushes.
 */
public class SseServer {

    static final int PORT = 8083;
    static final List<String> LIFECYCLE = List.of("PLACED", "ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED");

    static final class TrackedOrder {
        final String id;
        final ReentrantLock lock = new ReentrantLock();
        final Condition changed = lock.newCondition();
        /** Every status the order has had. Event id N == history.get(N - 1). Guarded by lock. */
        final List<Map<String, Object>> history = new ArrayList<>();

        TrackedOrder(String id) { this.id = id; record(0); }

        void record(int step) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", id);
            m.put("status", LIFECYCLE.get(step));
            m.put("version", history.size() + 1);
            m.put("changedAt", System.currentTimeMillis());
            history.add(m);
        }

        void advance() {
            lock.lock();
            try {
                record(history.size());
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        boolean isDone() { return history.size() == LIFECYCLE.size(); }
    }

    static final Map<String, TrackedOrder> ORDERS = new ConcurrentHashMap<>();
    static final AtomicInteger SEQ = new AtomicInteger(100);
    static final ScheduledExecutorService KITCHEN = Executors.newScheduledThreadPool(2);

    public static void main(String[] args) throws IOException {
        System.setProperty("sun.net.httpserver.nodelay", "true");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Http.safe(SseServer::route));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("SSE server on http://localhost:" + PORT + "  (open it in your browser)");
    }

    static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");
        String method = ex.getRequestMethod();
        if (path.equals("/") && method.equals("GET")) {
            byte[] html = TrackerPage.HTML.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            ex.getResponseBody().write(html);
            ex.close();
        } else if (path.equals("/orders") && method.equals("POST")) {
            createOrder(ex);
        } else if (parts.length == 4 && parts[1].equals("orders") && parts[3].equals("events") && method.equals("GET")) {
            TrackedOrder order = ORDERS.get(parts[2]);
            if (order == null) Http.send(ex, 404, Map.of("error", "Unknown order " + parts[2]));
            else stream(ex, order);
        } else {
            Http.send(ex, 404, Map.of("error", "Not found"));
        }
    }

    static void createOrder(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        long stepMs = 3000;
        if (!raw.isBlank() && Json.parse(raw) instanceof Map<?, ?> m && m.get("stepMs") instanceof Number n) stepMs = n.longValue();
        TrackedOrder order = new TrackedOrder("ord_" + SEQ.incrementAndGet());
        ORDERS.put(order.id, order);
        scheduleNextStep(order, stepMs);
        Http.send(ex, 201, Map.of("orderId", order.id));
    }

    static void scheduleNextStep(TrackedOrder order, long stepMs) {
        long delay = (long) (stepMs * ThreadLocalRandom.current().nextDouble(0.5, 1.5));
        KITCHEN.schedule(() -> {
            order.advance();
            if (!order.isDone()) scheduleNextStep(order, stepMs);
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * TODO(1): Write ONE event in SSE wire format and flush it so the browser gets it NOW.
     *
     *   id: 3\n
     *   event: status\n
     *   data: {"status":"PREPARING",...}\n
     *   \n                      <- the blank line ends the event
     *
     * That's the entire protocol. It's just text over a response that never ends.
     */
    static void writeEvent(OutputStream os, int id, String event, String data) throws IOException {
        String message = "id: " + id + "\n"
                + "event: " + event + "\n"
                + "data: " + data + "\n\n";

        os.write(message.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /**
     * TODO(2): Stream the order's status changes.
     *
     *   1. lastSent = value of the "Last-Event-ID" header, else the ?lastEventId= query param, else 0.
     *   2. If the order is finished AND the client already has every event -> respond 204.
     *      (204 is how SSE tells a browser "stop reconnecting". Otherwise EventSource retries forever.)
     *   3. Set headers  Content-Type: text/event-stream  and  Cache-Control: no-cache,
     *      then ex.sendResponseHeaders(200, 0)   // 0 = "body length unknown, keep streaming"
     *   4. Loop:
     *        - under the lock, wait (order.changed.await(15, SECONDS)) until history.size() > lastSent
     *        - copy the new entries (history.subList(lastSent, size)) — then unlock before writing
     *        - nothing new after 15 s? write the comment line ": keep-alive\n\n" and flush
     *          (proxies close connections that look idle)
     *        - send each new entry with writeEvent(os, version, "status", Json.write(entry))
     *        - once the order is done and everything is sent -> close the stream
     *   A client that disconnects shows up as an IOException on write — just let it end the method.
     */
    static void stream(HttpExchange ex, TrackedOrder order) throws IOException {
        String lastEventId = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventId == null) {
            lastEventId = Http.query(ex).get("lastEventId");
        }

        int lastSent = 0;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                lastSent = Integer.parseInt(lastEventId);
            } catch (NumberFormatException e) {
                throw new Http.BadRequest("Last-Event-ID must be a number");
            }
            if (lastSent < 0) throw new Http.BadRequest("Last-Event-ID cannot be negative");
        }

        int historySize;
        boolean alreadyFinished;
        order.lock.lock();
        try {
            historySize = order.history.size();
            alreadyFinished = order.isDone();
        } finally {
            order.lock.unlock();
        }
        lastSent = Math.min(lastSent, historySize);
        if (alreadyFinished && lastSent == historySize) {
            Http.send(ex, 204, null);
            return;
        }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);

        try (OutputStream os = ex.getResponseBody()) {
            while (true) {
                List<Map<String, Object>> newEntries = new ArrayList<>();
                boolean finished;
                boolean timedOut;

                order.lock.lock();
                try {
                    long remaining = TimeUnit.SECONDS.toNanos(15);
                    while (order.history.size() <= lastSent && !order.isDone() && remaining > 0) {
                        remaining = order.changed.awaitNanos(remaining);
                    }

                    int size = order.history.size();
                    if (size > lastSent) {
                        newEntries.addAll(order.history.subList(lastSent, size));
                    }
                    finished = order.isDone();
                    timedOut = newEntries.isEmpty() && !finished;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    order.lock.unlock();
                }

                if (timedOut) {
                    os.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                for (Map<String, Object> entry : newEntries) {
                    int version = ((Number) entry.get("version")).intValue();
                    writeEvent(os, version, "status", Json.write(entry));
                    lastSent = version;
                }

                if (finished && newEntries.isEmpty()) return;
            }
        }
    }
}

/** The browser side. EventSource is built into every browser — no library needed. */
final class TrackerPage {
    static final String HTML = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>QuickBite Live Tracker</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0f0c29;color:#fff;max-width:640px;margin:32px auto;padding:0 16px}
          h1{background:linear-gradient(90deg,#ff512f,#f09819);-webkit-background-clip:text;color:transparent}
          button{border:0;border-radius:10px;padding:10px 16px;font-weight:700;cursor:pointer;margin-right:8px}
          #place{background:#00e676;color:#002}#drop{background:#ff1744;color:#fff}
          #conn{display:inline-block;margin-left:8px;padding:4px 10px;border-radius:999px;background:#555;font-size:13px}
          .on{background:#00b0ff!important}.off{background:#ff9100!important}
          ol{list-style:none;padding:0}li{margin:10px 0;padding:12px 14px;border-radius:12px;font-weight:600}
          .PLACED{background:#651fff}.ACCEPTED{background:#2979ff}.PREPARING{background:#ff9100}
          .OUT_FOR_DELIVERY{background:#f50057}.DELIVERED{background:#00c853}
          small{opacity:.8;font-weight:400}pre{background:#1a1640;padding:10px;border-radius:8px;font-size:12px;white-space:pre-wrap}
        </style></head><body>
        <h1>QuickBite Live Tracker</h1>
        <button id="place">Place order</button><button id="drop" disabled>Simulate network drop</button>
        <span id="conn">idle</span>
        <ol id="timeline"></ol>
        <h3>Raw events</h3><pre id="raw"></pre>
        <script>
          let es, orderId, lastId = 0;
          const $ = id => document.getElementById(id);
          function connect() {
            // A fresh EventSource can't set headers, so we resume with ?lastEventId=.
            // (When the BROWSER auto-reconnects, it sends the Last-Event-ID header by itself.)
            es = new EventSource(`/orders/${orderId}/events?lastEventId=${lastId}`);
            es.onopen = () => { $('conn').textContent = 'connected'; $('conn').className = 'on'; };
            es.onerror = () => { $('conn').textContent = 'reconnecting...'; $('conn').className = 'off'; };
            es.addEventListener('status', e => {
              lastId = Number(e.lastEventId);
              const d = JSON.parse(e.data);
              $('raw').textContent += `id: ${e.lastEventId}\\nevent: status\\ndata: ${e.data}\\n\\n`;
              const li = document.createElement('li');
              li.className = d.status;
              li.innerHTML = `${d.status.replaceAll('_', ' ')} <small>v${d.version} · ${new Date(d.changedAt).toLocaleTimeString()}</small>`;
              $('timeline').appendChild(li);
              if (d.status === 'DELIVERED') { es.close(); $('conn').textContent = 'done'; $('conn').className = ''; $('drop').disabled = true; }
            });
          }
          $('place').onclick = async () => {
            if (es) es.close();
            $('timeline').innerHTML = ''; $('raw').textContent = ''; lastId = 0;
            const r = await fetch('/orders', {method: 'POST', body: '{"stepMs":3000}'});
            orderId = (await r.json()).orderId;
            $('drop').disabled = false;
            connect();
          };
          $('drop').onclick = () => {
            es.close(); $('conn').textContent = 'offline (5s)'; $('conn').className = 'off';
            setTimeout(connect, 5000);   // come back and resume from lastId — nothing is lost
          };
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
