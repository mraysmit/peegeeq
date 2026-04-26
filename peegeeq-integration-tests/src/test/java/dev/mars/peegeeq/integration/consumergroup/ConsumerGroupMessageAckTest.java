/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.mars.peegeeq.integration.consumergroup;

import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that a consumer group member receives a message
 * and the message is removed from the queue (ack/complete behaviour).
 *
 * <h3>Coverage gap addressed</h3>
 * {@code ConsumerGroupSmokeTest} only exercises CRUD for the group entity.
 * This test proves the actual consumption contract: a message published to the
 * queue is dispatched to a registered consumer group member, the member's
 * handler is invoked, and after successful processing the row is deleted from
 * {@code queue_messages} (queue depth → 0).
 *
 * <h3>Test flow</h3>
 * <ol>
 *   <li>Create a PeeGeeQ setup via the REST API.</li>
 *   <li>Obtain the {@code QueueFactory} directly from
 *       {@code setupService.getSetupResult()} — the REST consumer-group handler
 *       does not call {@code start()}, so we wire the group programmatically to
 *       avoid that limitation.</li>
 *   <li>Create a consumer group, add one member with a handler that records the
 *       received message ID, and call {@code start()}.</li>
 *   <li>Publish a message via the REST API.</li>
 *   <li>Poll {@code queue_messages} every 200 ms until both conditions are true:
 *       the handler has been invoked (captured list non-empty) and the row has
 *       been deleted (COUNT = 0).</li>
 *   <li>Assert and complete within a 20-second guard timer.</li>
 * </ol>
 */
@ExtendWith(VertxExtension.class)
@DisplayName("Consumer Group Message Ack Integration Tests")
@Tag("integration")
public class ConsumerGroupMessageAckTest extends SmokeTestBase {

    /**
     * Verifies that a consumer group member receives a published message and
     * that the message is subsequently removed from the queue.
     *
     * <h3>What is being tested</h3>
     * <ul>
     *   <li>The consumer group's underlying {@code PgNativeQueueConsumer} polls
     *       {@code queue_messages} and dispatches the message to the registered
     *       member handler.</li>
     *   <li>After the handler returns {@code Future.succeededFuture()}, the
     *       consumer deletes the row from {@code queue_messages}, reducing the
     *       available queue depth to zero.</li>
     * </ul>
     */
    @Test
    @DisplayName("Consumer group member receives message and queue depth reaches zero")
    @SuppressWarnings("unchecked")
    void testConsumerGroupMemberReceivesAndAcksMessage(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "cg_ack_test_queue";
        String groupName = "ack-test-group";
        String correlationId = "cg-ack-" + UUID.randomUUID().toString().substring(0, 8);

        // Captures message IDs delivered to the consumer group member handler
        ConcurrentLinkedQueue<String> receivedMessageIds = new ConcurrentLinkedQueue<>();
        // Holds the group so it can be closed during cleanup
        AtomicReference<ConsumerGroup<Object>> groupRef = new AtomicReference<>();

        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);

        webClient.post("/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {} (status={})", setupId, setupResponse.statusCode());
                // Obtain the QueueFactory for the queue directly from the setup service.
                // This bypasses the REST consumer-group handler, which does not call start().
                return setupService.getSetupResult(setupId);
            })
            .compose(result -> {
                QueueFactory factory = result.getQueueFactories().get(queueName);
                assertNotNull(factory, "Queue factory must exist for queue: " + queueName);

                // Create the consumer group, add one member with a capturing handler, and start.
                ConsumerGroup<Object> group = (ConsumerGroup<Object>)
                    factory.createConsumerGroup(groupName, queueName, Object.class);
                groupRef.set(group);

                group.addConsumer("ack-member-1", message -> {
                    receivedMessageIds.add(message.getId());
                    logger.info("Consumer group member received message id={}", message.getId());
                    return io.vertx.core.Future.succeededFuture();
                });

                // start() is fire-and-forget; the underlying PgNativeQueueConsumer sets up
                // LISTEN/NOTIFY and a 5-second polling timer immediately.
                group.start();

                // Publish a message via the REST API with a unique correlationId in headers.
                return webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                    .sendJsonObject(new JsonObject()
                        .put("payload", new JsonObject().put("data", "consumer-group-ack-test"))
                        .put("correlationId", correlationId));
            })
            .onSuccess(publishResponse -> {
                logger.info("Message published (correlationId={}, status={})",
                    correlationId, publishResponse.statusCode());

                long[] failTimerId = {-1};
                failTimerId[0] = vertx.setTimer(20_000, ignored -> {
                    ConsumerGroup<Object> g = groupRef.get();
                    if (g != null) g.close();
                    testContext.failNow(new AssertionError(
                        "Consumer group member did not receive message within 20 seconds. "
                            + "Received count: " + receivedMessageIds.size()
                            + ". Check that the consumer group started correctly and "
                            + "the queue_messages table is being polled."));
                    cleanupSetup(setupId);
                });

                // Poll every 200 ms: wait for both the handler invocation and the DB deletion.
                vertx.setPeriodic(200, timerId -> {
                    if (receivedMessageIds.isEmpty()) {
                        // Handler not yet invoked — keep waiting
                        return;
                    }

                    // Handler was invoked — now verify the row has been deleted from queue_messages.
                    JsonObject dbConfig = setupRequest.getJsonObject("databaseConfig");
                    PgConnectOptions opts = new PgConnectOptions()
                        .setHost(dbConfig.getString("host"))
                        .setPort(dbConfig.getInteger("port"))
                        .setDatabase(dbConfig.getString("databaseName"))
                        .setUser(dbConfig.getString("username"))
                        .setPassword(dbConfig.getString("password"));

                    Pool pool = Pool.pool(vertx, opts, new PoolOptions().setMaxSize(1));

                    pool.preparedQuery(
                            "SELECT COUNT(*) AS n FROM queue_messages WHERE topic = $1")
                        .execute(Tuple.of(queueName))
                        .eventually(() -> pool.close())
                        .onSuccess(rows -> {
                            long remaining = rows.iterator().next().getLong("n");
                            if (remaining > 0) {
                                // Message row still present (locked or pending deletion) — keep polling
                                logger.debug("Message still in queue_messages (count={}), waiting...", remaining);
                                return;
                            }

                            // Both conditions met: handler invoked AND row deleted.
                            vertx.cancelTimer(timerId);
                            vertx.cancelTimer(failTimerId[0]);

                            testContext.verify(() -> {
                                assertFalse(receivedMessageIds.isEmpty(),
                                    "Consumer group member must have received at least one message");
                                assertEquals(0L, remaining,
                                    "queue_messages must be empty after consumer group member acks the message");
                                logger.info(
                                    "Consumer group ack verified: handler invoked (messageId={}), "
                                        + "queue depth=0 (correlationId={})",
                                    receivedMessageIds.peek(), correlationId);
                                ConsumerGroup<Object> g = groupRef.get();
                                if (g != null) g.close();
                                cleanupSetup(setupId);
                            });
                            testContext.completeNow();
                        })
                        .onFailure(err ->
                            logger.debug("DB check failed (will retry): {}", err.getMessage()));
                });
            })
            .onFailure(testContext::failNow);
    }

    private void cleanupSetup(String setupId) {
        webClient.delete("/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> logger.warn("Failed to cleanup setup {}", setupId, err));
    }
}
