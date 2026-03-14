

# **Management Summary — REST Interfaces over Message Queues**

### Purpose

Expose a **REST façade** on top of an enterprise **message broker** (e.g., Solace, IBM MQ, Artemis) to let external or non-native clients publish/consume messages via HTTP rather than JMS/AMQP/MQTT.

---

### Strategic Motivations

* **Ease of integration** — any client or SaaS can speak HTTP; no SDKs or drivers needed.
* **Security & compliance** — HTTP fits existing controls (OAuth2, mTLS, WAF, API gateways).
* **Firewall/NAT traversal** — REST is enterprise-friendly compared to raw broker ports.
* **Partner enablement** — simplifies onboarding for vendors and external systems.
* **API governance alignment** — OpenAPI specs, rate limits, and observability integrate cleanly.



---

### **Core Risks / Technical Trade-offs**

* **Delivery semantics – HTTP can’t ensure exactly-once; must emulate with idempotency keys.**
  → Retries and timeouts at the HTTP layer will inevitably create duplicates; without idempotent processing, you’ll corrupt downstream state under load.

* **Ordering – no native session ordering; per-key serialization required.**
  → Once you fan out over stateless REST workers, retries and concurrency scramble message order — critical if your logic depends on sequence.

* **Backpressure – no credit-based flow control; must fake with 429s and leases.**
  → HTTP gives no feedback loop between producer and consumer speed, so a surge in messages can overrun slow endpoints and crash systems.

* **Transactions – XA and session commits don’t map to REST; only eventual consistency possible.**
  → You lose atomic multi-resource operations; correctness now depends on compensating logic and reliable outbox processing.

* **Synchronous blocking – REST encourages sync behavior; if misused, kills throughput and stability.**
  → Treating HTTP as a “wait-for-completion” pipeline ties up threads, spikes latency, and defeats the entire purpose of asynchronous decoupling.

* **Reconnect/resume – stateless HTTP loses consumer position; resume tokens mandatory.**
  → Every disconnect resets progress; without explicit offsets or resume cursors, clients either replay or skip messages unpredictably.

* **Latency & throughput – REST adds 10–50× latency, 10–30× lower throughput per core.**
  → TLS handshakes, headers, and per-call overhead dominate cost; you’ll need far more hardware to achieve the same message rate.

* **DLQ / TTL / replay – must rebuild these features at the API layer.**
  → Without native lifecycle management, you’ll re-implement dead-letter queues, expiry, and replays — more moving parts to maintain and tune.

* **Observability mismatch – need dual telemetry planes (HTTP + broker metrics).**
  → Message-level visibility (lag, depth, redelivery) doesn’t map to API metrics (RPS, latency), forcing you to correlate two different monitoring domains.

* **Security drift – ACLs must be mirrored between gateway and broker; prone to misalignment.**
  → Policy updates in one system won’t automatically propagate to the other, creating access mismatches and potential security holes.



---

### **Mitigation Summary (what we can realistically do)**

* **Idempotency store** – Redis/RocksDB deduplication keyed by `Message-ID`.
  → Ensures retried REST requests don’t create duplicate downstream side effects; essential for at-least-once delivery semantics.

* **Ordering keys** – One in-flight message per logical partition (customer, entity, etc.).
  → Preserves local ordering guarantees per key while allowing concurrency across unrelated streams, preventing global serialization bottlenecks.

* **Leases & credits** – Limit inflight deliveries; return `429 + Retry-After` on overload.
  → Provides artificial backpressure, preventing slow consumers from being overwhelmed and protecting broker stability under burst load.

* **Outbox pattern** – Replace XA with app-level atomic writes + async publish.
  → Achieves reliable event emission without distributed transactions, ensuring messages reflect committed business state.

* **Async design discipline** – Always respond `201/202` and offload work to queue.
  → Prevents long-lived HTTP blocking and allows horizontal scaling by decoupling acceptance from downstream completion.

* **Resume tokens** – Consumers reconnect with last offset (`?resume=offset|time`).
  → Enables durable delivery semantics and seamless recovery after connection loss without replaying or skipping messages.

* **Batching & HTTP/2** – Batch 50+ messages, keep connections alive.
  → Reduces per-message overhead, connection churn, and CPU cost, improving throughput by an order of magnitude.

* **Replay endpoints** – `/deadletters`, `/replay`, `/operations/{id}` for operational parity.
  → Gives operators tools to reprocess failures, inspect poison messages, and maintain observability equivalent to native MQ DLQs.

* **Tracing & metrics** – Propagate `traceparent`; monitor lag/redelivery vs HTTP latency.
  → Provides end-to-end visibility across HTTP and broker planes, crucial for diagnosing slowdowns and message loss.

