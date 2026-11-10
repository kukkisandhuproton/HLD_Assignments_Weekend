using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace QuickBite.Rest;

/*
 * ORDER API — EXERCISE 01
 * ======================
 * This class hosts the QuickBite REST API on http://localhost:8081.
 *
 * It receives HTTP requests, chooses the correct handler, and sends
 * HTTP responses containing JSON.
 *
 * Orders are stored in memory. Restarting the server removes them.
 * The menu is predefined and does not change.
 *
 * Run:
 *   dotnet run --no-launch-profile -- server
 *
 * Check, in another terminal:
 *   dotnet run --no-launch-profile -- check
 *
 * Endpoints you will make work:
 *
 *   GET    /menu
 *          → 200 list of menu items (already done; use as an example)
 *
 *   POST   /orders
 *          → 201 + Location header (TODO 1, idempotency TODO 5)
 *
 *   GET    /orders/{id}
 *          → 200 / 404 (TODO 2)
 *
 *   GET    /orders?status=&limit=&offset=
 *          → 200 paginated list (TODO 3)
 *
 *   PATCH  /orders/{id} {"status":"CANCELLED"}
 *          → 200 / 400 / 409 (TODO 4)
 *
 * Request flow:
 *   RunAsync → RouteAsync → endpoint handler → SendAsync
 *
 * Some handlers read a JSON body before responding.
 * Unsupported methods go through MethodNotAllowedAsync.
 * Exceptions are handled by the request wrapper in RunAsync.
 *
 * ASP.NET Core handles the HTTP server.
 * System.Text.Json handles JSON parsing and serialization.
 * No custom Http.cs or Json.cs files are needed.
 */
public static class OrderApi
{
    public const int Port = 8081;

    // ---------- Domain ----------

    // The possible stages of an order.
    public enum Status
    {
        PLACED,
        PREPARING,
        OUT_FOR_DELIVERY,
        DELIVERED,
        CANCELLED
    }

    /*
     * MENU ITEM
     * =========
     * Describes a product that customers can order.
     *
     * Price is the price of one unit.
     * ItemId is the identifier used in order requests.
     */
    public sealed record MenuItem(
        string ItemId,
        string Name,
        int Price);

    /*
     * ORDER ITEM
     * ==========
     * Describes one line in an order.
     *
     * It contains the menu item's identifier and the quantity requested.
     * The price is looked up in Menu when calculating the order total.
     */
    public sealed record Item(
        string ItemId,
        int Qty);


    /*
     * ORDER
     * =====
     * Represents an order stored by the API and returned as JSON.
     *
     * This record contains the customer's items, calculated total,
     * current status, and creation time.
     *
     * WithStatus creates a replacement record when the status changes.
     * The caller must store that replacement in Orders.
     */
    public sealed record Order(
        string Id,
        string CustomerId,
        List<Item> Items,
        int Total,
        Status Status,
        string CreatedAt)
    {
        // DATA HELPER:
        // Return a copy with a different status.
        // The existing record is not modified.
        public Order WithStatus(Status status)
        {
            return this with { Status = status };
        }
    }

    // The menu is given. Use these prices when calculating totals.
    private static readonly Dictionary<string, MenuItem> Menu = new()
    {
        ["paneer-tikka"] =
            new MenuItem("paneer-tikka", "Paneer Tikka", 249),

        ["masala-dosa"] =
            new MenuItem("masala-dosa", "Masala Dosa", 129),

        ["veg-biryani"] =
            new MenuItem("veg-biryani", "Veg Biryani", 219),

        ["gulab-jamun"] =
            new MenuItem("gulab-jamun", "Gulab Jamun", 79)
    };

    // ---------- In-memory storage ----------

    // HTTP requests can run concurrently.
    // Use this lock when reading or changing Orders.
    private static readonly object OrdersLock = new();

    private static readonly Dictionary<string, Order> Orders = new();

    // key -> orderId
    private static readonly ConcurrentDictionary<string, string> IdempotencyKeys = new();

    // Use Interlocked.Increment(ref sequence) to generate the next number.
    private static int sequence = 1000;

