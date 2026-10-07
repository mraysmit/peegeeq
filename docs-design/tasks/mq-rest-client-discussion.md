

# REST Interfaces over Message Queues: Architecture, Trade-offs, and Mitigation

### Author

Mark A Ray-Smith Software Engineer



### Last Updated

Nov 2025

---

## 1. Context and Objective

Many teams consider exposing a **REST API façade** over a traditional **message broker** (e.g., Solace, ActiveMQ Artemis, RabbitMQ, Kafka) to let clients integrate via HTTP instead of broker-native protocols (AMQP, JMS, MQTT).

The motivation: ease of use (HTTP everywhere), firewall friendliness, and SaaS/webhook integration.
The risk: loss of core message-queue guarantees and performance characteristics.

This discussion lays out:

* The **pros and cons** of such an approach
* A **criticality-ordered feature comparison** (native vs REST)
* **Quantitative performance deltas**
* **Synchronous vs asynchronous concerns** (now integrated across tables)
* **Mitigation strategies** and residual risks
* **Architectural recommendations** for Solace and Artemis
* **When to accept** (and when not to accept) this trade-off

---


## 2. Typical Motivations

### 2.1 Why some teams prefer REST over MQ

* **Platform / Infra team**
    * **Ubiquity of HTTP**: everything speaks HTTP; TLS, proxies, WAFs, API gateways already exist.
    * **Network friendliness**: traverses NAT/firewalls cleanly; mTLS/JWT offload is standard.
* **Security team**
    * **Uniform controls**: OAuth2 scopes, mTLS, WAF rules, DLP, audit all live at the edge.
    * **Vendor risk**: fewer language/native dependencies on endpoints you don’t control.
* **Consumer / Integration team**
    * **Polyglot + zero-install**: partners avoid JMS/AMQP drivers; webhooks are familiar.
    * **Contract-first**: OpenAPI governance, schema linting, rate limits—easier to standardize.
    * **Time-to-first-event**: faster onboarding → more integrations.
    * **Team autonomy**: REST competences are widespread; reduces specialized hiring.

### 2.2 Systems theory & architecture drivers

* **Loose coupling over time**
  HTTP is a coarse-grained, failure-tolerant boundary; it discourages chatty coupling and forces explicit contracts. That’s good at system boundaries.
* **Layered intermediaries**
  REST fits API gateways, auth proxies, SA Service Eedge, Layer 7 rate-limiters. You get policy injection “for free,” which is somewhat harder with raw MQ protocols.
* **Cost of coordination across segreagated enterprises**
  Conway’s Law effect is siloed teams: multiple teams across stacks gravitate to the lowest common denominator (HTTP) to reduce coordination friction.

### 2.3 Networking & protocol realities (why HTTP “just works”)

* **Middlebox compatibility**: proxies, caches (where applicable), TLS termination, observability agents—HTTP is first-class.
* **Client simplicity**: standard libs, easy retries, easy auth; no long-lived socket tuning or credit windows to understand.
* **Downside (acknowledged)**: HTTP is stateless request/response by default, so you lose built-in **credit-based flow** and **session semantics** that MQs rely on.

### 2.4 Reliability & queueing theory (why queues help—but HTTP can clash)

* **Little’s Law (L = λ × W)**
  If you block HTTP until downstream work finishes, you inflate **W** (time in system) and need many more connections/threads (L) to sustain arrival rate (λ). That’s why **202 + async status** is the sane pattern.
* **Backpressure necessity**
  Without credits, producers can drive λ > μ (service rate), exploding queue depth and latency variance. MQs embed flow control; HTTP needs **leases/credits** to emulate it.
* **Variance amplification**
  Heavy-tailed service times make synchronous HTTP unstable under burst; decoupling with a queue smooths variance—**if** you don’t reintroduce sync waits.


### 2.5 Distributed-systems constraints you’re dealing with

