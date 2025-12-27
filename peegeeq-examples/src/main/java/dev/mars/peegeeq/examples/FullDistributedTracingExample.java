package dev.mars.peegeeq.examples;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Demonstrates FULL distributed tracing flow across HTTP → Queue → Consumer → External Service.
 * <p>
 * This example shows:
 * 1. HTTP API receives request with traceparent header
 * 2. API sends message to queue with trace context
 * 3. Consumer processes message (trace context propagated)
 * 4. Consumer calls external service with trace context
 * 5. All logs across all services show the same traceId
 * <p>
 * Run this example and then make requests:
 * <pre>
 * curl -X POST http://localhost:8080/orders \
 *   -H "Content-Type: application/json" \
 *   -H "traceparent: 00-$(uuidgen | tr -d '-' | cut -c1-32)-$(uuidgen | tr -d '-' | cut -c1-16)-01" \
 *   -d '{"orderId":"12345","amount":100.00}'
 * </pre>
 */
public class FullDistributedTracingExample {

    private static final Logger logger = LoggerFactory.getLogger(FullDistributedTracingExample.class);
    private static final String TOPIC = "orders";

    public static void main(String[] args) throws Exception {
        // Initialize PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default");
        PeeGeeQManager manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create outbox factory
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);

        QueueFactory factory = provider.createFactory("outbox", databaseService);
        MessageProducer<JsonObject> producer = factory.createProducer(TOPIC, JsonObject.class);
        MessageConsumer<JsonObject> consumer = factory.createConsumer(TOPIC, JsonObject.class);

        Vertx vertx = manager.getVertx();
        WebClient webClient = WebClient.create(vertx);

        // Start external service simulator (port 9090)
        startExternalService(vertx);

        // Start REST API (port 8080)
        startRestApi(vertx, producer);

        // Start consumer that calls external service
        consumer.subscribe(message -> {
            // MDC is automatically populated by PeeGeeQ!
            String traceId = MDC.get("traceId");
            String spanId = MDC.get("spanId");
            String correlationId = MDC.get("correlationId");

            logger.info("📨 Consumer received order: {}", message.getPayload());
            logger.info("🔍 Trace context: traceId={}, spanId={}, correlationId={}", traceId, spanId, correlationId);

            // Simulate business logic
            logger.info("💼 Processing order...");

            // Call external service with trace context propagation
            String newSpanId = generateSpanId();
            String traceparent = String.format("00-%s-%s-01", traceId, newSpanId);

            logger.info("🌐 Calling external service with traceparent: {}", traceparent);

            CompletableFuture<Void> future = new CompletableFuture<>();

            webClient.post(9090, "localhost", "/external/process")
                    .putHeader("traceparent", traceparent)
                    .putHeader("correlationId", correlationId)
                    .sendJsonObject(message.getPayload())
                    .onSuccess(response -> {
                        logger.info("✅ External service responded: {}", response.bodyAsString());
                        logger.info("🎯 Order processing complete!");
                        future.complete(null);
                    })
                    .onFailure(err -> {
                        logger.error("❌ External service call failed", err);
                        future.completeExceptionally(err);
                    });

            return future;
        });

        logger.info("================================================================================");
        logger.info("🚀 Full Distributed Tracing Example Started");
        logger.info("================================================================================");
        logger.info("📍 REST API listening on: http://localhost:8080");
        logger.info("📍 External service on: http://localhost:9090");
        logger.info("");
        logger.info("📝 Send a request with trace context:");
        logger.info("   curl -X POST http://localhost:8080/orders \\");
        logger.info("     -H \"Content-Type: application/json\" \\");
        logger.info("     -H \"traceparent: 00-$(uuidgen | tr -d '-' | cut -c1-32)-$(uuidgen | tr -d '-' | cut -c1-16)-01\" \\");
        logger.info("     -d '{\"orderId\":\"12345\",\"amount\":100.00}'");
        logger.info("");
        logger.info("🔍 Watch the logs - all services will show the same traceId!");
        logger.info("================================================================================");

        // Keep running
        Thread.currentThread().join();
    }

    private static void startRestApi(Vertx vertx, MessageProducer<JsonObject> producer) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        router.post("/orders").handler(ctx -> {
            // Extract trace context from incoming HTTP request
            String traceparent = ctx.request().getHeader("traceparent");
            String correlationId = ctx.request().getHeader("correlationId");

            if (traceparent != null) {
                // Parse traceparent and set MDC for API logs
                TraceContextUtil.setMDCFromTraceparent(traceparent);
                if (correlationId != null) {
                    TraceContextUtil.setMDC(TraceContextUtil.MDC_CORRELATION_ID, correlationId);
                }
            }

            String traceId = MDC.get("traceId");
            String spanId = MDC.get("spanId");

            logger.info("📥 REST API received order request");
            logger.info("🔍 Trace context: traceId={}, spanId={}, correlationId={}", traceId, spanId, correlationId);

            JsonObject order = ctx.body().asJsonObject();
            logger.info("📦 Order details: {}", order);

            // Generate new span ID for queue message
            String newSpanId = generateSpanId();
            String newTraceparent = String.format("00-%s-%s-01", traceId, newSpanId);

            // Send to queue with trace context
            Map<String, String> headers = new HashMap<>();
            headers.put("traceparent", newTraceparent);
            if (correlationId != null) {
                headers.put("correlationId", correlationId);
            }

            logger.info("📤 Sending to queue with traceparent: {}", newTraceparent);

            producer.send(order, headers, correlationId)
                    .thenAccept(v -> {
                        logger.info("✅ Message sent to queue successfully");

                        // Return response with trace context
                        ctx.response()
                                .putHeader("Content-Type", "application/json")
                                .putHeader("traceparent", traceparent)
                                .setStatusCode(202)
                                .end(new JsonObject()
                                        .put("status", "accepted")
                                        .put("traceId", traceId)
                                        .put("correlationId", correlationId)
                                        .encode());

                        // Clear MDC after request
                        MDC.clear();
                    })
                    .exceptionally(err -> {
                        logger.error("❌ Failed to send message", err);
                        ctx.response().setStatusCode(500).end();
                        MDC.clear();
                        return null;
                    });
        });

        HttpServer server = vertx.createHttpServer();
        server.requestHandler(router).listen(8080);
    }

    private static void startExternalService(Vertx vertx) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        router.post("/external/process").handler(ctx -> {
            // Extract trace context from incoming request
            String traceparent = ctx.request().getHeader("traceparent");
            String correlationId = ctx.request().getHeader("correlationId");

            if (traceparent != null) {
                TraceContextUtil.setMDCFromTraceparent(traceparent);
                if (correlationId != null) {
                    TraceContextUtil.setMDC(TraceContextUtil.MDC_CORRELATION_ID, correlationId);
                }
            }

            String traceId = MDC.get("traceId");
            String spanId = MDC.get("spanId");

            logger.info("🌐 External service received request");
            logger.info("🔍 Trace context: traceId={}, spanId={}, correlationId={}", traceId, spanId, correlationId);

            JsonObject payload = ctx.body().asJsonObject();
            logger.info("📦 Processing: {}", payload);

            // Simulate processing
            logger.info("⚙️ External service processing...");

            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .putHeader("traceparent", traceparent)
                    .end(new JsonObject()
                            .put("status", "processed")
                            .put("traceId", traceId)
                            .encode());

            MDC.clear();
        });

        HttpServer server = vertx.createHttpServer();
        server.requestHandler(router).listen(9090);
        logger.info("🌐 External service started on port 9090");
    }

    private static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

