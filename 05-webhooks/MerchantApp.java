import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exercise 05 — QuickBite's backend, which RECEIVES webhooks from QuickPay.
 *
 * Run:  java MerchantApp.java           (http://localhost:8092)
 *       java MerchantApp.java --flaky   (fails the first 2 webhooks with 503, to watch retries)
 *
 *   POST /orders {"amount":499}     -> creates an order, asks QuickPay to charge it -> 201 PENDING_PAYMENT
 *   GET  /orders/{id}               -> watch it flip to PAID / PAYMENT_FAILED when the webhook lands
 *   POST /webhooks/quickpay         -> the webhook receiver (TODO 3, 4, 5)
 *   GET  /stats                     -> counters for received / duplicate / rejected webhooks
 */
public class MerchantApp {

    static final int PORT = 8092;
    static final String GATEWAY = "http://localhost:8091";
    static final String WEBHOOK_SECRET = "whsec_quickbite_demo_123";
    static final long TOLERANCE_SECONDS = 5 * 60;

    static final Map<String, Map<String, Object>> ORDERS = new ConcurrentHashMap<>();
    static final Set<String> PROCESSED_EVENTS = ConcurrentHashMap.newKeySet();
    static final AtomicInteger SEQ = new AtomicInteger(700);
    static final AtomicInteger RECEIVED = new AtomicInteger(), DUPLICATES = new AtomicInteger(), REJECTED = new AtomicInteger();
    static final AtomicInteger FLAKY_FAILURES_LEFT = new AtomicInteger(0);
    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws IOException {
        if (List.of(args).contains("--flaky")) FLAKY_FAILURES_LEFT.set(2);
        System.setProperty("sun.net.httpserver.nodelay", "true");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Http.safe(MerchantApp::route));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("QuickBite merchant on http://localhost:" + PORT + (FLAKY_FAILURES_LEFT.get() > 0 ? "  [FLAKY mode]" : ""));
    }

    static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (path.equals("/orders") && method.equals("POST")) createOrder(ex);
        else if (path.startsWith("/orders/") && method.equals("GET")) {
            var o = ORDERS.get(path.substring("/orders/".length()));
            if (o == null) Http.send(ex, 404, Map.of("error", "no such order"));
            else Http.send(ex, 200, o);
        } else if (path.equals("/webhooks/quickpay") && method.equals("POST")) receiveWebhook(ex);
        else if (path.equals("/stats")) {
            Http.send(ex, 200, Map.of("received", RECEIVED.get(), "duplicatesIgnored", DUPLICATES.get(), "rejected", REJECTED.get()));
        } else Http.send(ex, 404, Map.of("error", "not found"));
    }

    /** Given: create an order and start a payment. Note we do NOT wait for the payment result. */
    static void createOrder(HttpExchange ex) throws IOException {
        Map<String, Object> body = Http.body(ex);
        if (!(body.get("amount") instanceof Number amount) || amount.longValue() <= 0) {
            Http.send(ex, 400, Map.of("error", "amount must be a positive number"));
            return;
        }
        String orderId = "ord_" + SEQ.incrementAndGet();
        Map<String, Object> order = new ConcurrentHashMap<>(Map.of("orderId", orderId, "amount", amount.longValue(), "status", "PENDING_PAYMENT"));
        ORDERS.put(orderId, order);
        try {
            String req = Json.write(Map.of("orderId", orderId, "amount", amount.longValue(),
                    "webhookUrl", "http://localhost:" + PORT + "/webhooks/quickpay"));
            var resp = HTTP.send(HttpRequest.newBuilder(URI.create(GATEWAY + "/payments"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(req)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (Json.parse(resp.body()) instanceof Map<?, ?> p && p.get("paymentId") instanceof String pid) order.put("paymentId", pid);
        } catch (Exception e) {
            ORDERS.remove(orderId);
            Http.send(ex, 502, Map.of("error", "payment gateway unreachable -- is PayGateway.java running?"));
            return;
        }
        Http.send(ex, 201, order);
    }

    static void receiveWebhook(HttpExchange ex) throws IOException {
        RECEIVED.incrementAndGet();
        // Read the RAW bytes. The signature is over these exact bytes — if you parse and re-serialize
        // the JSON first, key order or spacing may change and the signature will never match.
        String rawBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sigHeader = ex.getRequestHeaders().getFirst("QuickPay-Signature");

        if (FLAKY_FAILURES_LEFT.getAndDecrement() > 0) {
            System.out.println("[webhook] pretending to be down (503)");
            Http.send(ex, 503, Map.of("error", "temporarily unavailable"));
            return;
        }

        /*
         * TODO(3): Verify the signature.  Header looks like:  t=1730000000,v1=ab12...ef
         *   - header missing or malformed                       -> 401
         *   - |now - t| > TOLERANCE_SECONDS                     -> 401 (stops replay of an old, stolen request)
         *   - expected = hmacHex(WEBHOOK_SECRET, t + "." + rawBody)
         *     compare with v1 using MessageDigest.isEqual(a.getBytes(), b.getBytes())   <- constant time!
         *     mismatch                                          -> 401
         *   On any 401, also REJECTED.incrementAndGet().
         */
        String rejection = verifySignature(sigHeader, rawBody);
        if (rejection != null) {
            REJECTED.incrementAndGet();
            System.out.println("[webhook] rejected: " + rejection);
            Http.send(ex, 401, Map.of("error", rejection));
            return;
        }

        Map<String, Object> event = Http.parseObject(rawBody);
        String eventId = (String) event.get("id");

        /*
         * TODO(4): Idempotency. Gateways deliver "at least once", so the same event WILL arrive twice.
         *   If eventId was already processed -> DUPLICATES.incrementAndGet() and respond 200
         *   (200, not 409! A non-2xx would make the gateway retry a duplicate forever.)
         *   Hint: PROCESSED_EVENTS.add(eventId) returns false if it was already there — atomic check-and-set.
         */
        if (eventId == null) throw new Http.BadRequest("event id is required");
        if (!PROCESSED_EVENTS.add(eventId)) {
            DUPLICATES.incrementAndGet();
            System.out.println("[webhook] duplicate " + eventId + " ignored");
            Http.send(ex, 200, Map.of("result", "duplicate ignored"));
            return;
        }

        /*
         * TODO(5): Apply the event.
         *   data.orderId -> the order.  Unknown order -> still 200 (log it; retrying won't help).
         *   "payment.succeeded" -> status PAID       "payment.failed" -> status PAYMENT_FAILED
         *   Respond 200 {"result":"ok"} quickly. (Slow work like emails belongs in a background job.)
         */
        String orderId = event.get("data") instanceof Map<?, ?> data && data.get("orderId") instanceof String id ? id : null;
        Map<String, Object> order = orderId == null ? null : ORDERS.get(orderId);
        String newStatus = switch (String.valueOf(event.get("type"))) {
            case "payment.succeeded" -> "PAID";
            case "payment.failed" -> "PAYMENT_FAILED";
            default -> null;
        };
        if (order == null) System.out.println("[webhook] " + eventId + " for unknown order " + orderId + " -- ignored");
        else if (newStatus == null) System.out.println("[webhook] " + eventId + " has unhandled type " + event.get("type"));
        else {
            order.put("status", newStatus);
            System.out.println("[webhook] " + eventId + " -> " + orderId + " " + newStatus);
        }
        Http.send(ex, 200, Map.of("result", "ok"));
    }

    /** Returns why the signature is invalid, or null if it checks out. */
    static String verifySignature(String header, String rawBody) {
        if (header == null) return "missing QuickPay-Signature header";
        String t = null, v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) t = kv[1];
            else if (kv[0].equals("v1")) v1 = kv[1];
        }
        if (t == null || v1 == null) return "malformed QuickPay-Signature header";
        long ts;
        try { ts = Long.parseLong(t); } catch (NumberFormatException e) { return "malformed timestamp"; }
        if (Math.abs(System.currentTimeMillis() / 1000 - ts) > TOLERANCE_SECONDS) return "timestamp outside tolerance (replay?)";
        String expected = hmacHex(WEBHOOK_SECRET, t + "." + rawBody);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8))) return "signature mismatch";
        return null;
    }

    static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
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