* **FLP & CAP realities**
  Network partitions and crashes mean you’ll choose **at-least-once + idempotency** over mythical exactly-once. HTTP doesn’t change that it just pushes the logic into your API and clients.#

* **Idempotency > transactions**
  End-to-end exactly-once is impractical across boundaries. Idempotent handlers + outbox are the proven patterns.

* **Clock & session loss**
  HTTP’s short-lived sessions lose implicit progress (like cursors and delivery state), so you need **resume tokens** and explicit offsets.
* When using **Server-Sent Events (SSE)** or WebSockets, treat the connection as **soft state** you must periodically checkpoint the broker offset or message ID in the stream. On reconnect, use `Last-Event-ID` or a custom `?resume=offset` to rejoin without message loss or duplication.

### 2.6 Economics (why the trade-off might work)

* **Adoption cost down**: partners integrate faster; fewer custom SDKs to maintain.
* **Ops cost shifts**: you pay later by re-implementing MQ semantics (acks, backpressure, DLQ, replay) at the edge.
* **Total cost calculus**: worthwhile for low/medium volumes and external edges; costly for high-rate internal streams.

### 2.7 When the motivations are valid vs. invalid

* **Valid (go REST)**

    * External partners, **low to moderate** volume, loose SLAs.
    * Human- or SaaS-driven events, webhook-friendly.
    * Org needs standardized auth/GRC controls at the edge.
* **Invalid (don’t RESTify core)**

    * You need **strict ordering**, **sub-10ms p99**, or **transactions**.
    * High fan-out/fan-in streams (thousands+ msg/s) where flow control is critical.
    * You control both sides and can ship a native client.

### 2.8 Decision checklist (quick yes/no)

1. Can you accept **at-least-once + idempotency** everywhere?
2. Is **ordering only per key** (not global) acceptable?
3. Will you avoid blocking HTTP for downstream work (**202 + status/callback/stream** only)?
4. Can consumers support **leases/credits** or SSE/WS (not naive webhooks) for backpressure?
5. Are **DLQ + replay** non-negotiable and staffed operationally?
6. Are p95 ≥ **10–30 ms** and p99 ≥ **80–250 ms** acceptable?
7. Do you have a plan for **JWT→ACL** mapping and policy-as-code to prevent drift?

### Distributed-systems constraints in computer science
> **FLP (Fischer, Lynch, and Patterson) impossibility result**: This theorem states that it's impossible to create a distributed consensus algorithm that is guaranteed to terminate in an asynchronous network where one or more nodes might fail by crashing.
>
>**CAP theorem**: This theorem states that in the presence of a network partition, a distributed data store must choose between Consistency (every read receives the most recent write) and Availability (every request receives a non-error response).
Relationship: Both FLP and CAP are impossibility results that highlight fundamental limitations in distributed systems, but they have different conditions and implications. FLP focuses on consensus with crash failures, while CAP deals with the trade-off between consistency and availability during network partitions.

---

## 3. Key Feature Degradation and Associated Risks

(Feature order below is **canonical** and used across all tables.)

