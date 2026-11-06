# Exercise 01 — REST: the QuickBite Order API

**Time:** ~60 min  **Port:** 8081  **You'll learn:** resources vs actions, HTTP verbs, status codes, pagination, idempotency

## The story
QuickBite's mobile app needs an API to place, view, list and cancel food orders. You're building it.

## Step 0 — Design before you code (10 min, on paper)
Fill this table **before** opening the code. Then compare with what the code expects.

| User action | Method | Path              | Success code | Failure codes                                          |
|---|--------|-------------------|--------------|--------------------------------------------------------|
| See the menu | Get    | /menu             | 200 OK       | 400 - Bad Request, 401 - Unauthorized, 404 - Not Found |
| Place an order | Post   | /orders           | 201 Created  | 400 - Bad Request, 401 - Unauthorized, 404 - Not Found |
| View one order | Get    | /orders/{orderId} | 200 OK       | 400 - Bad Request, 401 - Unauthorized, 404 - Not Found |
| See my orders, 10 at a time |        |                   |              |                                                        |
| Cancel an order |        |                   |              |                                                        |

## Step 1 — Run it
```bash
java OrderApi.java        # terminal 1 — just Java 21+, no Maven, no Spring
java Check.java           # terminal 2 — automatic checker, run it after every TODO
```
Every unfinished TODO answers **501 Not Implemented**, so the checker tells you exactly what's left.

Poke it by hand too:
```bash
curl -s localhost:8081/menu
curl -si -X POST localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"cust_7","items":[{"itemId":"masala-dosa","qty":2}]}'
```
`-i` shows the status line and headers. **Read the headers** — `Location` is part of the API.

## Step 2 — TODOs in `OrderApi.java`
| TODO | Endpoint | Key idea |
|---|---|---|
| 1 | `POST /orders` | Validate input → **400**; created → **201 + Location** |
| 2 | `GET /orders/{id}` | Missing → **404**, not 200 with `null` |
| 3 | `GET /orders?status=&limit=&offset=` | Filtering & offset pagination, return `total` |
| 4 | `PATCH /orders/{id}` | Business rule broken → **409 Conflict** (not 400!) |
| 5 | `Idempotency-Key` header | Safe retries for a non-idempotent POST |

## Step 3 — Think & answer (write 2–3 lines each)
1. What's the difference between **400**, **404**, **409** and **422**? Which did you use where, and why?
2. `GET`, `PUT`, `DELETE` are idempotent; `POST` isn't. What does that mean for a client that times out?
3. Why is there no `DELETE /orders/{id}`? (Hint: would Swiggy ever *delete* your order history?)
4. Cancelling is modelled as `PATCH {"status":"CANCELLED"}`. Another option is `POST /orders/{id}/cancel`. Argue for each.
5. Offset pagination breaks when new orders arrive while a user scrolls. What is **cursor pagination** and how would `GET /orders` change?
6. Where would API versioning go — `/v1/orders`, a header, or a query param? Pick one and defend it.

## Stretch goals
- Add `ETag` to `GET /orders/{id}` and return **304 Not Modified** when `If-None-Match` matches.
- Switch `/orders` to cursor pagination: return `nextCursor`, accept `?cursor=`.
- Return **429 Too Many Requests** with `Retry-After` if one customer places >5 orders a minute.
