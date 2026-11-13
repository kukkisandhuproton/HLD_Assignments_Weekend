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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exercise 05 — "QuickPay", a pretend payment gateway that SENDS webhooks.
 *
 * Run:  java PayGateway.java                (http://localhost:8091)
 *       java PayGateway.java --duplicate    (delivers every event twice — real gateways do this!)
 *
 *   POST /payments  {"orderId":"ord_1","amount":499,"webhookUrl":"http://localhost:8092/webhooks/quickpay"}
 *                   -> 202 Accepted {"paymentId":"pay_..","status":"PENDING"}
 *                   ~1.5 s later the gateway POSTs an event to webhookUrl:
 *                   payment.succeeded (amount <= 10000) or payment.failed (amount > 10000)
 *   GET  /payments/{id}   -> current status (the "pull" fallback merchants use to reconcile)
 *   GET  /deliveries      -> log of every webhook delivery attempt (watch the retries here)
 */
public class PayGateway {

    static final int PORT = 8091;
    /** Shared with the merchant out-of-band (in real life: copied from the gateway dashboard). */
    static final String WEBHOOK_SECRET = "whsec_quickbite_demo_123";
    static final int MAX_ATTEMPTS = 5;

    static boolean duplicateMode;
    static final Map<String, Map<String, Object>> PAYMENTS = new ConcurrentHashMap<>();
    static final List<Map<String, Object>> DELIVERIES = Collections.synchronizedList(new ArrayList<>());
    static final AtomicInteger SEQ = new AtomicInteger(5000);
    static final ScheduledExecutorService BANK = Executors.newScheduledThreadPool(2);
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws IOException {
        duplicateMode = List.of(args).contains("--duplicate");
        System.setProperty("sun.net.httpserver.nodelay", "true");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Http.safe(PayGateway::route));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("QuickPay gateway on http://localhost:" + PORT + (duplicateMode ? "  [DUPLICATE mode]" : ""));
    }

    static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (path.equals("/payments") && method.equals("POST")) createPayment(ex);
        else if (path.startsWith("/payments/") && method.equals("GET")) {
            var p = PAYMENTS.get(path.substring("/payments/".length()));
            if (p == null) Http.send(ex, 404, Map.of("error", "no such payment"));
            else Http.send(ex, 200, p);
        } else if (path.equals("/deliveries") && method.equals("GET")) {
            synchronized (DELIVERIES) { Http.send(ex, 200, new ArrayList<>(DELIVERIES)); }
        } else {
            Http.send(ex, 200, Map.of("service", "QuickPay", "try", "POST /payments, GET /deliveries"));
        }
    }

    static void createPayment(HttpExchange ex) throws IOException {
        Map<String, Object> body = Http.body(ex);
        if (!(body.get("orderId") instanceof String orderId) || !(body.get("amount") instanceof Number amount)
                || !(body.get("webhookUrl") instanceof String webhookUrl)) {
            Http.send(ex, 400, Map.of("error", "orderId, amount and webhookUrl are required"));
            return;
        }
        String paymentId = "pay_" + SEQ.incrementAndGet();
        Map<String, Object> payment = new ConcurrentHashMap<>(Map.of(
                "paymentId", paymentId, "orderId", orderId, "amount", amount.longValue(), "status", "PENDING"));
        PAYMENTS.put(paymentId, payment);

        // The bank takes a moment. We answer NOW with 202 and tell the merchant later via webhook.
        BANK.schedule(() -> settle(payment, webhookUrl), 1500, TimeUnit.MILLISECONDS);
        Http.send(ex, 202, payment);
    }

    static void settle(Map<String, Object> payment, String webhookUrl) {
        boolean ok = ((Long) payment.get("amount")) <= 10_000;
        payment.put("status", ok ? "SUCCEEDED" : "FAILED");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_" + UUID.randomUUID().toString().substring(0, 8));
        event.put("type", ok ? "payment.succeeded" : "payment.failed");
        event.put("createdAt", System.currentTimeMillis());
        event.put("data", new LinkedHashMap<>(payment));
        String json = Json.write(event);
        System.out.println("[bank] " + payment.get("paymentId") + " " + payment.get("status") + " -> sending " + event.get("id"));

        Thread.startVirtualThread(() -> deliverWithRetries(webhookUrl, (String) event.get("id"), json));
        if (duplicateMode) Thread.startVirtualThread(() -> deliverWithRetries(webhookUrl, (String) event.get("id"), json));
    }

    /**
     * TODO(1): Build the signature header so the merchant can prove the webhook came from us.
     *   Format:   t=<unixSeconds>,v1=<hex HMAC-SHA256 of  "<unixSeconds>.<body>"  using WEBHOOK_SECRET>
     *   e.g.      t=1730000000,v1=5f2b...e1
     *   Use hmacHex(secret, message) below.
     *   (Why include the timestamp in what we sign? See README question 3.)
     */
    static String signatureHeader(String body, long unixSeconds) {
        return "t=" + unixSeconds + ",v1=" + hmacHex(WEBHOOK_SECRET, unixSeconds + "." + body);
    }

    /**
     * TODO(2): Deliver the webhook, retrying with exponential backoff.
     *   for attempt = 1..MAX_ATTEMPTS:
     *     - POST json to url with headers  Content-Type: application/json
     *                                      QuickPay-Signature: signatureHeader(json, now in seconds)
     *       (sign on EVERY attempt — the timestamp must be fresh)
     *       use HttpRequest.newBuilder(...).timeout(Duration.ofSeconds(5))  — slow merchants count as failures
     *     - recordAttempt(eventId, attempt, statusCodeOrErrorMessage)
     *     - 2xx -> done, return
     *     - anything else (non-2xx, timeout, connection refused) -> sleep 1s, 2s, 4s, 8s... then try again
     *   After MAX_ATTEMPTS failures, print that the event was given up on (real gateways email you).
     */
    static void deliverWithRetries(String url, String eventId, String json) {
        long delayMs = 1000;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("QuickPay-Signature", signatureHeader(json, System.currentTimeMillis() / 1000))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            try {
                int status = HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                recordAttempt(eventId, attempt, status);
                if (status / 100 == 2) return;
            } catch (IOException e) {
                recordAttempt(eventId, attempt, e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (attempt == MAX_ATTEMPTS) break;
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delayMs *= 2;
        }
        System.out.println("[webhook] " + eventId + " gave up after " + MAX_ATTEMPTS + " attempts -- merchant must reconcile via GET /payments/{id}");
    }

    // ---------------- given ----------------

    static void recordAttempt(String eventId, int attempt, Object result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", eventId);
        row.put("attempt", attempt);
        row.put("result", result);
        row.put("at", System.currentTimeMillis());
        DELIVERIES.add(row);
        System.out.println("[webhook] " + eventId + " attempt " + attempt + " -> " + result);
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
