using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;

namespace QuickBite.Rest;

/*
 * AUTOMATIC CHECKER — EXERCISE 01
 * ==============================
 * This class tests the REST API by sending real HTTP requests.
 *
 * It does not start the server and does not access the server's
 * in-memory Orders dictionary.
 *
 * Start the server first:
 *   dotnet run --no-launch-profile -- server
 *
 * Then run this checker in another terminal:
 *   dotnet run --no-launch-profile -- check
 *
 * How it works:
 *
 * 1. Verify that a server can be reached.
 * 2. Run each test in sequence.
 * 3. Send HTTP requests through CallAsync.
 * 4. Check responses with Expect and ExpectStatus.
 * 5. Print PASS or FAIL for each test.
 * 6. Print the final score.
 *
 * The tiny test kit is included at the bottom of this class.
 * No separate Kit class or test-framework package is required.
 *
 * The server TODOs are intentionally unfinished.
 * Failing tests show which assignment behaviors still need work.
 */
public static class Check
{
    private const string BaseUrl = "http://localhost:8081";

    private static readonly HttpClient Client = new();

    private static int passed;
    private static int failed;

    // STEP 2-CHECKER:
    // Run the assignment's tests in their original order.
    public static async Task RunAsync()
    {
        passed = 0;
        failed = 0;

        // STEP 3-CHECKER:
        // Confirm that a server is reachable before starting the tests.
        // This checks connectivity; the tests below check behavior.
        try
        {
            using var response =
                await Client.GetAsync(BaseUrl + "/menu");
        }
        catch (Exception exception)
            when (exception is HttpRequestException ||
                  exception is TaskCanceledException)
        {
            Console.WriteLine(
                $"Cannot reach {BaseUrl} -- start the server first: " +
                "dotnet run --no-launch-profile -- server");

            Environment.ExitCode = 1;
            return;
        }

        string goodOrder =
            "{\"customerId\":\"cust_7\",\"items\":[" +
            "{\"itemId\":\"masala-dosa\",\"qty\":2}," +
            "{\"itemId\":\"gulab-jamun\",\"qty\":1}]}";

        // Keep the created order's ID for later tests.
        string? id = null;

        Console.WriteLine("\n-- TODO(1) create order --");

        await TestAsync(
            "POST /orders with a valid body -> 201 Created",
            async () =>
            {
                var response =
                    await CallAsync("POST", "/orders", goodOrder);

                ExpectStatus(response, 201);

                id = Field(response.Body, "id");

                Expect(
                    id is not null && id.StartsWith("ord_"),
                    "body should contain the new order id");

                Expect(
                    Field(response.Body, "status") == "PLACED",
                    "new order status should be PLACED");

                Expect(
                    Field(response.Body, "total") == "337",
                    "total should be 2*129 + 79 = 337, got " +
                    Field(response.Body, "total"));

                string? location = response.Header("Location");

                Expect(
                    location == "/orders/" + id,
                    "Location header should be /orders/" + id +
                    " but was " + location);
            });

        await TestAsync(
            "missing customerId -> 400",
            async () => ExpectStatus(
                await CallAsync(
                    "POST",
                    "/orders",
                    "{\"items\":[{\"itemId\":\"masala-dosa\",\"qty\":1}]}"),
                400));

        await TestAsync(
            "empty items -> 400",
            async () => ExpectStatus(
                await CallAsync(
                    "POST",
                    "/orders",
                    "{\"customerId\":\"c1\",\"items\":[]}"),
                400));

        await TestAsync(
            "unknown itemId -> 400",
            async () => ExpectStatus(
                await CallAsync(
                    "POST",
                    "/orders",
                    "{\"customerId\":\"c1\",\"items\":[" +
                    "{\"itemId\":\"pizza\",\"qty\":1}]}"),
                400));

        await TestAsync(
            "qty 0 -> 400",
            async () => ExpectStatus(
                await CallAsync(
                    "POST",
                    "/orders",
                    "{\"customerId\":\"c1\",\"items\":[" +
                    "{\"itemId\":\"masala-dosa\",\"qty\":0}]}"),
                400));

        await TestAsync(
            "malformed JSON -> 400",
            async () => ExpectStatus(
                await CallAsync("POST", "/orders", "{oops"),
                400));

        Console.WriteLine("\n-- TODO(2) get order --");

        await TestAsync(
            "GET /orders/{id} -> 200 with that order",
            async () =>
            {
                var response = await CallAsync("GET", "/orders/" + id);

                ExpectStatus(response, 200);

                Expect(
                    id is not null && id == Field(response.Body, "id"),
                    "wrong order returned");
            });

        await TestAsync(
            "GET /orders/ord_999999 -> 404",
            async () => ExpectStatus(
                await CallAsync("GET", "/orders/ord_999999"),
                404));

        Console.WriteLine("\n-- TODO(3) list + filter + paginate --");

        // Given: create more orders so pagination can be tested.
        for (int i = 0; i < 3; i++)
        {
            await CallAsync("POST", "/orders", goodOrder);
        }

        await TestAsync(
            "GET /orders?limit=2 -> 200, at most 2 items, " +
            "has total/limit/offset",
            async () =>
            {
                var response =
                    await CallAsync("GET", "/orders?limit=2");

                ExpectStatus(response, 200);

                int items = Count(response.Body, "\"customerId\"");

                Expect(
                    items == 2,
                    "expected exactly 2 items in the page, got " + items);

                string? total = Field(response.Body, "total");

                Expect(
                    total is not null &&
                    int.Parse(total, CultureInfo.InvariantCulture) >= 4,
                    "total should count ALL matching orders (>= 4)");

                Expect(
                    Field(response.Body, "limit") == "2",
                    "response should echo limit");
            });

        await TestAsync(
            "GET /orders?limit=2&offset=1 returns a different " +
            "first item than offset=0",
            async () =>
            {
                var first = await CallAsync(
                    "GET", "/orders?limit=1&offset=0");

                var second = await CallAsync(
                    "GET", "/orders?limit=1&offset=1");

                string? a = Field(first.Body, "id");
                string? b = Field(second.Body, "id");

                Expect(
                    a is not null && a != b,
                    "offset is not being applied");
            });

        await TestAsync(
            "GET /orders?status=BOGUS -> 400",
            async () => ExpectStatus(
                await CallAsync("GET", "/orders?status=BOGUS"),
                400));

        await TestAsync(
            "GET /orders?limit=abc -> 400",
            async () => ExpectStatus(
                await CallAsync("GET", "/orders?limit=abc"),
                400));

        await TestAsync(
            "GET /orders?limit=500 -> 400 (max is 50)",
            async () => ExpectStatus(
                await CallAsync("GET", "/orders?limit=500"),
                400));

        Console.WriteLine("\n-- TODO(4) cancel with PATCH --");

        await TestAsync(
            "PATCH {status:CANCELLED} on a PLACED order -> 200",
            async () =>
            {
                var response = await CallAsync(
                    "PATCH",
                    "/orders/" + id,
                    "{\"status\":\"CANCELLED\"}");

                ExpectStatus(response, 200);

                Expect(
                    Field(response.Body, "status") == "CANCELLED",
                    "status should now be CANCELLED");
            });

        await TestAsync(
            "GET /orders?status=CANCELLED only returns cancelled orders",
            async () =>
            {
                var response = await CallAsync(
                    "GET",
                    "/orders?status=CANCELLED&limit=50");

                ExpectStatus(response, 200);

                Expect(
                    !response.Body.Contains("\"PLACED\""),
                    "filter returned a PLACED order");

                Expect(
                    id is not null && response.Body.Contains(id),
                    "the cancelled order is missing");
            });

        await TestAsync(
            "PATCH {status:DELIVERED} -> 400 (customers can't do that)",
            async () =>
            {
                var created =
                    await CallAsync("POST", "/orders", goodOrder);

                string? other = Field(created.Body, "id");

                ExpectStatus(
                    await CallAsync(
                        "PATCH",
                        "/orders/" + other,
                        "{\"status\":\"DELIVERED\"}"),
                    400);
            });

        await TestAsync(
            "PATCH cancel on a PREPARING order -> 409 Conflict",
            async () =>
            {
                var created =
                    await CallAsync("POST", "/orders", goodOrder);

                string? other = Field(created.Body, "id");

                await CallAsync(
                    "POST",
                    "/admin/orders/" + other + "/advance",
                    "");

                ExpectStatus(
                    await CallAsync(
                        "PATCH",
                        "/orders/" + other,
                        "{\"status\":\"CANCELLED\"}"),
                    409);
            });

        await TestAsync(
            "PATCH on a missing order -> 404",
            async () => ExpectStatus(
                await CallAsync(
                    "PATCH",
                    "/orders/ord_999999",
                    "{\"status\":\"CANCELLED\"}"),
                404));

        await TestAsync(
            "DELETE /orders/{id} -> 405 with an Allow header",
            async () =>
            {
                var response =
                    await CallAsync("DELETE", "/orders/" + id);

                ExpectStatus(response, 405);

                Expect(
                    response.Header("Allow") is not null,
                    "405 responses should say what IS allowed");
            });

        Console.WriteLine("\n-- TODO(5) idempotency --");

        await TestAsync(
            "Same Idempotency-Key twice -> one order, " +
            "second response replayed",
            async () =>
            {
                string key = "key-" + Guid.NewGuid().ToString("N");

                var first = await CallAsync(
                    "POST", "/orders", goodOrder,
                    "Idempotency-Key", key);

                var second = await CallAsync(
                    "POST", "/orders", goodOrder,
                    "Idempotency-Key", key);

                ExpectStatus(first, 201);
                ExpectStatus(second, 200);

                string? firstId = Field(first.Body, "id");

                Expect(
                    firstId is not null &&
                    firstId == Field(second.Body, "id"),
                    "a retry created a DUPLICATE order");

                Expect(
                    second.Header("Idempotent-Replayed") == "true",
                    "missing Idempotent-Replayed: true header");
            });

        await TestAsync(
            "Different Idempotency-Keys -> different orders",
            async () =>
            {
                var a = await CallAsync(
                    "POST", "/orders", goodOrder,
                    "Idempotency-Key", "a-" + Guid.NewGuid().ToString("N"));

                var b = await CallAsync(
                    "POST", "/orders", goodOrder,
                    "Idempotency-Key", "b-" + Guid.NewGuid().ToString("N"));

                string? aId = Field(a.Body, "id");
                string? bId = Field(b.Body, "id");

                Expect(
                    aId is not null && bId is not null && aId != bId,
                    "different keys must create different orders");
            });

        // FINAL CHECKER STEP:
        // Print the total number of passing and failing tests.
        Console.WriteLine(
            $"\n{passed} passed, {failed} failed" +
            (failed == 0 ? "  -> Exercise 01 complete!" : ""));
    }

