package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests verifying that DLQ writes in {@code OutboxConsumer} are isolated
 * to the correct tenant schema and do not bleed into other tenants' schemas.
 *
 * <p>Multi-tenant isolation rule: one isolated schema per tenant.
 * {@code peegeeq.database.schema} is the single source of truth.
 * All SQL in {@code OutboxConsumer} must use schema-qualified table names.</p>
 *
 * <ul>
 *   <li>TC-7a: DLQ writes for tenant A go to {@code schema_a.dead_letter_queue};
 *       DLQ writes for tenant B go to {@code schema_b.dead_letter_queue};
 *       neither tenant's DLQ contains the other's messages.</li>
 *   <li>TC-7b: {@code outbox} status update ({@code DEAD_LETTER}) goes to the correct
 *       tenant schema and does not touch the other tenant's outbox.</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("DLQ multi-tenant schema isolation")
public class DlqMultiTenantSchemaIsolationTest {

    private static final Logger logger = LoggerFactory.getLogger(DlqMultiTenantSchemaIsolationTest.class);

    private static final String SCHEMA_A = "tenant_a_schema";
    private static final String SCHEMA_B = "tenant_b_schema";

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    // Tenant A
    private PeeGeeQManager managerA;
    private OutboxFactory factoryA;
    private MessageProducer<String> producerA;
    private ConsumerGroup<String> consumerGroupA;
    private String topicA;

    // Tenant B
    private PeeGeeQManager managerB;
    private OutboxFactory factoryB;
    private MessageProducer<String> producerB;
    private ConsumerGroup<String> consumerGroupB;
    private String topicB;