    // Convert C# property names to camelCase JSON.
    // Convert status enum values to strings such as "PLACED".
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() }
    };

    // STEP 2-SERVER:
    // Build and start the HTTP server.
    // This method keeps running until the application is stopped.
    public static async Task RunAsync(string[] args)
    {
        var builder = WebApplication.CreateBuilder(args);
        await using var app = builder.Build();

        // STEP 3: Executed once for every incoming HTTP request.
        //
        // This wrapper converts exceptions into HTTP responses:
        //   unfinished TODO → 501
        //   invalid request → 400
        //   unexpected bug  → 500
        //
        // HttpContext contains this request and its response.
        app.Run(async context =>
        {
            try
            {
                await RouteAsync(context);
            }
            catch (OperationCanceledException)
                when (context.RequestAborted.IsCancellationRequested)
            {
                // The client disconnected or cancelled its request.
            }
            catch (NotImplementedException exception)
            {
                await TrySendAsync(
                    context,
                    501,
                    new { error = exception.Message });
            }
            catch (BadHttpRequestException exception)
            {
                await TrySendAsync(
                    context,
                    exception.StatusCode,
                    new { error = exception.Message });
            }
            catch (JsonException)
            {
                await TrySendAsync(
                    context,
                    StatusCodes.Status400BadRequest,
                    new { error = "Malformed JSON or invalid field types" });
            }
            catch (Exception exception)
            {
                Console.Error.WriteLine(exception);

                await TrySendAsync(
                    context,
                    500,
                    new { error = "Server bug: " + exception.Message });
            }
        });

        Console.WriteLine(
            $"QuickBite Order API running on http://localhost:{Port}");

        await app.RunAsync($"http://localhost:{Port}");
    }

    // ---------- Routing (given) ----------

    // STEP 4:
    // Inspect the URL path and choose the appropriate handler.
    // Only the handler matching the request is called.
    private static async Task RouteAsync(HttpContext context)
    {
        string path = context.Request.Path.Value ?? "/";

        string[] parts = path.Split(
            '/',
            StringSplitOptions.RemoveEmptyEntries);

        if (parts.Length == 1 && parts[0] == "menu")
        {
            await MenuAsync(context);
            return;
        }

        if (parts.Length >= 1 && parts[0] == "orders")
        {
            await OrdersAsync(context, parts);
            return;
        }

        if (parts.Length >= 2 &&
            parts[0] == "admin" &&
            parts[1] == "orders")
        {
            await AdminAdvanceAsync(context, parts);
            return;
        }

        await SendAsync(
            context,
            404,
            new { error = "No such resource" });
    }

    // STEP 5A — /menu:
    // Return all menu items for GET requests.
    // Other HTTP methods receive 405 Method Not Allowed.
    private static async Task MenuAsync(HttpContext context)
    {
        if (context.Request.Method != "GET")
        {
            await MethodNotAllowedAsync(context, "GET");
            return;
        }

        await SendAsync(context, 200, Menu.Values.ToList());
    }

    // STEP 5B — /orders or /orders/{id}:
    // Choose the order operation using the HTTP method and URL.
    private static async Task OrdersAsync(
        HttpContext context,
        string[] parts)
    {
        string method = context.Request.Method;

        if (parts.Length == 1)
        {
            // /orders
            switch (method)
            {
                case "POST":
                    await CreateOrderAsync(context);
                    return;

                case "GET":
                    await ListOrdersAsync(context);
                    return;

                default:
                    await MethodNotAllowedAsync(context, "GET, POST");
                    return;
            }
        }

        if (parts.Length == 2)
        {
            // /orders/{id}
            string id = parts[1];

            switch (method)
            {
                case "GET":
                    await GetOrderAsync(context, id);
                    return;

                case "PATCH":
                    await PatchOrderAsync(context, id);
                    return;

                // Think: why is there no DELETE here?
                // See README, question 3.
                default:
                    await MethodNotAllowedAsync(context, "GET, PATCH");
                    return;
            }
        }

        await SendAsync(
            context,
            404,
            new { error = "No such resource" });
    }

    // ---------- Handlers ----------

    /*
     * STEP 6A — CREATE AN ORDER
     *
     * POST /orders
     * Body:
     * {"customerId":"cust_7","items":[{"itemId":"masala-dosa","qty":2}]}
     *
     * TODO(1): Validate and create an order.
     *
     * - customerId must be a non-blank string
     *     → otherwise 400
     *
     * - items must be a non-empty list
     *     → otherwise 400
     *
     * - every itemId must exist in Menu, qty must be 1..20
     *     → otherwise 400 with a helpful message
     *
     * - total = sum(price * qty)
     * - id = "ord_" + Interlocked.Increment(ref sequence)
     * - status = Status.PLACED
     * - createdAt = DateTimeOffset.UtcNow.ToString("O")
     *
     * - respond 201 Created with:
     *     Location: /orders/{id}
     *   and the order as the response body
     *
     * TODO(5) (do this after the others pass): Idempotency.
     *
     * If the request has an "Idempotency-Key" header already seen,
     * do NOT create a new order.
     *
     * Return 200 with the ORIGINAL order and:
     *   Idempotent-Replayed: true
     *
     * Otherwise remember key -> orderId after creating.
     *
     * Why? A mobile app on flaky 4G retries POST — the user must not
     * get charged twice.
     *
     * Concurrency hint:
     * ConcurrentDictionary makes individual operations thread-safe.
     * Checking a key and creating an order is a sequence of operations;
     * consider how to protect that whole sequence from simultaneous retries.
     */
    private static async Task CreateOrderAsync(HttpContext context)
    {
        // 1. Convert the incoming JSON into a request object.
        // Malformed JSON must produce 400, rather than a server error.
        Order? requestOrder;
        
         try
        {
            requestOrder = await context.Request.ReadFromJsonAsync<Order>();
        }
        catch (System.Text.Json.JsonException exception)
        {
            Console.WriteLine("DEBUG: JSON exception caught");
            Console.WriteLine(exception);

            await SendAsync(
                context,
                StatusCodes.Status400BadRequest,
                new { error = "Malformed JSON or invalid field types" });


            return;
        }

        // 2. Validate the customer.
        if(requestOrder is null || string.IsNullOrEmpty(requestOrder.CustomerId))
        {
            await SendAsync(
                context,
                StatusCodes.Status400BadRequest,
                new { error = "Invalid request body" });
            return;
        }

        // 3. Validate that at least one item was supplied.
        if(requestOrder.Items is null || requestOrder.Items.Count == 0)
        {
            await SendAsync(
                context,
                StatusCodes.Status400BadRequest,
                new { error = "Items list cannot be empty" });
            return;
        }

        // 4. Validate each item and calculate the total using menu prices.
        var items= new List<Item>();
        int total=0;

        foreach(var item in requestOrder.Items)
        {
            if(!Menu.ContainsKey(item.ItemId))
            {
                await SendAsync(
                    context,
                    StatusCodes.Status400BadRequest,
                    new { error = $"ItemId {item.ItemId} does not exist in menu" });
                return;
            }

            if(item.Qty < 1 || item.Qty > 20)
            {
                await SendAsync(
                    context,
                    StatusCodes.Status400BadRequest,
                    new { error = $"Quantity for itemId {item.ItemId} must be between 1 and 20" });
                return;
            }

            items.Add(item);
            total += Menu[item.ItemId].Price * item.Qty;
        }

        // 5. Read the optional idempotency key.
        string? idemKey = context.Request.Headers["Idempotency-Key"].FirstOrDefault();
       
        bool replayed;

        // 6. Protect the entire check-and-create operation.
        // Two simultaneous requests with the same key must not both
        // create an order.

        lock (OrdersLock)
        {
            if (idemKey is not null &&
                IdempotencyKeys.TryGetValue(idemKey, out string? existingId))
            {
                // This key already belongs to an order.
                requestOrder = Orders[existingId];
                replayed = true;
            }
            else
            {
                // Generate the server-owned fields.
                string id = "ord_" + Interlocked.Increment(ref sequence);

                requestOrder = new Order(
                    Id: id,
                    CustomerId: requestOrder.CustomerId,
                    Items: items,
                    Total: total,
                    Status: Status.PLACED,
                    CreatedAt: DateTimeOffset.UtcNow.ToString("O"));

                // Save the order in memory.
                Orders[requestOrder.Id] = requestOrder;

                // Remember which order was created for this key.
                if (idemKey is not null)
                {
                    IdempotencyKeys[idemKey] = requestOrder.Id;
                }

                replayed = false;
            }
        }

         // 7. Send the response AFTER releasing the lock.
         // C# does not allow await inside a lock block.
        if (replayed)
        {
            context.Response.Headers["Idempotent-Replayed"] = "true";

            await SendAsync(context, StatusCodes.Status200OK, requestOrder);
            return;
        }


        
        context.Response.Headers["Location"] = $"/orders/{requestOrder.Id}";
        await SendAsync(context, StatusCodes.Status201Created, requestOrder);

        
    }

    /*
     * STEP 6B — LIST ORDERS
     *
     * GET /orders?status=PLACED&limit=10&offset=0
     *
     * TODO(3): Filter + paginate.
     *
     * - status is optional; an unknown status value → 400
     * - limit defaults to 10 (max 50)
     * - offset defaults to 0
     * - bad numbers → 400
     *
     * Respond 200 with:
     * {
     *   "items": [...],
     *   "total": <count after filtering>,
     *   "limit": n,
     *   "offset": n
     * }
     *
     * Hint: context.Request.Query gives you the query parameters.
     * Use a consistent order before applying pagination.
     */
    private static Task ListOrdersAsync(HttpContext context)
    {
        IQueryCollection query = context.Request.Query;

        // 1. Validate the optional status filter.
        // Match enum names exactly, such as PLACED or CANCELLED.
        // Checking the names also prevents numeric enum values being accepted.
        Status? statusFilter = null;

        if (query.TryGetValue("status", out var statusValue))
        {
            string statusText = statusValue.ToString();

            if (!Enum.GetNames<Status>().Contains(statusText))
            {
                throw new BadHttpRequestException(
                    "Unknown status: " + statusText,
                    StatusCodes.Status400BadRequest);
            }

            statusFilter = Enum.Parse<Status>(statusText);
        }

        // 2. Read limit, defaulting to 10.
        // Only allow page sizes from 1 through 50.
        int limit = 10;

        if (query.TryGetValue("limit", out var limitValue))
        {
            if (!int.TryParse(limitValue.ToString(), out limit) ||
                limit < 1 ||
                limit > 50)
            {
                throw new BadHttpRequestException(
                    "limit must be an integer between 1 and 50",
                    StatusCodes.Status400BadRequest);
            }
        }

        // 3. Read offset, defaulting to 0.
        // Offset is the number of matching orders to skip.
        int offset = 0;

        if (query.TryGetValue("offset", out var offsetValue))
        {
            if (!int.TryParse(offsetValue.ToString(), out offset) ||
                offset < 0)
            {
                throw new BadHttpRequestException(
                    "offset must be a non-negative integer",
                    StatusCodes.Status400BadRequest);
            }
        }

        // 4. Create a snapshot while holding the shared lock.
        // This copies references into a new list, not the Order objects.
        List<Order> orders;

        lock (OrdersLock)
        {
            orders = Orders.Values.ToList();
        }

        // 5. Filter the snapshot in place.
        // Removing entries here does NOT delete orders from the dictionary.
        if (statusFilter.HasValue)
        {
            orders.RemoveAll(
                order => order.Status != statusFilter.Value);
        }

        // 6. Sort the snapshot in place by creation time.
        // Use the unique order ID to break ties consistently.
        orders.Sort((a, b) =>
        {
            int comparison =
                string.CompareOrdinal(a.CreatedAt, b.CreatedAt);

            return comparison != 0
                ? comparison
                : string.CompareOrdinal(a.Id, b.Id);
        });

        // 7. Count ALL matching orders before pagination.
        int total = orders.Count;

        // 8. Skip offset orders and return up to limit orders.
        // An offset beyond the available results produces an empty page.
        List<Order> items = orders
            .Skip(offset)
            .Take(limit)
            .ToList();

        // 9. Return the page and its pagination information.
        return SendAsync(context, StatusCodes.Status200OK, new
        {
            items,
            total,
            limit,
            offset
        });
    }

    /*
     * STEP 6C — GET ONE ORDER
     *
     * GET /orders/{id}
     *
     * TODO(2):
     * Return 200 with the order, or 404 {"error":"..."} if missing.
     */
    private static Task GetOrderAsync(HttpContext context, string id)
    {
        Order? order;

        // 1. Look up the order by its ID while holding the shared lock.
        // TryGetValue returns null in 'order' if the key is missing.
        lock (OrdersLock)
        {
            Orders.TryGetValue(id, out order);
        }

        // 2. If the order does not exist, return 404 with an error.
        if (order is null)
        {
            return SendAsync(
                context,
                StatusCodes.Status404NotFound,
                new { error = $"Order '{id}' not found" });
        }

        // 3. Return 200 with the order as JSON.
        // Send the response after releasing the lock.
        return SendAsync(
            context,
            StatusCodes.Status200OK,
            order);
    }

    /*
     * STEP 6D — CANCEL AN ORDER
     *
     * PATCH /orders/{id}
     * Body: {"status":"CANCELLED"}
     *
     * TODO(4):
     * Customers may only CANCEL, and only while the order is PLACED.
     *
     * - order missing
     *     → 404
     *
     * - status missing or anything but CANCELLED
     *     → 400 (a customer cannot mark an order DELIVERED!)
     *
     * - order not in PLACED
     *     → 409 Conflict
     *       {"error":"cannot cancel an order that is PREPARING"}
     *
     * - otherwise
     *     → 200 with the updated order
     */
    private static async Task PatchOrderAsync(
        HttpContext context,
        string id)
    {
        // 1. Read the JSON body.
        // ReadBodyAsync rejects empty or malformed JSON with 400.
        Dictionary<string, JsonElement> body =
            await ReadBodyAsync(context);

        int responseStatus;
        object responseBody;

        // 2. Protect the lookup, status check, and update together.
        // Another request cannot change the order between these steps
        // if it also uses OrdersLock.
        lock (OrdersLock)
        {
            if (!Orders.TryGetValue(id, out Order? order))
            {
                // 3. The order does not exist.
                responseStatus = StatusCodes.Status404NotFound;
                responseBody = new
                {
                    error = $"Order '{id}' not found"
                };
            }
            else if (
                !body.TryGetValue("status", out JsonElement statusValue) ||
                statusValue.ValueKind != JsonValueKind.String ||
                statusValue.GetString() != "CANCELLED")
            {
                // 4. Customers can only request CANCELLED.
                responseStatus = StatusCodes.Status400BadRequest;
                responseBody = new
                {
                    error = "status must be CANCELLED"
                };
            }
            else if (order.Status != Status.PLACED)
            {
                // 5. Cancellation is only allowed while still PLACED.
                responseStatus = StatusCodes.Status409Conflict;
                responseBody = new
                {
                    error = $"cannot cancel an order that is {order.Status}"
                };
            }
            else
            {
                // 6. Create a copy with the new status and store it.
                Order updated = order.WithStatus(Status.CANCELLED);
                Orders[id] = updated;

                responseStatus = StatusCodes.Status200OK;
                responseBody = updated;
            }
        }

        // 7. Send the response after releasing the lock.
        await SendAsync(context, responseStatus, responseBody);
    }

    /*
     * STEP 5C — ADMIN ADVANCE (GIVEN)
     *
     * POST /admin/orders/{id}/advance
     *
     * Pretend this is the restaurant's tablet app.
     * Moves an order one step forward:
     *
     * PLACED → PREPARING → OUT_FOR_DELIVERY → DELIVERED
     *
     * CANCELLED orders remain CANCELLED.
     *
     * Check.cs uses this endpoint to test the 409 Conflict case.
     */
    private static async Task AdminAdvanceAsync(
        HttpContext context,
        string[] parts)
    {
        if (context.Request.Method != "POST" ||
            parts.Length != 4 ||
            parts[3] != "advance")
        {
            await SendAsync(
                context,
                404,
                new { error = "Use POST /admin/orders/{id}/advance" });

            return;
        }

        Order? updated = null;

        // Read and update together under the same lock.
        // Do not await network operations while holding this lock.
        lock (OrdersLock)
        {
            if (Orders.TryGetValue(parts[2], out Order? order))
            {
                Status next = order.Status switch
                {
                    Status.PLACED => Status.PREPARING,
                    Status.PREPARING => Status.OUT_FOR_DELIVERY,
                    Status.CANCELLED => Status.CANCELLED,
                    _ => Status.DELIVERED
                };

                updated = order.WithStatus(next);
                Orders[order.Id] = updated;
            }
        }

        if (updated is null)
        {
            await SendAsync(
                context,
                404,
                new { error = "Order not found" });

            return;
        }

        await SendAsync(context, 200, updated);
    }

    // =================================================================
    // Helpers below — you don't need to change anything here.
    // =================================================================

    // STEP 7 — READ A REQUEST BODY:
    // Called by handlers that need JSON input, such as POST and PATCH.
    //
    // Read the HTTP body, verify that it is a JSON object, and return
    // its properties as a dictionary of JsonElement values.
    //
    // Empty, malformed, or non-object JSON becomes HTTP 400 through
    // the exception handler in RunAsync.
    private static async Task<Dictionary<string, JsonElement>>
        ReadBodyAsync(HttpContext context)
    {
        using var reader = new StreamReader(
            context.Request.Body,
            Encoding.UTF8,
            detectEncodingFromByteOrderMarks: true,
            bufferSize: 1024,
            leaveOpen: true);

        string raw = await reader.ReadToEndAsync();

        if (string.IsNullOrWhiteSpace(raw))
        {
            throw new BadHttpRequestException(
                "Request body is required",
                StatusCodes.Status400BadRequest);
        }

        try
        {
            using JsonDocument document = JsonDocument.Parse(raw);

            if (document.RootElement.ValueKind != JsonValueKind.Object)
            {
                throw new BadHttpRequestException(
                    "Body must be a JSON object",
                    StatusCodes.Status400BadRequest);
            }

            var result = new Dictionary<string, JsonElement>();

            foreach (JsonProperty property
                     in document.RootElement.EnumerateObject())
            {
                // Clone so the value remains usable after the
                // JsonDocument is disposed at the end of this method.
                result[property.Name] = property.Value.Clone();
            }

            return result;
        }
        catch (JsonException exception)
        {
            throw new BadHttpRequestException(
                "Malformed JSON: " + exception.Message,
                StatusCodes.Status400BadRequest);
        }
    }

    // FINAL RESPONSE STEP:
    // Set the HTTP status and serialize the response body as JSON.
    // The request completes when the handler returns.
    private static async Task SendAsync(
        HttpContext context,
        int status,
        object? body)
    {
        context.Response.StatusCode = status;

        Console.WriteLine(
            $"{context.Request.Method,-6} " +
            $"{context.Request.Path}{context.Request.QueryString} " +
            $"-> {status}");

        if (status == StatusCodes.Status204NoContent)
        {
            return;
        }

        if (body is null)
        {
            context.Response.ContentLength = 0;
            return;
        }

        await context.Response.WriteAsJsonAsync(
            body,
            JsonOptions,
            cancellationToken: context.RequestAborted);
    }

    // ERROR RESPONSE STEP:
    // Try to send a JSON error response.
    //
    // Once a response has started, its status and headers cannot be
    // replaced. A disconnected client also cannot receive a response.
    private static async Task TrySendAsync(
        HttpContext context,
        int status,
        object body)
    {
        if (context.Response.HasStarted ||
            context.RequestAborted.IsCancellationRequested)
        {
            return;
        }

        try
        {
            await SendAsync(context, status, body);
        }
        catch
        {
            // The client may have disconnected while sending.
        }
    }

    // ALTERNATIVE RESPONSE STEP:
    // Return 405 for an HTTP method the endpoint does not support.
    // The Allow header tells the client which methods are available.
    private static Task MethodNotAllowedAsync(
        HttpContext context,
        string allow)
    {
        context.Response.Headers["Allow"] = allow;

        return SendAsync(
            context,
            StatusCodes.Status405MethodNotAllowed,
            new
            {
                error =
                    $"{context.Request.Method} not allowed here. " +
                    $"Allowed: {allow}"
            });
    }
}