    // ---------------- tiny test kit ----------------

    // STEP 4-CHECKER:
    // Run one test and record its outcome.
    //
    // Func<Task> is an asynchronous function passed as an argument.
    // A failed assertion throws an exception, which is caught here
    // so the remaining tests can still run.
    private static async Task TestAsync(
        string name,
        Func<Task> body)
    {
        try
        {
            await body();
            passed++;

            Console.WriteLine("  [PASS] " + name);
        }
        catch (Exception exception)
        {
            failed++;

            Console.WriteLine(
                "  [FAIL] " + name +
                "\n       " + exception.Message);
        }
    }

    // STEP 5-CHECKER:
    // Send an HTTP request and read the full response.
    //
    // body is optional: GET requests normally have no body.
    // headers contains alternating names and values:
    //   "Idempotency-Key", "example-key"
    //
    // The response is copied into our small Response record before
    // the underlying HttpResponseMessage is disposed.
    private static async Task<Response> CallAsync(
        string method,
        string path,
        string? body = null,
        params string[] headers)
    {
        if (headers.Length % 2 != 0)
        {
            throw new ArgumentException(
                "Headers must be supplied as name/value pairs.");
        }

        using var request = new HttpRequestMessage(
            new HttpMethod(method),
            BaseUrl + path);

        if (body is not null)
        {
            request.Content =
                new StringContent(body, Encoding.UTF8, "application/json");
        }

        for (int i = 0; i < headers.Length; i += 2)
        {
            if (!request.Headers.TryAddWithoutValidation(
                    headers[i],
                    headers[i + 1]))
            {
                request.Content ??=
                    new StringContent("", Encoding.UTF8, "application/json");

                request.Content.Headers.TryAddWithoutValidation(
                    headers[i],
                    headers[i + 1]);
            }
        }

        using var response = await Client.SendAsync(request);

        string responseBody =
            await response.Content.ReadAsStringAsync();

        var responseHeaders =
            new Dictionary<string, string>(
                StringComparer.OrdinalIgnoreCase);

        foreach (var header in response.Headers)
        {
            responseHeaders[header.Key] = header.Value.First();
        }

        // .NET exposes certain headers, including Allow,
        // through Content.Headers instead of Headers.
        foreach (var header in response.Content.Headers)
        {
            responseHeaders[header.Key] = header.Value.First();
        }

        return new Response(
            (int)response.StatusCode,
            responseBody,
            responseHeaders);
    }

