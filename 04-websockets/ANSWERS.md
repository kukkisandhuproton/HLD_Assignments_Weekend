# Exercise 04 — Think & answer

## 1. Why does WebSocket start as HTTP?
So it can travel everywhere HTTP already travels. It uses ports 80/443, which firewalls and corporate networks allow. It passes through existing proxies, TLS terminators and load balancers, which already understand an HTTP request with `Upgrade: websocket`. It can reuse cookies and auth headers, and one server can serve normal pages and WebSockets on the same port, as `ChatServer` does. A brand-new protocol on a new port would be blocked in many networks. The `Sec-WebSocket-Key`/`Accept` exchange proves the server really speaks WebSocket. Without it, a plain HTTP server could be tricked into treating the connection as WebSocket.

## 2. Why must browser→server frames be masked?
To stop cache-poisoning attacks on proxies that do not understand WebSocket. Without masking, malicious JavaScript could send bytes that look like `GET /app.js HTTP/1.1 Host: bank.com`. A transparent proxy could read that as a real HTTP request and cache the attacker's "response" for every other user. The browser picks a random 4-byte mask for each frame, and page script cannot control it, so the bytes on the wire are unpredictable and cannot be shaped into a valid HTTP request. Server→client frames do not need masking because the attacker controls the browser's script, not the server.

## 3. `ROOMS` lives in one JVM, but we run 3 instances behind a load balancer
Asha is on server A and Ravi is on server B. Each server's `ROOMS` only knows about its own sockets, so `broadcast` on A never reaches Ravi. They both see an "online" list containing only themselves, and their messages go nowhere.

Fixes:
- **Redis Pub/Sub:** each server subscribes to a channel per room (e.g. `room:ord_42`). When a message arrives on any server, that server publishes it to the channel. Every server receives it and writes it to its local sockets in that room. Delivery is fire-and-forget: if a server is down, it misses the messages.
- **Kafka (or Redis Streams):** the same fan-out, but messages are stored. A server that restarts, or a client that reconnects, can resume from an offset and replay history. You get ordering per partition (key the partition by room).
- Presence (the online list) moves to a shared store too, e.g. a Redis set per room with TTLs.

**Sticky sessions:** the load balancer always sends the same client to the same server, using a cookie or a hash of the IP. This keeps reconnects on the same box, but it does not solve this problem: Asha and Ravi are different clients and can still land on different servers. You could hash by order id so a whole room shares one server, but that creates hot spots and loses rooms when a node dies. A pub/sub backplane is the real fix.

## 4. How many open connections can one box hold?
Typically tens of thousands to around a million idle connections on a tuned Linux box. The limits:
- **File descriptors:** one per socket. Raise `ulimit -n` / `fs.file-max` (the defaults are often 1024).
- **Memory:** kernel TCP buffers (a few KB to tens of KB per socket) plus application state. With buffered streams, a `Client` and a thread stack, this is a few KB to 100+ KB per connection. 1M connections × 20 KB ≈ 20 GB.
- **Threads:** one platform thread per connection costs about 1 MB of stack and lots of context switching, which caps you at a few thousand. That is why `ChatServer` uses virtual threads (or you use NIO/epoll event loops such as Netty).
- **Ephemeral ports / 4-tuples:** these mostly limit proxies and load balancers that open outbound connections, not the server itself.
- **CPU:** broadcast fan-out (one message × N recipients), TLS and heartbeats, once traffic is heavy.

## 5. Ping/pong, and the phone in a tunnel
The **server** should send pings, or at least enforce a timeout. The server is the one holding the resources and maintaining presence. Clients may also ping to detect a dead connection quickly on their side, and browsers answer pongs automatically.

If the phone loses signal, no Close frame and no TCP FIN ever arrive. The server's blocking `readFrame` just waits. TCP keepalive may take hours to notice. Until then, the user stays in `ROOMS`, shows as "online", and every broadcast writes into a dead socket's buffer. With a heartbeat (ping every 20 s, close if there is no pong within 10 s), the server detects the dead connection within about 30 s, closes the socket, and runs `onClose`. The room is then told "Ravi left".

## 6. WebSocket vs "POST to send + SSE to receive"
WebSocket is better for high-frequency, low-latency, two-way traffic: games, collaborative editing, chat with typing indicators.

POST + SSE is better when:
- **Most traffic is server→client** and sends are rare (notifications, live order status, LLM token streaming).
- **Infrastructure is plain HTTP:** it works through every proxy, CDN and HTTP/2 or HTTP/3 multiplexing. Standard auth, rate limiting, logging, WAF rules and retries apply to each POST.
- **Sends need request/response semantics:** status codes, idempotency keys, validation errors per message.
- **You want automatic reconnect and resume for free:** `EventSource` reconnects and sends `Last-Event-ID`.
- **Scaling is simpler:** the POST goes to any stateless server, and only the SSE stream is long-lived.
