import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic checker for Exercise 01. Start the server first:  java OrderApi.java
 * Then run:  java Check.java
 */
public class Check {
    static final String BASE = "http://localhost:8081";
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static int passed, failed;

    public static void main(String[] args) throws Exception {
        try {
            CLIENT.send(HttpRequest.newBuilder(URI.create(BASE + "/menu")).build(), HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            System.out.println("Cannot reach " + BASE + " -- start the server first:  java OrderApi.java");
            System.exit(1);
        }

        String goodOrder = """
            {"customerId":"cust_7","items":[{"itemId":"masala-dosa","qty":2},{"itemId":"gulab-jamun","qty":1}]}""";

        System.out.println("\n-- TODO(1) create order --");
        String[] id = new String[1];
        test("POST /orders with a valid body -> 201 Created", () -> {
            var r = call("POST", "/orders", goodOrder);
            expectStatus(r, 201);
            id[0] = field(r.body(), "id");
            expect(id[0] != null && id[0].startsWith("ord_"), "body should contain the new order id");
            expect("PLACED".equals(field(r.body(), "status")), "new order status should be PLACED");
            expect("337".equals(field(r.body(), "total")), "total should be 2*129 + 79 = 337, got " + field(r.body(), "total"));
            String loc = r.headers().firstValue("Location").orElse(null);
            expect(("/orders/" + id[0]).equals(loc), "Location header should be /orders/" + id[0] + " but was " + loc);
        });
        test("missing customerId -> 400", () -> expectStatus(call("POST", "/orders", "{\"items\":[{\"itemId\":\"masala-dosa\",\"qty\":1}]}"), 400));
        test("empty items -> 400", () -> expectStatus(call("POST", "/orders", "{\"customerId\":\"c1\",\"items\":[]}"), 400));
        test("unknown itemId -> 400", () -> expectStatus(call("POST", "/orders", "{\"customerId\":\"c1\",\"items\":[{\"itemId\":\"pizza\",\"qty\":1}]}"), 400));
        test("qty 0 -> 400", () -> expectStatus(call("POST", "/orders", "{\"customerId\":\"c1\",\"items\":[{\"itemId\":\"masala-dosa\",\"qty\":0}]}"), 400));
        test("malformed JSON -> 400", () -> expectStatus(call("POST", "/orders", "{oops"), 400));

        System.out.println("\n-- TODO(2) get order --");
        test("GET /orders/{id} -> 200 with that order", () -> {
            var r = call("GET", "/orders/" + id[0], null);
            expectStatus(r, 200);
            expect(id[0].equals(field(r.body(), "id")), "wrong order returned");
        });
        test("GET /orders/ord_999999 -> 404", () -> expectStatus(call("GET", "/orders/ord_999999", null), 404));

        /*
        System.out.println("\n-- TODO(3) list + filter + paginate --");
        for (int i = 0; i < 3; i++) call("POST", "/orders", goodOrder);
        test("GET /orders?limit=2 -> 200, at most 2 items, has total/limit/offset", () -> {
            var r = call("GET", "/orders?limit=2", null);
            expectStatus(r, 200);
            int items = count(r.body(), "\"customerId\"");
            expect(items == 2, "expected exactly 2 items in the page, got " + items);
            expect(field(r.body(), "total") != null && Integer.parseInt(field(r.body(), "total")) >= 4, "total should count ALL matching orders (>= 4)");
            expect("2".equals(field(r.body(), "limit")), "response should echo limit");
        });
        test("GET /orders?limit=2&offset=1 returns a different first item than offset=0", () -> {
            String a = field(call("GET", "/orders?limit=1&offset=0", null).body(), "id");
            String b = field(call("GET", "/orders?limit=1&offset=1", null).body(), "id");
            expect(a != null && !a.equals(b), "offset is not being applied");
        });
        test("GET /orders?status=BOGUS -> 400", () -> expectStatus(call("GET", "/orders?status=BOGUS", null), 400));
        test("GET /orders?limit=abc -> 400", () -> expectStatus(call("GET", "/orders?limit=abc", null), 400));
        test("GET /orders?limit=500 -> 400 (max is 50)", () -> expectStatus(call("GET", "/orders?limit=500", null), 400));

        System.out.println("\n-- TODO(4) cancel with PATCH --");
        test("PATCH {status:CANCELLED} on a PLACED order -> 200", () -> {
            var r = call("PATCH", "/orders/" + id[0], "{\"status\":\"CANCELLED\"}");
            expectStatus(r, 200);
            expect("CANCELLED".equals(field(r.body(), "status")), "status should now be CANCELLED");
        });
        test("GET /orders?status=CANCELLED only returns cancelled orders", () -> {
            var r = call("GET", "/orders?status=CANCELLED&limit=50", null);
            expectStatus(r, 200);
            expect(!r.body().contains("\"PLACED\""), "filter returned a PLACED order");
            expect(r.body().contains(id[0]), "the cancelled order is missing");
        });
        test("PATCH {status:DELIVERED} -> 400 (customers can't do that)", () -> {
            String other = field(call("POST", "/orders", goodOrder).body(), "id");
            expectStatus(call("PATCH", "/orders/" + other, "{\"status\":\"DELIVERED\"}"), 400);
        });
        test("PATCH cancel on a PREPARING order -> 409 Conflict", () -> {
            String other = field(call("POST", "/orders", goodOrder).body(), "id");
            call("POST", "/admin/orders/" + other + "/advance", "");
            expectStatus(call("PATCH", "/orders/" + other, "{\"status\":\"CANCELLED\"}"), 409);
        });
        test("PATCH on a missing order -> 404", () -> expectStatus(call("PATCH", "/orders/ord_999999", "{\"status\":\"CANCELLED\"}"), 404));
        test("DELETE /orders/{id} -> 405 with an Allow header", () -> {
            var r = call("DELETE", "/orders/" + id[0], null);
            expectStatus(r, 405);
            expect(r.headers().firstValue("Allow").isPresent(), "405 responses should say what IS allowed");
        });

        System.out.println("\n-- TODO(5) idempotency --");
        test("Same Idempotency-Key twice -> one order, second response replayed", () -> {
            String key = "key-" + System.nanoTime();
            var first = call("POST", "/orders", goodOrder, "Idempotency-Key", key);
            var second = call("POST", "/orders", goodOrder, "Idempotency-Key", key);
            expectStatus(first, 201);
            expectStatus(second, 200);
            expect(field(first.body(), "id").equals(field(second.body(), "id")), "a retry created a DUPLICATE order");
            expect("true".equals(second.headers().firstValue("Idempotent-Replayed").orElse(null)), "missing Idempotent-Replayed: true header");
        });
        test("Different Idempotency-Keys -> different orders", () -> {
            var a = call("POST", "/orders", goodOrder, "Idempotency-Key", "a-" + System.nanoTime());
            var b = call("POST", "/orders", goodOrder, "Idempotency-Key", "b-" + System.nanoTime());
            expect(!field(a.body(), "id").equals(field(b.body(), "id")), "different keys must create different orders");
        });

         */

        System.out.printf("%n%d passed, %d failed%s%n", passed, failed, failed == 0 ? "  -> Exercise 01 complete!" : "");
    }

    // ---------------- tiny test kit ----------------
    interface Body { void run() throws Exception; }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] " + name + "\n       " + t.getMessage());
        }
    }

    static HttpResponse<String> call(String method, String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(BASE + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void expect(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    static void expectStatus(HttpResponse<String> r, int status) {
        if (r.statusCode() != status)
            throw new AssertionError("expected HTTP " + status + " but got " + r.statusCode() + "  body=" + r.body());
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"([^\"]*)\"|[-\\d.]+|true|false|null)").matcher(json);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
