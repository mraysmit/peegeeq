package dev.mars.peegeeq.integration.nativequeue;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.pgqueue.ConsumerConfig;
import dev.mars.peegeeq.pgqueue.ConsumerMode;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Multi-setup native LISTEN/NOTIFY isolation — regression coverage for the Phase F1 shared-Vert.x
 * defect (see {@code docs-design/tasks/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md}, "Phase F1").
 *
 * <p>Real deployments run <strong>multiple PeeGeeQ setups per service</strong>. That dimension was
 * previously untested and hid a defect: when the per-setup managers shared one Vert.x, a
 * <em>second</em> setup's dedicated LISTEN connection silently received no notifications, so
 * anything driven purely by LISTEN/NOTIFY (an SSE tail, a {@code LISTEN_NOTIFY_ONLY} consumer) never
 * delivered. A PeeGeeQ setup is an independent instance and now owns its own Vert.x — that isolation
 * is by design, not an overhead to optimize away. This test locks that two native setups on one
 * service both deliver over LISTEN/NOTIFY.
 *
 * <p><b>Why {@code LISTEN_NOTIFY_ONLY}:</b> the default HYBRID consumer also polls, so it would
 * deliver the message via the poll fallback even if LISTEN were dead — masking the very defect under
 * test. {@code LISTEN_NOTIFY_ONLY} makes delivery depend purely on NOTIFY, so a deaf LISTEN fails the
 * test rather than passing slowly.
 */
@ExtendWith(VertxExtension.class)
@Tag("integration")
@DisplayName("Multi-setup native LISTEN/NOTIFY isolation")
public class MultiSetupNativeListenIsolationTest extends SmokeTestBase {

    private static final Logger log = LoggerFactory.getLogger(MultiSetupNativeListenIsolationTest.class);

    private final List<MessageConsumer<?>> activeConsumers = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void closeConsumers() {
        synchronized (activeConsumers) {
            for (MessageConsumer<?> c : activeConsumers) {
                try {
                    c.close();
                } catch (Exception e) {
                    log.warn("Failed to close consumer during cleanup", e);
                }
            }
            activeConsumers.clear();
        }
    }

    /**
     * Two native setups on one service, each with a {@link ConsumerMode#LISTEN_NOTIFY_ONLY} consumer.
     * A message produced to each setup's queue must reach THAT setup's consumer purely over
     * LISTEN/NOTIFY. Under the Phase F1 shared Vert.x the second setup's LISTEN went deaf and its
     * consumer never received (timeout); with a per-setup Vert.x both deliver.
     */
    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    @DisplayName("Each of two native setups' LISTEN_NOTIFY_ONLY consumers receives its own message")
    void twoNativeSetups_eachListenOnlyConsumerReceivesItsMessage(VertxTestContext testContext) {
        String setupA = generateSetupId();
        String setupB = generateSetupId();
        String queueName = "multi_setup_listen_queue";

        Checkpoint receivedA = testContext.checkpoint();
        Checkpoint receivedB = testContext.checkpoint();

        // Create both setups (each provisions its own database + per-setup Vert.x), then subscribe a
        // LISTEN_NOTIFY_ONLY consumer on each while both are live — the multi-setup scenario F1 broke.
        setupService.createCompleteSetup(createSetupRequest(setupA, queueName))
                .compose(resultA -> setupService.createCompleteSetup(createSetupRequest(setupB, queueName))
                        .map(resultB -> new DatabaseSetupResult[]{resultA, resultB}))
                .onSuccess(results -> {
                    subscribeThenProduce(results[0], setupA, queueName, "A", receivedA, testContext);
                    subscribeThenProduce(results[1], setupB, queueName, "B", receivedB, testContext);
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Subscribes a {@code LISTEN_NOTIFY_ONLY} consumer on {@code setup}'s queue and flags
     * {@code received} when it sees this call's unique marker, then — only after the subscription's
     * LISTEN is established ({@code subscribe()} completes) — produces exactly that message via the
     * REST API. A received marker therefore proves delivery travelled over NOTIFY.
     */
    private void subscribeThenProduce(DatabaseSetupResult setup, String setupId, String queueName,
            String tag, Checkpoint received, VertxTestContext ctx) {
        QueueFactory factory = setup.getQueueFactories().get(queueName);
        if (factory == null) {
            ctx.failNow(new AssertionError("Queue factory missing for setup " + tag));
            return;
        }
        String marker = "listen-isolation-" + tag + "-" + System.nanoTime();
        MessageConsumer<Object> consumer = factory.createConsumer(queueName, Object.class,
                ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
        activeConsumers.add(consumer);
        consumer.subscribe(msg -> {
                    if (String.valueOf(msg.getPayload()).contains(marker)) {
                        log.info("setup {}: consumer received its message over LISTEN/NOTIFY", tag);
                        received.flag();
                    }
                    return Future.succeededFuture();
                })
                .onSuccess(v -> webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                        .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("marker", marker)))
                        .onSuccess(resp -> {
                            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                                ctx.failNow(new AssertionError("Produce to setup " + tag
                                        + " failed: HTTP " + resp.statusCode()));
                            }
                        })
                        .onFailure(ctx::failNow))
                .onFailure(ctx::failNow);
    }

    private DatabaseSetupRequest createSetupRequest(String setupId, String queueName) {
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(getPostgresHost())
                .port(getPostgresPort())
                .databaseName("multisetup_db_" + setupId.replace("-", "_"))
                .username(getPostgresUsername())
                .password(getPostgresPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();
        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName(queueName)
                .implementationType("native")
                .maxRetries(3)
                .visibilityTimeoutSeconds(30)
                .build();
        return new DatabaseSetupRequest(setupId, dbConfig, List.of(queueConfig), List.of(), Map.of());
    }
}