* **Unified ACLs** – Sync JWT scopes ↔ broker ACLs via central policy-as-code.
  → Keeps authorization consistent across both layers, avoiding drift and ensuring secure, least-privilege access.

---


### Quantitative Impacts (approximate)

| Metric               | Native MQ     | REST façade                     | Δ               |
| -------------------- | ------------- | ------------------------------- | --------------- |
| Throughput/core      | 20–100k msg/s | 0.5–8k msg/s (with mitigations) | ↓ 10–30×        |
| Median latency       | 1–5 ms        | 10–30 ms                        | ↑ 10×           |
| p99 latency          | <10 ms        | 80–250 ms                       | ↑ 20×           |
| Duplication rate     | ~0            | 0.01–0.1%                       | slight          |
| Operational overhead | low           | high                            | ↑ 50%+ ops load |

---


### **Systemic Principles**

* **REST is synchronous; MQs are asynchronous** — bridging them is not lossless.
  → Every REST call implies immediate completion, but message delivery is deferred — if you blur that line, you’ll mislead clients about reliability and timing.

* **Don’t block HTTP** waiting for message processing; treat REST as an enqueue-only control plane.
  → If you block until downstream work finishes, you’ve effectively rebuilt a fragile synchronous RPC system on top of a queue — destroying decoupling and scalability.

* **SSE/WebSockets** can approximate streaming, but still require offset checkpointing.
  → Long-lived connections add reactivity, but without stored offsets or `Last-Event-ID`, reconnects will replay or skip messages, violating delivery guarantees.

* **Idempotency beats transactions** across distributed boundaries.
  → Distributed 2PC/XA never scales under failure; idempotent operations with retries let you survive partial commits and maintain correctness under load.

* **Async semantics must be contractually explicit** — not an implementation detail.
  → Clients must know that responses are provisional (`202 Accepted`), and that delivery, ordering, and ack semantics are governed by protocol — not hope.

---


### **Where REST-over-MQ Makes Sense**

* **External partner / SaaS integrations (webhooks, telemetry).**
  → These clients often can’t run broker SDKs or open long-lived sockets; REST gives them a frictionless, secure entry point without sacrificing broker durability.

* **Admin tools, dashboards, monitoring, or ad-hoc producers.**
  → Perfect for low-frequency or human-triggered actions — REST’s simplicity outweighs its inefficiency when throughput and latency aren’t critical.

* **Low-volume, high-friction integrations where broker clients aren’t practical.**
  → In regulated, locked-down, or third-party environments, HTTP is the only protocol that passes enterprise firewalls and compliance checks.

* **Controlled latency (<250 ms p99) and at-least-once delivery are acceptable.**
  → When users or systems can tolerate modest delay and rare duplicates, REST provides good-enough reliability without operational pain.

---


### **Where It Should Be Rejected**

* **Internal high-throughput event pipelines.**
  → REST overhead (headers, handshakes, serialization) crushes throughput; native clients sustain millions of messages per second, REST can’t.

* **Systems requiring strict ordering, low latency (<10 ms), or transactional boundaries.**
  → HTTP’s statelessness breaks per-session sequencing and adds unpredictable latency; once ordering or XA guarantees matter, only native MQ protocols hold up.

* **Scenarios with symmetric control (you own both producer and consumer).**
  → If you control both ends, there’s no reason to cripple yourself with REST — use the broker natively and preserve full semantics, throughput, and telemetry.

* **Core domain event buses or command streams.**
  → These are the system’s backbone for consistency and replay; REST facades introduce fragility, slowdowns, and semantic drift that you can’t afford at the core.


---

### Bottom Line

> REST-over-MQ improves *reach*, not *reliability*.
> It simplifies the **edge**, but complicates the **core**.
> Treat it as an **integration layer**, never as a **replacement** for native message clients.

---

## Appendix. Systems that Offer a REST Messaging API

### Solace — REST Messaging API

* **Pros:** first-class REST messages, guaranteed delivery, OAuth2/mTLS, good for edge/webhooks.
* **Cons:** HTTP lacks credits; ordering is limited; higher latency; REST GET is polling; not full transaction semantics.
* **Bottom line:** Great for **edge**; keep **core** consumers on SMF/MQTT/AMQP.

### IBM MQ — REST Messaging & Admin

* **Pros:** IBM-supported messaging over HTTP; persistent puts/gets; simple for non-JMS stacks.
* **Cons:** One message per HTTP call; no streaming callbacks; no batching/flow control; 20–100 ms typical latency.
* **Bottom line:** **Safe but slow**; use for integration/admin/low-volume, not heavy workloads.