| **#** | **Feature**                      | **Native MQ Client Feature**                                  | **REST Interface Challenge / Risk**                                                   |
| ----: | -------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
|     1 | **Delivery Semantics**           | At-least/at-most/exactly-once supported; broker-tracked acks. | Must emulate via idempotency keys; exactly-once unrealistic; dupes on retry/failover. |
|     2 | **Ordering Guarantees**          | Strict per queue/session or per partition.                    | Retries/parallel REST calls scramble order; per-key ordering needs custom design.     |
|     3 | **Backpressure / Flow Control**  | Credit-based, fine-grained; prevents overload.                | None natively; must fake via `429/Retry-After`; often ignored by clients.             |
|     4 | **Transactions / Sessions**      | Built-in commits/rollbacks; session acks.                     | Must reinvent ack/lease/commit semantics over HTTP.                                   |
|     5 | **Synchronous vs Asynchronous**  | Async by default; sync only for enqueue/confirm.              | Teams block HTTP on downstream work → timeouts, retry storms, broken SLAs.            |
|     6 | **Reconnect / Resume**           | Durable subs, link recovery, cursors.                         | Stateless; must poll or re-deliver; replay gaps without offsets/resume tokens.        |
|     7 | **Latency / Throughput**         | Binary protocol; 1–2 ms p95; very high throughput.            | 20–100 ms p95 typical; HTTP overhead throttles throughput heavily.                    |
|     8 | **Batching / Prefetch**          | Prefetch windows & batch acks.                                | One-message-per-call unless batches were added explicitly.                            |
|     9 | **TTL / DLQ / Delay / Priority** | Native, reliable.                                             | Lost unless re-implemented in API; easy to misconfigure.                              |
|    10 | **Observability**                | Lag, redeliveries, depth, consumer health.                    | HTTP RPS/p95 ≠ broker lag; mapping is lossy without dual-plane telemetry.             |
|    11 | **Security / ACLs**              | Fine-grained broker ACLs.                                     | Must mirror in gateway; drift likely without a single source of truth.                |
|    12 | **Selectors / Filters**          | Efficient broker-side selectors.                              | Re-creating selector semantics in HTTP is limited and costly.                         |
|    13 | **Protocol Overhead**            | Lightweight binary frames, persistent connections.            | Header-heavy, stateless HTTPS; JSON encoding costs.                                   |
|    14 | **Real-time / Streaming**        | Long-lived push with backpressure.                            | Polling/webhooks add latency; streaming requires stateful bridge.                     |
|    15 | **Client Libraries**             | Optimized SDKs per language.                                  | Any HTTP client works but lacks broker semantics (acks, credits, sessions).           |
|    16 | **Operational Complexity**       | Tuning clients, but semantics are built-in.                   | You rebuild semantics (acks, leases, DLQ, replay) → higher long-term ops cost.        |

---

## 4. Ranked Criticality with Quantitative Characteristics

(Same canonical order; ranks reflect impact on correctness/resilience/perf.)

| **#** | **Feature**              | **Δ Throughput**          | **Δ Latency**                  | **Risk** | **Criticality** |
| ----: | ------------------------ | ------------------------- | ------------------------------ | -------- | --------------- |
|     1 | Delivery Semantics       | –                         | +100–300% (retries)            | High     | 🔴 Critical     |
|     2 | Ordering                 | –                         | –                              | High     | 🔴 Critical     |
|     3 | Backpressure / Flow      | −30–50%                   | +50–100%                       | High     | 🔴 Critical     |
|     4 | Transactions / Sessions  | –                         | +200%                          | High     | 🔴 Critical     |
|     5 | **Synchronous vs Async** | −20–60% (blocked threads) | +100–500% if blocking for work | High     | 🔴 Critical     |
|     6 | Reconnect / Resume       | −40–70%                   | +100–200 ms                    | Med-High | 🟠 High         |
|     7 | Latency & Throughput     | ↓ 5–20×                   | ↑ 10–50×                       | Medium   | 🟠 High         |
|     8 | Batching / Prefetch      | ↓ 5–10×                   | ↑ 5–10×                        | Medium   | 🟠 High         |
|     9 | TTL / DLQ / Delay        | –                         | +25–50% ops overhead           | Medium   | 🟠 Medium       |
|    10 | Observability            | –                         | +10–20% ops effort             | Medium   | 🟡 Medium       |
|    11 | Security / ACLs          | –                         | –                              | Medium   | 🟡 Medium       |
|    12 | Selectors / Filters      | −20–40%                   | +50–100%                       | Low-Med  | 🟡 Medium       |
|    13 | Protocol Overhead        | ↓ 10–30×                  | ↑ 5–10×                        | Low      | 🟡 Medium       |
|    14 | Real-time / Streaming    | ↓ 10–50×                  | ↑ 100–1000%                    | Low-Med  | 🟡 Medium       |
|    15 | Client Libraries         | –                         | +0–5%                          | Low      | 🟢 Low          |
|    16 | Operational Complexity   | –                         | +50% ops cost                  | Low-Med  | 🟢 Low          |

