package dev.mars.peegeeq.db.subscription;

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

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.PeeGeeQDefaults;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cancelling one consumer group must not mark a message processed that no consumer has
 * processed, and must not change the messages that the other groups still read.
 *
 * <p>The subscription service is the one {@code PeeGeeQManager} gives every caller, so the test
 * runs with the production wiring. Proposal test R-3 (delivery outcome redesign, phase 1,
 * increment 1d).</p>
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Cancel leaves the messages of the topic unchanged")
class CancelLeavesMessagesIntegrationTest extends BaseIntegrationTest {

    private static final String POOL = PeeGeeQDefaults.DEFAULT_POOL_ID;
    private static final int MESSAGE_COUNT = 3;

    private SubscriptionService subscriptions;
    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;

    @BeforeEach
    void setUpCancel() {
        subscriptions = manager.createSubscriptionService();
        connectionManager = manager.getClientFactory().getConnectionManager();
        topicConfigService = new TopicConfigService(connectionManager, POOL);
    }

    @Test
    @DisplayName("R-3: cancel of one of two groups on a QUEUE topic leaves the pending messages PENDING")
    void cancelOfOneOfTwoGroupsOnQueueTopicLeavesPendingMessagesPending(VertxTestContext testContext) {
        String topic = "r3-queue-" + UUID.randomUUID().toString().substring(0, 8);

        // A topic with no outbox_topics row is a QUEUE topic.
        subscriptions.subscribe(topic, "group-a", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults())
                .compose(v -> subscriptions.subscribe(topic, "group-b", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic))
                .compose(ids -> subscriptions.cancel(topic, "group-b")
                        .compose(v -> subscriptions.getSubscription(topic, "group-b"))
                        .compose(cancelled -> messageRows(ids).map(rows -> {
                            testContext.verify(() -> {
                                assertEquals(SubscriptionState.CANCELLED, cancelled.state());
                                for (Row row : rows) {
                                    assertEquals("PENDING", row.getString("status"),
                                            "cancel must not mark message " + row.getLong("id") + " processed");
                                    assertEquals(1, row.getInteger("required_consumer_groups"),
                                            "cancel must not change the required group count of message "
                                                    + row.getLong("id"));
                                }
                            });
                            return (Void) null;
                        })))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("R-3: cancel of one of two groups on a PUB_SUB topic leaves the counts and tracking rows unchanged")
    void cancelOfOneOfTwoGroupsOnPubSubTopicLeavesCountsAndTrackingRowsUnchanged(VertxTestContext testContext) {
        String topic = "r3-pubsub-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .build())
                .compose(v -> subscriptions.subscribe(topic, "group-a", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
                .compose(v -> subscriptions.subscribe(topic, "group-b", SubscriptionStore.OUTBOX, SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic))
                .compose(ids -> insertPendingTrackingRows(ids, "group-b").map(ids))
                .compose(ids -> subscriptions.cancel(topic, "group-b")
                        .compose(v -> subscriptions.getSubscription(topic, "group-b"))
                        .compose(cancelled -> messageRows(ids)
                                .compose(rows -> countTrackingRows(topic, "group-b").map(trackingRows -> {
                                    testContext.verify(() -> {
                                        assertEquals(SubscriptionState.CANCELLED, cancelled.state());
                                        for (Row row : rows) {
                                            assertEquals("PENDING", row.getString("status"),
                                                    "cancel must not mark message " + row.getLong("id") + " processed");
                                            assertEquals(2, row.getInteger("required_consumer_groups"),
                                                    "cancel must not change the required group count of message "
                                                            + row.getLong("id"));
                                        }
                                        assertEquals(MESSAGE_COUNT, trackingRows,
                                                "cancel must not delete the group's tracking rows");
                                    });
                                    return (Void) null;
                                }))))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // --- Helpers ---

    /** Inserts the PENDING messages after the subscriptions, so they belong to both groups. */
    private Future<List<Long>> insertMessages(String topic) {
        List<Long> ids = new ArrayList<>();
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            JsonObject payload = new JsonObject().put("index", i);
            chain = chain.compose(v -> connectionManager.withTransaction(POOL, connection -> connection
                    .preparedQuery("""
                            INSERT INTO outbox (topic, payload, created_at, status)
                            VALUES ($1, $2::jsonb, $3, 'PENDING')
                            RETURNING id
                            """)
                    .execute(Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC)))
                    .map(rows -> {
                        ids.add(rows.iterator().next().getLong("id"));
                        return (Void) null;
                    })));
        }
        return chain.map(v -> ids);
    }

    private Future<Void> insertPendingTrackingRows(List<Long> ids, String groupName) {
        return connectionManager.withTransaction(POOL, connection -> connection
                .preparedQuery("""
                        INSERT INTO outbox_consumer_groups (message_id, group_name, status)
                        SELECT unnest($1::bigint[]), $2, 'PENDING'
                        ON CONFLICT (message_id, group_name) DO NOTHING
                        """)
                .execute(Tuple.of(ids.toArray(new Long[0]), groupName))
                .mapEmpty());
    }

    private Future<List<Row>> messageRows(List<Long> ids) {
        return connectionManager.withConnection(POOL, connection -> connection
                .preparedQuery("SELECT id, status, required_consumer_groups FROM outbox WHERE id = ANY($1) ORDER BY id")
                .execute(Tuple.of(ids.toArray(new Long[0])))
                .map(rows -> {
                    List<Row> result = new ArrayList<>();
                    rows.forEach(result::add);
                    assertEquals(ids.size(), result.size(), "every inserted message is still in outbox");
                    return result;
                }));
    }

    private Future<Long> countTrackingRows(String topic, String groupName) {
        return connectionManager.withConnection(POOL, connection -> connection
                .preparedQuery("""
                        SELECT COUNT(*) AS cnt FROM outbox_consumer_groups ocg
                        JOIN outbox o ON o.id = ocg.message_id
                        WHERE o.topic = $1 AND ocg.group_name = $2
                        """)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> rows.iterator().next().getLong("cnt")));
    }
}
