package dev.mars.peegeeq.integration.tracing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end observability acceptance test.
 *
 * Validates trace continuity through the real REST -> queue implementation path and
 * verifies MDC isolation across two distinct traced requests.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("Observability System Integration Test")
class ObservabilitySystemIntegrationTest extends SmokeTestBase {

    private TestLogCaptureAppender logCaptureAppender;
    private Logger rootLogger;

    @BeforeEach
    void attachLogCapture() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logCaptureAppender = new TestLogCaptureAppender();
        logCaptureAppender.start();
        rootLogger.addAppender(logCaptureAppender);
    }

    @AfterEach
    void detachLogCapture() {
        if (rootLogger != null && logCaptureAppender != null) {
            rootLogger.detachAppender(logCaptureAppender);
            logCaptureAppender.stop();
        }
    }

    @Test
        @DisplayName("Setup creation should preserve incoming traceparent in setup handler logs")
        void testSetupCreationTracePropagation(VertxTestContext testContext) {
                String setupId = generateSetupId();
                String queueName = "obs_setup_trace_queue_" + UUID.randomUUID().toString().substring(0, 8);

                String traceId = "33333333333333333333333333333333";
                String spanId = "cccccccccccccccc";
                String traceparent = "00-" + traceId + "-" + spanId + "-01";

                JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);

                webClient.post("/api/v1/database-setup/create")
                                .putHeader("content-type", "application/json")
                                .putHeader("traceparent", traceparent)
                                .sendJsonObject(setupRequest)
                                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                                        assertEquals(201, response.statusCode(), "Setup creation should succeed");

                                        Awaitility.await()
                                                        .atMost(Duration.ofSeconds(8))
                                                        .pollInterval(Duration.ofMillis(100))
                                                        .untilAsserted(() -> {
                                                                List<ILoggingEvent> events = logCaptureAppender.snapshot();
                                                                boolean found = events.stream().anyMatch(event ->
                                                                                traceId.equals(event.getMDCPropertyMap().get("traceId"))
                                                                                                && event.getLoggerName().contains("dev.mars.peegeeq.rest.handlers.DatabaseSetupHandler")
                                                                                                && event.getFormattedMessage().contains("Creating database setup: " + setupId));
                                                                assertTrue(found, "Setup handler log should carry the request trace ID");
                                                        });

                                        cleanupSetup(setupId);
                                        testContext.completeNow();
                                })));
        }

        @Test
    @DisplayName("Propagates trace across REST, runtime, and DB with no MDC bleed between requests")
    void testTraceCorrelationAsSystem(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "obs_trace_queue_" + UUID.randomUUID().toString().substring(0, 8);

        String traceIdA = "11111111111111111111111111111111";
        String spanIdA = "aaaaaaaaaaaaaaaa";
        String traceparentA = "00-" + traceIdA + "-" + spanIdA + "-01";

        String traceIdB = "22222222222222222222222222222222";
        String spanIdB = "bbbbbbbbbbbbbbbb";
        String traceparentB = "00-" + traceIdB + "-" + spanIdB + "-01";

        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);

        JsonObject firstMessage = new JsonObject()
                .put("payload", new JsonObject().put("step", "first"))
                .put("correlationId", "corr-a-" + setupId);

        JsonObject secondMessage = new JsonObject()
                .put("payload", new JsonObject().put("step", "second"))
                .put("correlationId", "corr-b-" + setupId);

        webClient.post("/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .putHeader("traceparent", traceparentA)
                .sendJsonObject(setupRequest)
                .compose(setupResponse -> {
                    assertEquals(201, setupResponse.statusCode(), "Setup creation should succeed");
                    return webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                            .putHeader("content-type", "application/json")
                            .putHeader("traceparent", traceparentA)
                            .sendJsonObject(firstMessage);
                })
                .compose(firstSendResponse -> {
                    assertEquals(200, firstSendResponse.statusCode(), "First send should succeed");
                    return webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                            .putHeader("content-type", "application/json")
                            .putHeader("traceparent", traceparentB)
                            .sendJsonObject(secondMessage);
                })
                .onComplete(testContext.succeeding(secondSendResponse -> testContext.verify(() -> {
                    assertEquals(200, secondSendResponse.statusCode(), "Second send should succeed");

                    Awaitility.await()
                            .atMost(Duration.ofSeconds(8))
                            .pollInterval(Duration.ofMillis(100))
                            .untilAsserted(() -> assertTraceCoverage(traceIdA, traceIdB, setupId, queueName));

                    cleanupSetup(setupId);
                    testContext.completeNow();
                })));
    }

    private void assertTraceCoverage(String traceIdA, String traceIdB, String setupId, String queueName) {
        List<ILoggingEvent> allEvents = logCaptureAppender.snapshot();

        List<ILoggingEvent> traceAEvents = allEvents.stream()
                .filter(event -> traceIdA.equals(event.getMDCPropertyMap().get("traceId")))
                .collect(Collectors.toList());

        assertFalse(traceAEvents.isEmpty(), "Expected events with trace A");

        assertTrue(traceAEvents.stream().anyMatch(event ->
                        event.getLoggerName().contains("dev.mars.peegeeq.rest.handlers.QueueHandler") &&
                                event.getFormattedMessage().contains(queueName)),
                "Trace A should appear in queue handler logs");

        assertTrue(traceAEvents.stream().anyMatch(event ->
                        event.getLoggerName().contains("dev.mars.peegeeq.pgqueue.PgNativeQueueProducer") &&
                                event.getFormattedMessage().contains(queueName)),
                "Trace A should propagate into queue implementation logs");

        List<ILoggingEvent> queueSendEvents = allEvents.stream()
                .filter(event -> event.getLoggerName().contains("dev.mars.peegeeq.rest.handlers.QueueHandler"))
                .filter(event -> event.getFormattedMessage().contains("Sending message to queue " + queueName))
                .collect(Collectors.toList());

        assertTrue(queueSendEvents.size() >= 2,
                "Expected queue send logs for both traced requests");

        Set<String> queueTraceIds = queueSendEvents.stream()
                .map(event -> event.getMDCPropertyMap().get("traceId"))
                .collect(Collectors.toSet());

        assertTrue(queueTraceIds.contains(traceIdA), "Queue send logs should contain trace A");
        assertTrue(queueTraceIds.contains(traceIdB), "Queue send logs should contain trace B");
        assertEquals(2, queueTraceIds.size(),
                "Queue send logs should only contain the two request trace IDs (no stale bleed)");
    }

    private void cleanupSetup(String setupId) {
        webClient.delete("/api/v1/setups/" + setupId)
                .send()
                .onFailure(err -> logger.warn("Failed to cleanup setup {}: {}", setupId, err.getMessage()));
    }

    private static final class TestLogCaptureAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject);
        }

        List<ILoggingEvent> snapshot() {
            return new ArrayList<>(events);
        }
    }
}