---

### Quantitative Comparison (Baseline vs REST)

| **Metric**             | **Native MQ**   | **REST Proxy** | **Δ (approx)** |
| ---------------------- | --------------- | -------------- | -------------- |
| Throughput (1 core)    | 20k–100k msg/s  | 0.5k–2k msg/s  | ↓ 10–50×       |
| Median Latency         | 1–5 ms          | 30–100 ms      | ↑ 10–20×       |
| p99 Latency            | < 10 ms         | 200–1000 ms    | ↑ 20–100×      |
| CPU Cost per Message   | 50–150 µs       | 1–5 ms         | ↑ 10–40×       |
| Network Overhead       | < 200 B         | 1–2 KB         | ↑ 5–10×        |
| Concurrent Connections | 10k–100k        | 100–1k         | ↓ 100×         |
| Exactly-Once           | Supported (txn) | Not feasible   | –              |

---

## 5. Synchronous vs Asynchronous Concerns (Reference)

*(The content below is now represented as row **#5** in every table.)*

**Producer path (client → REST → MQ)**

* Accept + Confirm only (`201/202` on enqueue).
* Never block on downstream work; expose `Location: /operations/{id}`.
* Use publisher confirms for durability (not completion).
* `Idempotency-Key` mandatory per message.

**Consumer path (MQ → REST client)**

* Delivery is **at-least-once**; expose `Message-Id`, `Delivery-Count`, `Lease-Token`.
* Explicit `ACK/NACK/EXTEND`.
* Backpressure via leases/pull credits.

**Patterns**

* `202 + /operations/{id}` for async tracking.
* Use webhooks only with backoff/DLQ.
* Prefer SSE/WebSocket for live consumption.

---

## 6. Mitigation Matrix (same canonical order; includes Sync/Async)

| **#** | **Feature gap**              | **Mitigation**                                                            | **Key Technique**                                                        | **Residual Risk**                                | **Effort**                           |   |
| ----: | ---------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------ | ------------------------------------ | - |
|     1 | Delivery semantics           | Enforce idempotency; surface `Delivery-Count`; poison-message routing.    | Redis/RocksDB idempotency table; hash+TTL; immediate DLQ on schema fail. | Duplicates possible across boundaries.           | M                                    |   |
|     2 | Ordering                     | Per-key ordering; partition; single in-flight per key.                    | `Ordering-Key` + serialize; block N+1 until N ack/lease-expiry.          | Minor reorder on rebalancing.                    | M                                    |   |
|     3 | Backpressure                 | Credits/leases; publish throttling; outbound circuit breaker + backoff.   | Pull/SSE > push; `429/Retry-After`; jitter; DLQ parking.                 | Partners may ignore throttling.                  | M                                    |   |
|     4 | Transactions / Sessions      | App-level outbox; explicit ACK/NACK/EXTEND; compensations.                | Never XA/2PC; track processed offsets; transactional outbox in services. | No atomic cross-resource commits.                | M–L                                  |   |
|     5 | **Synchronous vs Async**     | **Accept+confirm only**; externalize completion (status/callback/stream). | `202 + Location`; SSE/WS; short timeouts; circuit breakers; idempotency. | Blocking patterns may creep back in via retries. | M                                    |   |
|     6 | Reconnect / Resume           | Resume tokens; broker offsets; durable subs behind bridge.                | SSE/WS with `brokerOffset`; `?resume=offset                              | time`; optional WAL.                             | Short gaps possible on bridge crash. | M |
|     7 | Latency / Throughput         | Batch send/receive; HTTP/2; keep-alive; compression.                      | `max=N&wait=25s`; ack arrays; gzip; no 1-msg/1-call.                     | Still slower than native.                        | S–M                                  |   |
|     8 | Batching / Prefetch          | Prefetch == maxInFlight; coalesced acks.                                  | Per-connection/tenant caps; lease tokens; batch publish/consume.         | Efficiency only; doesn’t fix HTTP overhead.      | S                                    |   |
|     9 | TTL / DLQ / Delay / Priority | Map 1:1 into API; first-class replay endpoints.                           | Clamp TTL; broker scheduling for delay; `/deadletters`, `/replay`.       | Priority semantics limited over REST.            | S–M                                  |   |
|    10 | Observability                | Dual-plane metrics + tracing.                                             | Lag, redelivery, DLQ depth, inflight; `traceparent` propagation.         | Some fidelity loss vs native.                    | S                                    |   |
|    11 | Security / ACLs              | JWT→ACL mapping; mTLS; HMAC for webhooks; policy as code.                 | Single source of truth (OPA/Git); rotate keys; cert pinning.             | Mapping/config drift remains possible.           | S–M                                  |   |
|    12 | Selectors / Filters          | Safe, whitelisted predicates; server-side filtering at bridge.            | Map limited fields; cache selector→subscription; deny arbitrary exprs.   | Complex predicates degrade performance.          | S–M                                  |   |
|    13 | Protocol Overhead            | HTTP/2 multiplex; persistent connections; binary payloads where possible. | Avoid chatty JSON for large payloads; compress; reuse connections.       | Still heavier than AMQP/MQTT.                    | S                                    |   |
|    14 | Real-time / Streaming        | Prefer SSE/WS over polling; heartbeats; resumable streams.                | Keep-alive 15s; `resume` tokens; per-key serialization in stream.        | Higher than native latency.                      | S–M                                  |   |
|    15 | Client Libraries             | Provide thin SDKs to hide complexity.                                     | Ship Java/Node/Python refs with idempotency, acks, backoff baked-in.     | Some clients roll their own poorly.              | S–M                                  |   |
|    16 | Operational Complexity       | Golden paths + runbooks; game days; auto-alerts.                          | DLQ replay playbook; outage drills; dashboards for lag/leases/429s.      | Ongoing ops tax persists.                        | S                                    |   |

---

### Quantitative “Before vs After Mitigation”

| **Metric**        | **REST (no fix)** | **REST (mitigated)** | **Native**    |
| ----------------- | ----------------- | -------------------- | ------------- |
| Median latency    | 30–100 ms         | 10–30 ms             | 1–5 ms        |
| p99 latency       | 200–1000 ms       | 80–250 ms            | < 10 ms       |
| Throughput / core | 0.5–2k msg/s      | 3–8k msg/s           | 20–100k msg/s |
| Duplication rate  | 0.5–2%            | 0.01–0.1%            | ~0            |
| Ordering errors   | High              | Near-0 per key       | 0             |
| DLQ under outage  | High              | Moderate             | Low           |

---

## 7. Recommended API Surface (Summary)

### Publish (Producers)

`POST /v1/publish/{destination}` → `201 Created`
Headers: `Idempotency-Key`, `Correlation-Id`, `Message-Id`
Body:

```json
{ "opId": "...", "statusUrl": "/v1/operations/{opId}" }
```

### Consume (Pull)

`GET /v1/messages/{subscription}?max=50&wait=25s`
Per message: `Lease-Token`, `Lease-Expires-At`
Ack via: `POST /v1/ack { "tokens": ["..."] }`

### Stream (Recommended)

`GET /v1/stream/{subscription}` (SSE/HTTP2)
Events: `message`, `heartbeat`, `control`
Acks via WS frames or `POST /ack`

### Dead Letters & Replay

`GET /v1/deadletters`
`POST /v1/replay?from=offset|time`

### Backpressure

* Ingress: token bucket → `429 + Retry-After`
* Egress: per-connection `maxInFlight` leases

---

## 8. Observability Model

| **Plane**         | **Key Metrics**                          | **Example Alerts**                                |
| ----------------- | ---------------------------------------- | ------------------------------------------------- |
| **Publish plane** | RPS, confirm latency, 429 rate           | `>1% 5xx`, surge in idempotency reuse             |
| **Consume plane** | Consumer lag, redelivery rate, DLQ depth | `lag>30s`, `DLQ>threshold`, `lease_expired>5/min` |

All traces must propagate `traceparent`.
Never mix publish and consume metrics; they have different SLOs.

---

## 9. Broker-Specific Guidance

### Solace

* Use **Solace REST Messaging** for ingress (guaranteed delivery + confirms).
* Use **WebSocket/SSE bridge** for consumption; map Solace ack → lease token.
* Map Solace replay to `/replay?from=time|id`.
* Set `max-delivered-unacked` = your `maxInFlight`.

### ActiveMQ Artemis

* Ingress: **Apache Camel** (`rest → jms:queue`) with idempotent consumer EIP.
* Consumption: stateful SSE/WS bridge using **Core/AMQP** client; enforce per-key serialization.
* Tune `consumer-window-size ≈ maxInFlight * avgMsgSize`.
* Map Artemis DLQ → `/deadletters`.

---

## 10. When a REST Façade is Acceptable

✅ **Use it** when:

* External/partner clients; HTTP is the only realistic option.
* Low-volume, low-criticality flows.
* SLA tolerates 10–100 ms latency and occasional duplicates.

🚫 **Avoid it** when:

* You need strict ordering, high throughput, or sub-10 ms p99.
* Workflows depend on transactions/XA.
* You control both ends and can ship real clients.

---

## 11. Summary: Typically What You Trade for Simplicity…

| **Dimension**         | **Native MQ** | **REST Wrapper**           |
| --------------------- | ------------- | -------------------------- |
| Reliability           | Strong        | Medium (needs idempotency) |
| Latency               | Milliseconds  | Tens–hundreds of ms        |
| Throughput            | High          | Low–medium                 |
| Operational Cost      | Moderate      | High (custom semantics)    |
| Ease of Integration   | Low           | High                       |
| Long-term Scalability | Excellent     | Moderate–poor              |
| Developer Familiarity | Specialized   | Universal (HTTP)           |

**Rule of thumb:**

> REST + MQ = good *edge integration* pattern, bad *core messaging* pattern.

---

## 12. Systems that Offer a REST Messaging API

### Solace REST Messaging API

* **Pros:** first-class REST messages, guaranteed delivery, OAuth2/mTLS, good for edge/webhooks.
* **Cons:** HTTP lacks credits; ordering is limited; higher latency; REST GET is polling; not full transaction semantics.
* **Bottom line:** Great for **edge**; keep **core** consumers on SMF/MQTT/AMQP.

### IBM MQ REST Messaging & Admin

* **Pros:** IBM-supported messaging over HTTP; persistent puts/gets; simple for non-JMS stacks.
* **Cons:** One message per HTTP call; no streaming callbacks; no batching/flow control; 20–100 ms typical latency.
* **Bottom line:** **Safe but slow**; use for integration/admin/low-volume, not heavy workloads.

### RabbitMQ / Artemis / Cloud

* RabbitMQ HTTP API: **admin**, not message flow.
* Artemis: management REST only; use AMQP/JMS/STOMP or a bridge (Camel/Quarkus).
* Azure Service Bus / AWS SQS: REST-native with **visibility timeout**, **dedupe IDs**, **batch send/receive** that make REST viable at scale.

**Why vendors ship REST anyway:** lower onboarding friction, admin/monitoring/serverless integration but only a **subset** of broker semantics survives.

---

## 13. Mitigation Matrix Detailed + Simple Explanations (keeps same order)

**1) Delivery semantics** *Idempotency + DLQ*
HTTP retries cause duplicates; require **Idempotency-Key**, store & dedupe, expose `Delivery-Count`, DLQ on schema fail.