    // STEP 6-CHECKER — ASSERT A CONDITION:
    // Throw when the condition is false.
    // TestAsync catches the exception and prints the failure message.
    private static void Expect(bool condition, string message)
    {
        if (!condition)
        {
            throw new Exception(message);
        }
    }

    // STEP 6-CHECKER — ASSERT AN HTTP STATUS:
    // Verify that an endpoint returned the expected status code.
    // Include the response body to make failures easier to understand.
    private static void ExpectStatus(Response response, int status)
    {
        if (response.StatusCode != status)
        {
            throw new Exception(
                $"expected HTTP {status} but got {response.StatusCode}" +
                "  body=" + response.Body);
        }
    }

    // RESPONSE-READING HELPER:
    // Pull "name": value out of JSON text using the first match.
    // This preserves the original Java checker's simple behavior,
    // including finding fields inside arrays.
    //
    // It is a small test helper, not a complete JSON parser.
    private static string? Field(string json, string name)
    {
        string pattern =
            "\"" + Regex.Escape(name) +
            "\"\\s*:\\s*(\"([^\"]*)\"|[-\\d.]+|true|false|null)";

        Match match = Regex.Match(json, pattern);

        if (!match.Success)
        {
            return null;
        }

        return match.Groups[2].Success
            ? match.Groups[2].Value
            : match.Groups[1].Value;
    }

    // RESPONSE-READING HELPER:
    // Count non-overlapping occurrences of a string.
    // The pagination test counts "customerId" fields to count orders.
    private static int Count(string text, string needle)
    {
        int count = 0;
        int index = 0;

        while ((index = text.IndexOf(
                    needle,
                    index,
                    StringComparison.Ordinal)) >= 0)
        {
            count++;
            index += needle.Length;
        }

        return count;
    }

    /*
     * RESPONSE
     * ========
     * A small container for the HTTP response data used by the tests.
     *
     * It stores the numeric status code, JSON text, and response headers.
     * Keeping these values allows CallAsync to dispose the underlying
     * HTTP response without losing data needed by the assertions.
     */
    private sealed record Response(
        int StatusCode,
        string Body,
        Dictionary<string, string> Headers)
    {
        // RESPONSE-READING HELPER:
        // Return the first value of a response header, or null if absent.
        // Header names are case-insensitive because of the dictionary
        // comparer selected in CallAsync.
        public string? Header(string name)
        {
            return Headers.TryGetValue(name, out string? value)
                ? value
                : null;
        }
    }
}