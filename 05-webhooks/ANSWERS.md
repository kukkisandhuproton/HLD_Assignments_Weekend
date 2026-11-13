# Exercise 05 — Think & answer

## 1. Webhook vs polling `GET /payments/{id}`
Webhooks are fast and cheap: the merchant learns the result in about 1.5 s without sending thousands of "is it done yet?" requests. But delivery is best-effort. If the merchant is down for the whole retry window (1+2+4+8 s here, often hours or days at real gateways), for example during a long deploy, or if a bug returns 500s, the event is lost. Gateways offer the pull API so merchants can **reconcile**. You still need to poll when:
- an order stays `PENDING_PAYMENT` longer than expected (a reconciliation job checks it),
- you recover from an outage or restore a database,
- you want to confirm the current state before an irreversible action, such as shipping goods.

Use webhooks for speed, and polling for correctness.

## 2. Why HMAC instead of `X-Secret: abc`?
A static token proves only that the sender knows the token, once. If it leaks (logs, a proxy, a misconfigured endpoint), an attacker can attach it to any body forever. A static token also says nothing about the **content**: someone in the middle could change `failed` to `succeeded` and keep the header.

An HMAC is computed over the timestamp and the exact body, so it proves:
- **authenticity:** only someone with the secret could produce it,
- **integrity:** this exact body, unmodified.

The secret itself never travels over the network. A captured signature is valid only for that one body and timestamp.

## 3. Why sign the timestamp? What does `TOLERANCE_SECONDS` stop?
**Replay attacks.** An attacker who captures one valid webhook (body plus signature) could resend it later, for example to replay an old `payment.succeeded`. Because the timestamp is part of what is signed, the attacker cannot change it without breaking the signature. The receiver rejects anything older than 5 minutes, so a stolen request expires quickly. Idempotency on the event id covers replays inside that window.

## 4. Why `MessageDigest.isEqual` instead of `String.equals`?
`String.equals` returns at the first differing character, so a wrong guess that matches the first 10 characters takes slightly longer than one that matches 0. By measuring response times over many requests, an attacker can find the correct signature one character at a time: a **timing attack**. `MessageDigest.isEqual` always compares every byte, so its running time reveals nothing about where the mismatch is.

## 5. Why verify against the raw body bytes?
The signature covers the exact bytes the gateway sent. Parsing and re-serializing can change key order, whitespace, number formatting (`1.0` vs `1`), unicode escaping (`é` vs `é`) or trailing newlines. The JSON means the same thing, but the bytes differ, so a valid webhook fails verification. Verifying a re-serialized form is also weaker: you would be checking something other than what you received. Verify the raw bytes first, then parse.

## 6. Why answer 200 to a duplicate, not 409?
The gateway retries anything that is not 2xx. A 409 tells it "delivery failed", so it keeps resending an event you already handled, and you get alerts or a disabled endpoint for nothing. The status code tells the gateway "I have this event"; it does not report whether this particular copy changed anything. Delivery is at-least-once, the receiver is idempotent, and the combination behaves as exactly-once.

## 7. The handler takes 8 s, but the gateway times out at 5 s
At 5 s the gateway treats the attempt as failed, even though your handler is still running and will mark the order PAID and send the email. It then retries. Without idempotency, each retry sends another confirmation email: duplicate emails, possibly double side effects, and several handlers running at once for the same event.

**Fix:** respond fast and do the slow work asynchronously. Verify the signature, record the event id, persist the event or state change (ideally in the same database transaction as an outbox or queue row), return 200 in milliseconds, and let a background worker on a queue (SQS, Kafka, a DB-backed job table) send the email, with its own retries and idempotency key.

## 8. `payment.failed` arrives after `payment.succeeded`
Retries and parallel deliveries can reorder events, so arrival order is not event order. A late `failed` must **not** overwrite `PAID`. Guards:
- **State machine:** allow only legal transitions. `PENDING_PAYMENT → PAID` or `PENDING_PAYMENT → PAYMENT_FAILED` are fine; `PAID → PAYMENT_FAILED` is ignored or flagged. Terminal states stay terminal unless a separate event like `payment.refunded` arrives.
- **Ordering metadata:** compare the event's `createdAt` or a per-payment sequence/version with the last one applied, and drop older events (store `lastEventAt` on the order).
- **Ask the source of truth:** when in doubt, call `GET /payments/{id}` and apply the gateway's current status. Many teams treat the webhook only as "something changed, go fetch".

Note: the current `MerchantApp` follows the checker, which expects a later `succeeded` to flip a `PAYMENT_FAILED` order to `PAID`, so it applies the latest event it receives. A production version would add the transition guard above.