**2) Ordering** *Per-key serialization*
Use `Ordering-Key`; allow only one in-flight per key; partition for parallelism.

**3) Backpressure** *Credits/leases + throttling*
Cap in-flight; `429/Retry-After`; exponential backoff + jitter; park to DLQ.

**4) Transactions / Sessions** *Outbox & acks*
No XA; use app-level outbox and explicit `ACK/NACK/EXTEND`; track processed offsets.

**5) Synchronous vs Asynchronous** *Don’t block HTTP for work*
Respond `201/202` on enqueue; completion via status/callback/stream; prefer SSE/WS; add circuit breakers.

**6) Reconnect / Resume** *Offsets & resume tokens*
Include `brokerOffset`; clients reconnect with `?resume=offset|time`; consider bridge WAL.

**7) Latency / Throughput** *Batch + HTTP/2 + compression*
`max=N&wait=25s` for pull; coalesced acks; gzip; keep-alive.

**8) Batching / Prefetch** *Prefetch==maxInFlight*
Use per-connection caps; coalesce acks.

**9) TTL / DLQ / Delay / Priority** *Map 1:1 + replay*
Expose TTL/delay/priority; `/deadletters`, `/replay`; clamp ranges.

**10) Observability** *Dual-plane metrics*
Publish plane vs processing plane + tracing; don’t conflate SLAs.