    @BeforeEach
    void setUp() {
        // Initialize both tenant schemas  each gets its own outbox + dead_letter_queue tables
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_A, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_B, SchemaComponent.QUEUE_ALL);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        topicA = "dlq-tenant-a-" + suffix;
        topicB = "dlq-tenant-b-" + suffix;
    }

    private Future<Void> startManagers() {
        Properties propsA = PeeGeeQTestConfig.builder().from(postgres)
                .schema(SCHEMA_A)
                .property("peegeeq.queue.max-retries", "1")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        Properties propsB = PeeGeeQTestConfig.builder().from(postgres)
                .schema(SCHEMA_B)
                .property("peegeeq.queue.max-retries", "1")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration configA = new PeeGeeQConfiguration("default", propsA);
        PeeGeeQConfiguration configB = new PeeGeeQConfiguration("default", propsB);

        managerA = new PeeGeeQManager(configA, new SimpleMeterRegistry());
        managerB = new PeeGeeQManager(configB, new SimpleMeterRegistry());

        return managerA.start()
                .map(v -> {
                    DatabaseService dsA = new PgDatabaseService(managerA);
                    factoryA = new OutboxFactory(dsA, configA);
                    producerA = factoryA.createProducer(topicA, String.class);
                    consumerGroupA = factoryA.createConsumerGroup("group-a", topicA, String.class);
                    return null;
                })
                .compose(v -> managerB.start())
                .map(v -> {
                    DatabaseService dsB = new PgDatabaseService(managerB);
                    factoryB = new OutboxFactory(dsB, configB);
                    producerB = factoryB.createProducer(topicB, String.class);
                    consumerGroupB = factoryB.createConsumerGroup("group-b", topicB, String.class);
                    return null;
                });
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future.<Void>succeededFuture()
                .eventually(() -> consumerGroupA != null
                        ? consumerGroupA.stop().compose(v -> consumerGroupA.close())
                                .onFailure(e -> logger.warn("consumerGroupA stop/close failed", e))
                        : Future.succeededFuture())
                .eventually(() -> consumerGroupB != null
                        ? consumerGroupB.stop().compose(v -> consumerGroupB.close())
                                .onFailure(e -> logger.warn("consumerGroupB stop/close failed", e))
                        : Future.succeededFuture())
                .eventually(() -> {
                    if (producerA != null) producerA.close();
                    if (producerB != null) producerB.close();
                    if (factoryA != null) factoryA.close();
                    if (factoryB != null) factoryB.close();
                    Future<Void> closeA = managerA != null ? managerA.closeReactive() : Future.succeededFuture();
                    Future<Void> closeB = managerB != null ? managerB.closeReactive() : Future.succeededFuture();
                    return closeA.compose(ignored -> closeB);
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS));
    }

    // =========================================================================
    // TC-7a: DLQ writes go to the correct tenant schema  no cross-tenant bleed
    // =========================================================================

    @Test
    @DisplayName("TC-7a: DLQ writes isolated to correct tenant schema  no cross-tenant bleed")
    void tc7a_dlqWritesGoToCorrectTenantSchema(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== TC-7a: DLQ multi-tenant schema isolation ===");

        startManagers()
                .compose(v -> {
                    // Both consumers have always-throwing filters  will exhaust max-retries=1  DLQ
                    consumerGroupA.addConsumer("member-a",
                            msg -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-7a EXPECTED FILTER EXCEPTION tenant_a"); });
                    consumerGroupB.addConsumer("member-b",
                            msg -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-7a EXPECTED FILTER EXCEPTION tenant_b"); });
                    return consumerGroupA.start();
                })
                .compose(v -> consumerGroupB.start())
                .compose(v -> {
                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== " +
                                "The next ERROR logs ('TC-7a EXPECTED FILTER EXCEPTION') are EXPECTED");
                    return producerA.send("msg-tenant-a");
                })
                .compose(v -> producerB.send("msg-tenant-b"))
                // Wait for tenant A's message to reach DLQ
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryDLQCount(managerA.getPool(), topicA).map(c -> c >= 1),
                        20_000,
                        "tenant_a DLQ should have 1 entry for topic " + topicA))
                // Wait for tenant B's message to reach DLQ
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryDLQCount(managerB.getPool(), topicB).map(c -> c >= 1),
                        20_000,
                        "tenant_b DLQ should have 1 entry for topic " + topicB))
                // Verify counts in each tenant's own DLQ
                .compose(v -> queryDLQCount(managerA.getPool(), topicA))
                .compose(countA -> {
                    testContext.verify(() -> assertEquals(1, countA,
                            SCHEMA_A + ".dead_letter_queue should have exactly 1 entry for topic " + topicA +
                            "  had " + countA));
                    return queryDLQCount(managerB.getPool(), topicB);
                })
                .compose(countB -> {
                    testContext.verify(() -> assertEquals(1, countB,
                            SCHEMA_B + ".dead_letter_queue should have exactly 1 entry for topic " + topicB +
                            "  had " + countB));
                    // Cross-tenant isolation: tenant A's DLQ must NOT contain tenant B's topic
                    return queryDLQCount(managerA.getPool(), topicB);
                })
                .compose(crossCountA -> {
                    testContext.verify(() -> assertEquals(0, crossCountA,
                            SCHEMA_A + ".dead_letter_queue must NOT contain tenant B's topic " + topicB +
                            " (cross-tenant bleed)  had " + crossCountA));
                    // Cross-tenant isolation: tenant B's DLQ must NOT contain tenant A's topic
                    return queryDLQCount(managerB.getPool(), topicA);
                })
                .onSuccess(crossCountB -> testContext.verify(() -> {
                    assertEquals(0, crossCountB,
                            SCHEMA_B + ".dead_letter_queue must NOT contain tenant A's topic " + topicA +
                            " (cross-tenant bleed)  had " + crossCountB);
                    logger.info("TC-7a PASSED: each tenant's DLQ has 1 entry, no cross-tenant bleed");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(40, TimeUnit.SECONDS));
    }

    // =========================================================================
    // TC-7b: outbox DEAD_LETTER status update goes to correct tenant schema
    // =========================================================================

    @Test
    @DisplayName("TC-7b: outbox DEAD_LETTER status update isolated to correct tenant schema")
    void tc7b_outboxStatusUpdateIsolatedToCorrectSchema(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== TC-7b: outbox status update multi-tenant isolation ===");

        startManagers()
                .compose(v -> {
                    consumerGroupA.addConsumer("member-a",
                            msg -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-7b EXPECTED FILTER EXCEPTION tenant_a"); });
                    consumerGroupB.addConsumer("member-b",
                            msg -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-7b EXPECTED FILTER EXCEPTION tenant_b"); });
                    return consumerGroupA.start();
                })
                .compose(v -> consumerGroupB.start())
                .compose(v -> {
                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== " +
                                "The next ERROR logs ('TC-7b EXPECTED FILTER EXCEPTION') are EXPECTED");
                    return producerA.send("msg-tenant-a");
                })
                .compose(v -> producerB.send("msg-tenant-b"))
                // Wait for tenant A outbox to reach DEAD_LETTER
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryOutboxStatus(managerA.getPool(), topicA).map("DEAD_LETTER"::equals),
                        20_000,
                        SCHEMA_A + ".outbox should reach status=DEAD_LETTER for topic " + topicA))
                // Wait for tenant B outbox to reach DEAD_LETTER
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryOutboxStatus(managerB.getPool(), topicB).map("DEAD_LETTER"::equals),
                        20_000,
                        SCHEMA_B + ".outbox should reach status=DEAD_LETTER for topic " + topicB))
                // Verify tenant A outbox status
                .compose(v -> queryOutboxStatus(managerA.getPool(), topicA))
                .compose(statusA -> {
                    testContext.verify(() -> assertEquals("DEAD_LETTER", statusA,
                            SCHEMA_A + ".outbox status should be DEAD_LETTER for topic " + topicA +
                            ", was " + statusA));
                    return queryOutboxStatus(managerB.getPool(), topicB);
                })
                .compose(statusB -> {
                    testContext.verify(() -> assertEquals("DEAD_LETTER", statusB,
                            SCHEMA_B + ".outbox status should be DEAD_LETTER for topic " + topicB +
                            ", was " + statusB));
                    // Cross-schema check: tenant A's outbox must have NO row for tenant B's topic
                    return queryOutboxStatus(managerA.getPool(), topicB);
                })
                .compose(crossStatusA -> {
                    testContext.verify(() -> assertEquals("",  crossStatusA,
                            SCHEMA_A + ".outbox must NOT contain an entry for tenant B's topic " + topicB +
                            " (cross-tenant bleed)  status was '" + crossStatusA + "'"));
                    // Cross-schema check: tenant B's outbox must have NO row for tenant A's topic
                    return queryOutboxStatus(managerB.getPool(), topicA);
                })
                .onSuccess(crossStatusB -> testContext.verify(() -> {
                    assertEquals("", crossStatusB,
                            SCHEMA_B + ".outbox must NOT contain an entry for tenant A's topic " + topicA +
                            " (cross-tenant bleed)  status was '" + crossStatusB + "'");
                    logger.info("TC-7b PASSED: each tenant's outbox has DEAD_LETTER status, no cross-schema bleed");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(40, TimeUnit.SECONDS));
    }

    // =========================================================================
    // Async DB helpers  all return Future<T>, no blocking
    // =========================================================================

    private Future<Integer> queryDLQCount(Pool pool, String topic) {
        return pool.preparedQuery("SELECT COUNT(*) AS cnt FROM dead_letter_queue WHERE topic = $1")
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getInteger("cnt"));
    }

    private Future<String> queryOutboxStatus(Pool pool, String topic) {
        return pool.preparedQuery("SELECT status FROM outbox WHERE topic = $1 LIMIT 1")
                .execute(Tuple.of(topic))
                .map(rows -> {
                    var it = rows.iterator();
                    return it.hasNext() ? it.next().getString("status") : "";
                });
    }

    // =========================================================================
    // Async polling helper  Supplier<Future<Boolean>>, no event-loop blocking
    // =========================================================================

    private Future<Void> awaitDatabaseCondition(Vertx vertx, Supplier<Future<Boolean>> condition,
                                                  long timeoutMillis, String failureMessage) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        return pollCondition(vertx, condition, deadline, failureMessage);
    }

    private Future<Void> pollCondition(Vertx vertx, Supplier<Future<Boolean>> condition,
                                        long deadline, String failureMessage) {
        return condition.get()
                .transform(ar -> {
                    if (ar.succeeded() && Boolean.TRUE.equals(ar.result())) {
                        return Future.succeededFuture();
                    }
                    if (System.currentTimeMillis() > deadline) {
                        String reason = ar.failed()
                                ? "  condition threw: " + ar.cause().getMessage()
                                : " (timed out)";
                        return Future.failedFuture(new AssertionError(failureMessage + reason));
                    }
                    return vertx.timer(100)
                            .compose(id -> pollCondition(vertx, condition, deadline, failureMessage));
                });
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
