import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercise 01 — REST API for QuickBite orders.
 *
 * Run:   java OrderApi.java          (server on http://localhost:8081)
 * Check: java Check.java             (in another terminal)
 *
 * Endpoints you will make work:
 *   GET    /menu                       -> 200 list of menu items   (already done — use it as your example)
 *   POST   /orders                     -> 201 + Location header    (TODO 1, idempotency TODO 5)
 *   GET    /orders/{id}                -> 200 / 404                (TODO 2)
 *   GET    /orders?status=&limit=&offset= -> 200 paginated list   (TODO 3)
 *   PATCH  /orders/{id}  {"status":"CANCELLED"} -> 200 / 400 / 409 (TODO 4)
 */
public class OrderApi {

    static final int PORT = 8081;

    // ---------- Domain ----------
    enum Status { PLACED, PREPARING, OUT_FOR_DELIVERY, DELIVERED, CANCELLED }

    record MenuItem(String itemId, String name, int price) {}

    record Item(String itemId, int qty) {}

    record Order(String id, String customerId, List<Item> items, int total, Status status, String createdAt) {
        Order withStatus(Status s) { return new Order(id, customerId, items, total, s, createdAt); }
    }

    static final Map<String, MenuItem> MENU = new LinkedHashMap<>();
    static {
        for (MenuItem m : List.of(
                new MenuItem("paneer-tikka", "Paneer Tikka", 249),
                new MenuItem("masala-dosa", "Masala Dosa", 129),
                new MenuItem("veg-biryani", "Veg Biryani", 219),
                new MenuItem("gulab-jamun", "Gulab Jamun", 79))) {
            MENU.put(m.itemId(), m);
        }
    }

    // ---------- In-memory storage ----------
    static final Map<String, Order> ORDERS = Collections.synchronizedMap(new LinkedHashMap<>());
    static final Map<String, String> IDEMPOTENCY_KEYS = new ConcurrentHashMap<>(); // key -> orderId
    static final AtomicInteger SEQ = new AtomicInteger(1000);

    public static void main(String[] args) throws IOException {
        System.setProperty("sun.net.httpserver.nodelay", "true"); // send small responses immediately
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/menu", Http.safe(OrderApi::menu));
        server.createContext("/orders", Http.safe(OrderApi::orders));
        server.createContext("/admin/orders", Http.safe(OrderApi::adminAdvance));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("QuickBite Order API running on http://localhost:" + PORT);
    }

    // ---------- Routing (given) ----------
    static void menu(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { Http.methodNotAllowed(ex, "GET"); return; }
        Http.send(ex, 200, new ArrayList<>(MENU.values()));
    }

    static void orders(HttpExchange ex) throws IOException {
        String[] parts = ex.getRequestURI().getPath().split("/"); // ["", "orders", "{id}"]
        String method = ex.getRequestMethod();
        if (parts.length == 2) {                       // /orders
            switch (method) {
                case "POST" -> createOrder(ex);
                case "GET" -> listOrders(ex);
                default -> Http.methodNotAllowed(ex, "GET, POST");
            }
        } else if (parts.length == 3) {                // /orders/{id}
            switch (method) {
                case "GET" -> getOrder(ex, parts[2]);
                case "PATCH" -> patchOrder(ex, parts[2]);
                // Think: why is there no DELETE here? (see README, question 3)
                default -> Http.methodNotAllowed(ex, "GET, PATCH");
            }
        } else {
            Http.send(ex, 404, Map.of("error", "No such resource"));
        }
    }

    // ---------- Handlers ----------

    /**
     * POST /orders
     * Body: {"customerId":"cust_7","items":[{"itemId":"masala-dosa","qty":2}]}
     *
     * TODO(1): Validate and create an order.
     *   - customerId must be a non-blank string                     -> else 400
     *   - items must be a non-empty list                            -> else 400
     *   - every itemId must exist in MENU, qty must be 1..20        -> else 400 with a helpful message
     *   - total = sum(price * qty); id = "ord_" + SEQ.incrementAndGet(); status = PLACED
     *   - respond 201 Created, with header  Location: /orders/{id}  and the order as body
     *
     * TODO(5) (do this after the others pass): Idempotency.
     *   If the request has an "Idempotency-Key" header that we have already seen,
     *   do NOT create a new order. Return 200 with the ORIGINAL order and the header
     *   "Idempotent-Replayed: true". Otherwise remember key -> orderId after creating.
     *   (Why? A mobile app on flaky 4G retries POST — the user must not get charged twice.)
     */
    static void createOrder(HttpExchange ex) throws IOException {
        Map<String, Object> body = Http.body(ex);
        String idemKey = ex.getRequestHeaders().getFirst("Idempotency-Key");

        System.out.println("idemKey is coming as : " + idemKey);

        // 1. Validate customerId
        if (body == null || !body.containsKey("customerId")) {
            Http.send(ex, 400, Map.of("error", "customerId must be a non-blank string"));
            return;
        }
        String customerId = body.get("customerId") instanceof String ? (String) body.get("customerId") : "";
        if (customerId.isBlank()) {
            Http.send(ex, 400, Map.of("error", "customerId must be a non-blank string"));
            return;
        }


        Object itemsObj = body.get("items");

        if (!(itemsObj instanceof List<?>) || ((List<?>) itemsObj).isEmpty()) {
            Http.send(ex, 400, Map.of("error", "list must be a non-empty list"));
            return;
        }

        @SuppressWarnings("unchecked")
        List<LinkedHashMap<?, ?>> items = (List<LinkedHashMap<?, ?>>) body.get("items");

        Set<String> set = new HashSet<>();

        for(Map.Entry<String, MenuItem> entry: MENU.entrySet()){
            String  key = entry.getKey();
            MenuItem value = entry.getValue();
            set.add(key);
        }
        int total = 0;
        List<Item> itemList = new ArrayList<>();

        for(LinkedHashMap item: items){
            if(!item.containsKey("itemId") || !set.contains(item.get("itemId"))){
                Http.send(ex, 400, Map.of("error", "itemId must be a non-blank string or present in menu"));
                return;
            }
            String itemId = item.get("itemId").toString();
            Number rawQty = (Number) item.get("qty");
            int qty = rawQty.intValue();
            if (qty < 1 || qty > 20) {
                Http.send(ex, 400, Map.of("error", "qty must be between 1 and 20"));
                return;
            }
            Item item1 = new Item(itemId, qty);
            itemList.add(item1);

            total += (Long)(item.get("qty")) * MENU.get(itemId).price;
        }

        LocalDateTime now = LocalDateTime.now();
        String orderId = "ord_" + SEQ.incrementAndGet();
        Order order = new Order(orderId, customerId, itemList, total, Status.PLACED, now.toString());
        ORDERS.put(orderId, order);
        ex.getResponseHeaders().set("Location", "/orders/" + orderId);
        Http.send(ex, 201, order);
    }

    /**
     * GET /orders/{id}
     * TODO(2): return 200 with the order, or 404 {"error": "..."} if it does not exist.
     */
    static void getOrder(HttpExchange ex, String id) throws IOException {
        System.out.println("********** IT HTIS THE METHOD");
        if(id == null || id.isEmpty() || !ORDERS.containsKey(id)){
            Http.send(ex, 404, Map.of("error", "id cannot be null or empty"));
            return;
        }
        Order order = ORDERS.get(id);
        Http.send(ex, 200, order);    }

    /**
     * GET /orders?status=PLACED&limit=10&offset=0
     * TODO(3): filter + paginate.
     *   - status is optional; an unknown status value -> 400
     *   - limit defaults to 10 (max 50), offset defaults to 0; bad numbers -> 400
     *   - respond 200 with {"items":[...], "total": <count after filtering>, "limit": n, "offset": n}
     *   Hint: Http.query(ex) gives you the query parameters as a Map.
     */
    static void listOrders(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        throw new UnsupportedOperationException("TODO(3) not implemented yet");
    }

    /**
     * PATCH /orders/{id}   Body: {"status":"CANCELLED"}
     * TODO(4): customers may only CANCEL, and only while the order is still PLACED.
     *   - order missing                         -> 404
     *   - status missing or anything but CANCELLED -> 400 (a customer can't mark an order DELIVERED!)
     *   - order not in PLACED                   -> 409 Conflict  {"error": "cannot cancel an order that is PREPARING"}
     *   - otherwise                             -> 200 with the updated order
     */
    static void patchOrder(HttpExchange ex, String id) throws IOException {
        Map<String, Object> body = Http.body(ex);
        throw new UnsupportedOperationException("TODO(4) not implemented yet");
    }

    /**
     * POST /admin/orders/{id}/advance   (given — pretend this is the restaurant's tablet app)
     * Moves an order one step forward: PLACED -> PREPARING -> OUT_FOR_DELIVERY -> DELIVERED.
     * Check.java uses it to test the 409 Conflict case.
     */
    static void adminAdvance(HttpExchange ex) throws IOException {
        String[] parts = ex.getRequestURI().getPath().split("/"); // ["", "admin", "orders", "{id}", "advance"]
        if (!ex.getRequestMethod().equals("POST") || parts.length != 5 || !parts[4].equals("advance")) {
            Http.send(ex, 404, Map.of("error", "Use POST /admin/orders/{id}/advance"));
            return;
        }
        synchronized (ORDERS) {
            Order o = ORDERS.get(parts[3]);
            if (o == null) { Http.send(ex, 404, Map.of("error", "Order not found")); return; }
            Status next = switch (o.status()) {
                case PLACED -> Status.PREPARING;
                case PREPARING -> Status.OUT_FOR_DELIVERY;
                default -> Status.DELIVERED;
            };
            if (o.status() == Status.CANCELLED) next = Status.CANCELLED;
            Order updated = o.withStatus(next);
            ORDERS.put(o.id(), updated);
            Http.send(ex, 200, updated);
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