**11) Security / ACLs** *JWT→ACL + mTLS + HMAC*
Single source of truth (OPA/Git); rotate keys; pin certs.

**12) Selectors / Filters** *Whitelisted server-side filters*
No arbitrary expressions; cache selector→subscription.

**13) Protocol Overhead** *HTTP/2 + reuse + binary where possible*
Avoid chatty JSON for large payloads; compress.

**14) Real-time / Streaming** *SSE/WS with heartbeats + resume*
Prefer streams over polling; per-key serialization on stream.

**15) Client Libraries** *Thin SDKs*
Bake in idempotency, acks, retries, backoff to reduce foot-guns.

**16) Operational Complexity** *Runbooks + game days*
DLQ replay playbooks; outage drills; alerts for lag/leases/429s.

---

## 14. Recommended Baseline Settings

| **Parameter**      | **Default**                 | **Why**                                         |
| ------------------ | --------------------------- | ----------------------------------------------- |
| `maxInFlight`      | 256                         | Stabilizes consumers; bounds memory.            |
| `lease`            | 60 s (max 10 min)           | Time to process; triggers redelivery on expiry. |
| `batchSize`        | 50                          | Good perf/latency trade-off.                    |
| `payloadLimit`     | 512 KB                      | Safe for HTTP + broker persistence.             |
| `wait` (long poll) | 25 s                        | Reduces churn; near-real-time pulls.            |
| `heartbeat`        | 15 s                        | Detects dead streams.                           |
| `retryBackoff`     | exp(1s → 5 min) ±20% jitter | Avoids retry storms; friendly backpressure.     |
| `maxAttempts`      | 25                          | Caps retries; pushes to DLQ when hopeless.      |
| `idempotencyTTL`   | 24–72 h                     | Realistic dedupe window.                        |

---

## 15. Simply Put

These mitigations won’t make HTTP equal to AMQP/JMS, but they **tame the biggest risks**:

* **Delivery & Ordering** – prevent duplicate/misordered effects.
* **Backpressure & Leases** – prevent overload.
* **Outbox & Replay** – recoverability and consistency.
* **Sync vs Async discipline** – don’t turn queues into slow RPC.
* **Observability & Security** – visibility and trust preserved.

> You can make REST-over-MQ behave decently but only by **re-implementing** much of what the broker already gave you.
