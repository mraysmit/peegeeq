package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueStats;
import dev.mars.peegeeq.api.messaging.ServerSideFilter;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests covering schema isolation gaps TC-S1 through TC-S13.
 *
 * <p>Each test exercises a different production code path that was patched during the
 * DLQ audit (Step 7) to use schema-qualified SQL.  The tests verify that:
 * <ul>
 *   <li>Queries target the correct tenant schema and never bleed into neighbouring schemas.</li>
 *   <li>The null-schema path (no peegeeq.database.schema property) uses unqualified SQL
 *       without throwing NullPointerException.</li>
 *   <li>The OutboxFactoryRegistrar falls back to the schema embedded in PgDatabaseService.</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox schema isolation coverage  TC-S1 through TC-S13")
public class OutboxSchemaIsolationCoverageTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxSchemaIsolationCoverageTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    // Per-test resources  cleared by @AfterEach
    private PeeGeeQManager manager;
    private PeeGeeQManager managerB;
    private OutboxFactory factory;
    private OutboxFactory factoryB;
    private ConsumerGroup<?> consumerGroup;
    private ConsumerGroup<?> consumerGroupB;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future.<Void>succeededFuture()
                .eventually(() -> consumerGroup != null
                        ? consumerGroup.stop().compose(v -> consumerGroup.close())
                                .onFailure(e -> logger.warn("consumerGroup stop/close failed", e))
                        : Future.succeededFuture())
                .eventually(() -> consumerGroupB != null
                        ? consumerGroupB.stop().compose(v -> consumerGroupB.close())
                                .onFailure(e -> logger.warn("consumerGroupB stop/close failed", e))
                        : Future.succeededFuture())
                .eventually(() -> {
                    if (factory != null) factory.close();
                    if (factoryB != null) factoryB.close();
                    Future<Void> closeA = manager != null ? manager.closeReactive() : Future.succeededFuture();
                    Future<Void> closeB = managerB != null ? managerB.closeReactive() : Future.succeededFuture();
                    return closeA.compose(ignored -> closeB);
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS),
                "tearDown should complete within 20 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S1: OutboxFactory.getStats() uses schema-qualified SQL
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S1: getStats returns message counts from the correct schema only")
    void tcS1_getStatsUsesCorrectSchema(VertxTestContext testContext) throws Exception {
        String schemaA = "tc_s1_schema_a";
        String schemaB = "tc_s1_schema_b";
        String suffix  = UUID.randomUUID().toString().substring(0, 8);
        String topicA  = "tc_s1_a_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaA, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaB, SchemaComponent.QUEUE_ALL);

        Properties propsA = PeeGeeQTestConfig.builder().from(postgres).schema(schemaA).build();
        Properties propsB = PeeGeeQTestConfig.builder().from(postgres).schema(schemaB).build();
        PeeGeeQConfiguration configA = new PeeGeeQConfiguration("default", propsA);
        PeeGeeQConfiguration configB = new PeeGeeQConfiguration("default", propsB);

        manager  = new PeeGeeQManager(configA, new SimpleMeterRegistry());
        managerB = new PeeGeeQManager(configB, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), configA);
                    return managerB.start();
                })
                .compose(v -> {
                    factoryB = new OutboxFactory(new PgDatabaseService(managerB), configB);
                    MessageProducer<String> producerA = factory.createProducer(topicA, String.class);
                    return producerA.send("msg1")
                            .compose(ignored -> producerA.send("msg2"))
                            .compose(ignored -> producerA.send("msg3"));
                })
                .compose(v -> factory.getStats(topicA))
                .compose(statsA -> {
                    testContext.verify(() -> assertEquals(3L, statsA.getTotalMessages(),
                            schemaA + " should have 3 total messages for " + topicA
                            + "  had " + statsA.getTotalMessages()));
                    return factoryB.getStats(topicA);
                })
                .onSuccess(statsB -> testContext.verify(() -> {
                    assertEquals(0L, statsB.getTotalMessages(),
                            schemaB + " must have 0 messages for " + topicA
                            + " (never written)  had " + statsB.getTotalMessages());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S1 should complete within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S2: OutboxFactory.countMessages() uses schema-qualified SQL
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S2: countMessages returns count from the correct schema only")
    void tcS2_countMessagesUsesCorrectSchema(VertxTestContext testContext) throws Exception {
        String schemaA = "tc_s2_schema_a";
        String schemaB = "tc_s2_schema_b";
        String suffix  = UUID.randomUUID().toString().substring(0, 8);
        String topicA  = "tc_s2_a_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaA, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaB, SchemaComponent.QUEUE_ALL);

        Properties propsA = PeeGeeQTestConfig.builder().from(postgres).schema(schemaA).build();
        Properties propsB = PeeGeeQTestConfig.builder().from(postgres).schema(schemaB).build();
        PeeGeeQConfiguration configA = new PeeGeeQConfiguration("default", propsA);
        PeeGeeQConfiguration configB = new PeeGeeQConfiguration("default", propsB);

        manager  = new PeeGeeQManager(configA, new SimpleMeterRegistry());
        managerB = new PeeGeeQManager(configB, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), configA);
                    return managerB.start();
                })
                .compose(v -> {
                    factoryB = new OutboxFactory(new PgDatabaseService(managerB), configB);
                    MessageProducer<String> producerA = factory.createProducer(topicA, String.class);
                    Future<Void> chain = Future.succeededFuture();
                    for (int i = 0; i < 5; i++) {
                        final String payload = "msg-" + i;
                        chain = chain.compose(ignored -> producerA.send(payload));
                    }
                    return chain;
                })
                .compose(v -> factory.countMessages(topicA))
                .compose(countA -> {
                    testContext.verify(() -> assertEquals(5L, countA,
                            schemaA + " should have 5 messages for " + topicA
                            + "  had " + countA));
                    return factoryB.countMessages(topicA);
                })
                .onSuccess(countB -> testContext.verify(() -> {
                    assertEquals(0L, countB,
                            schemaB + " must have 0 messages for " + topicA
                            + " (never written)  had " + countB);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S2 should complete within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S3: OutboxFactory.purgeMessages() purges only the configured schema
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S3: purgeMessages purges only the configured schema, not the other")
    void tcS3_purgeMessagesTargetsCorrectSchema(VertxTestContext testContext) throws Exception {
        String schemaA = "tc_s3_schema_a";
        String schemaB = "tc_s3_schema_b";
        String suffix  = UUID.randomUUID().toString().substring(0, 8);
        String topicA  = "tc_s3_a_" + suffix;
        String topicB  = "tc_s3_b_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaA, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaB, SchemaComponent.QUEUE_ALL);

        Properties propsA = PeeGeeQTestConfig.builder().from(postgres).schema(schemaA).build();
        Properties propsB = PeeGeeQTestConfig.builder().from(postgres).schema(schemaB).build();
        PeeGeeQConfiguration configA = new PeeGeeQConfiguration("default", propsA);
        PeeGeeQConfiguration configB = new PeeGeeQConfiguration("default", propsB);

        manager  = new PeeGeeQManager(configA, new SimpleMeterRegistry());
        managerB = new PeeGeeQManager(configB, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), configA);
                    return managerB.start();
                })
                .compose(v -> {
                    factoryB = new OutboxFactory(new PgDatabaseService(managerB), configB);
                    MessageProducer<String> producerA = factory.createProducer(topicA, String.class);
                    MessageProducer<String> producerB = factoryB.createProducer(topicB, String.class);
                    return producerA.send("a1")
                            .compose(ignored -> producerA.send("a2"))
                            .compose(ignored -> producerA.send("a3"))
                            .compose(ignored -> producerA.send("a4"))
                            .compose(ignored -> producerB.send("b1"))
                            .compose(ignored -> producerB.send("b2"));
                })
                .compose(v -> factory.purgeMessages(topicA))
                .compose(purgeCount -> {
                    testContext.verify(() -> assertEquals(4, purgeCount,
                            "purgeMessages for " + schemaA + " should delete 4 rows  deleted " + purgeCount));
                    return factory.countMessages(topicA);
                })
                .compose(countA -> {
                    testContext.verify(() -> assertEquals(0L, countA,
                            schemaA + " should be empty after purge  had " + countA));
                    return factoryB.countMessages(topicB);
                })
                .onSuccess(countB -> testContext.verify(() -> {
                    assertEquals(2L, countB,
                            schemaB + " must still have 2 messages (not purged)  had " + countB);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S3 should complete within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S4: OutboxQueueBrowser.browse() uses schema-qualified SQL
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S4: browser reads messages from the correct schema only")
    void tcS4_browserReadsFromCorrectSchema(VertxTestContext testContext) throws Exception {
        String schemaA = "tc_s4_schema_a";
        String schemaB = "tc_s4_schema_b";
        String suffix  = UUID.randomUUID().toString().substring(0, 8);
        String topicA  = "tc_s4_a_" + suffix;
        String topicB  = "tc_s4_b_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaA, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaB, SchemaComponent.QUEUE_ALL);

        Properties propsA = PeeGeeQTestConfig.builder().from(postgres).schema(schemaA).build();
        Properties propsB = PeeGeeQTestConfig.builder().from(postgres).schema(schemaB).build();
        PeeGeeQConfiguration configA = new PeeGeeQConfiguration("default", propsA);
        PeeGeeQConfiguration configB = new PeeGeeQConfiguration("default", propsB);

        manager  = new PeeGeeQManager(configA, new SimpleMeterRegistry());
        managerB = new PeeGeeQManager(configB, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), configA);
                    return managerB.start();
                })
                .compose(v -> {
                    factoryB = new OutboxFactory(new PgDatabaseService(managerB), configB);
                    MessageProducer<String> producerA = factory.createProducer(topicA, String.class);
                    MessageProducer<String> producerB = factoryB.createProducer(topicB, String.class);
                    return producerA.send("schema-a-msg1")
                            .compose(ignored -> producerA.send("schema-a-msg2"))
                            .compose(ignored -> producerA.send("schema-a-msg3"))
                            .compose(ignored -> producerB.send("schema-b-msg1"))
                            .compose(ignored -> producerB.send("schema-b-msg2"));
                })
                .compose(v -> factory.createBrowser(topicA, String.class))
                .compose(browserA -> browserA.browse(10, 0))
                .compose(messagesA -> {
                    testContext.verify(() -> {
                        assertEquals(3, messagesA.size(),
                                "Browser for " + schemaA + " should see 3 messages for topic " + topicA
                                + "  saw " + messagesA.size());
                        messagesA.forEach(msg -> assertTrue(
                                msg.getPayload().startsWith("schema-a-"),
                                "Browser A must only see schema-A messages, got: " + msg.getPayload()));
                    });
                    return factoryB.createBrowser(topicB, String.class);
                })
                .compose(browserB -> browserB.browse(10, 0))
                .onSuccess(messagesB -> testContext.verify(() -> {
                    assertEquals(2, messagesB.size(),
                            "Browser for " + schemaB + " should see 2 messages for topic " + topicB
                            + "  saw " + messagesB.size());
                    messagesB.forEach(msg -> assertTrue(
                            msg.getPayload().startsWith("schema-b-"),
                            "Browser B must only see schema-B messages, got: " + msg.getPayload()));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S4 should complete within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S5: Producer + Consumer roundtrip with explicit schema end-to-end
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S5: producer+consumer roundtrip works with explicit schema end-to-end")
    void tcS5_producerConsumerRoundtripWithExplicitSchema(VertxTestContext testContext) throws Exception {
        String schema = "tc_s5_schema";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic  = "tc_s5_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.QUEUE_ALL);

        Properties props = PeeGeeQTestConfig.builder().from(postgres).schema(schema)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        List<String> receivedPayloads = new CopyOnWriteArrayList<>();
        Checkpoint received = testContext.checkpoint();

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);
                    return factory.createProducer(topic, String.class).send("roundtrip-payload");
                })
                .compose(v -> {
                    factory.createConsumer(topic, String.class)
                            .subscribe(msg -> {
                                receivedPayloads.add(msg.getPayload());
                                received.flag();
                                return Future.succeededFuture();
                            })
                            .onFailure(testContext::failNow);
                    return Future.succeededFuture();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S5 producer+consumer roundtrip should complete within 30 seconds");
        assertEquals(1, receivedPayloads.size(), "Should receive exactly 1 message");
        assertEquals("roundtrip-payload", receivedPayloads.get(0));
    }

    // -----------------------------------------------------------------------
    // TC-S6: Null schema  unqualified SQL (no NPE, backward-compatible path)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S6: config without an explicit schema resolves to the 'myschema' placeholder and fails fast")
    void tcS6_missingSchemaFailsFastOnPlaceholder(VertxTestContext testContext) throws Exception {
        // Inverted contract: the previous test asserted that an omitted schema silently
        // fell back to unqualified SQL on PostgreSQL's default search_path — the
        // accidental-default defect class. PeeGeeQ has no default schema: an unconfigured
        // instance picks up the deliberate 'myschema' placeholder from
        // peegeeq-default.properties and manager startup fails loudly.
        Properties props = new Properties();
        props.setProperty("peegeeq.database.host",     postgres.getHost());
        props.setProperty("peegeeq.database.port",     String.valueOf(postgres.getFirstMappedPort()));
        props.setProperty("peegeeq.database.name",     postgres.getDatabaseName());
        props.setProperty("peegeeq.database.username", postgres.getUsername());
        props.setProperty("peegeeq.database.password", postgres.getPassword());
        props.setProperty("peegeeq.database.ssl.enabled", "false");
        props.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
        props.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
        // peegeeq.database.schema intentionally NOT set — the default profile's
        // placeholder takes effect

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
        assertEquals("myschema", config.getDatabaseConfig().getSchema(),
                "An unconfigured schema must resolve to the deliberate placeholder, never to 'public'");

        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        logger.error("THIS IS AN INTENTIONAL TEST ERROR: the next manager-start failure "
                + "('required tables missing in schema myschema') is the expected fail-fast behavior");
        manager.start()
                .onSuccess(v -> testContext.failNow(new AssertionError(
                        "Manager start must fail for the unconfigured 'myschema' placeholder")))
                .onFailure(err -> testContext.completeNow());

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S6: unconfigured-schema startup should fail fast within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S7: OutboxFactoryRegistrar propagates schema via PgDatabaseService fallback
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S7: OutboxFactoryRegistrar falls back to PgDatabaseService.getPeeGeeQConfiguration()")
    void tcS7_registrarFallbackPropagatesSchema(VertxTestContext testContext) throws Exception {
        String schema = "tc_s7_schema";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic  = "tc_s7_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.QUEUE_ALL);

        Properties props = PeeGeeQTestConfig.builder().from(postgres).schema(schema).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    PgDatabaseService pgDs = new PgDatabaseService(manager);

                    // PgQueueFactoryProvider with no-arg constructor  peeGeeQConfiguration == null.
                    // The registrar's createFactory() must fall back to pgDs.getPeeGeeQConfiguration().
                    PgQueueFactoryProvider noConfigProvider = new PgQueueFactoryProvider();
                    OutboxFactoryRegistrar.registerWith(noConfigProvider);

                    OutboxFactory factoryViaRegistrar;
                    try {
                        factoryViaRegistrar = (OutboxFactory) noConfigProvider
                                .createFactory("outbox", pgDs, new HashMap<>());
                    } catch (Exception e) {
                        return Future.<Void>failedFuture(e);
                    }
                    factory = factoryViaRegistrar;
                    return factory.createProducer(topic, String.class).send("registrar-test-msg");
                })
                .compose(v -> factory.getStats(topic))
                .onSuccess(stats -> testContext.verify(() -> {
                    assertEquals(1L, stats.getTotalMessages(),
                            "Factory created via registrar fallback must query " + schema + ".outbox"
                            + "  found " + stats.getTotalMessages() + " messages");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S7 should complete within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S8: ConsumerGroup processes messages in the correct schema
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S8: consumer group processes messages in the correct schema")
    void tcS8_consumerGroupUsesCorrectSchema(VertxTestContext testContext) throws Exception {
        String schema = "tc_s8_schema";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic  = "tc_s8_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.QUEUE_ALL);

        Properties props = PeeGeeQTestConfig.builder().from(postgres).schema(schema)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        Checkpoint checkpoint = testContext.checkpoint(2);

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);
                    ConsumerGroup<String> cg = factory.createConsumerGroup("group-tc-s8", topic, String.class);
                    consumerGroup = cg;
                    cg.addConsumer("member-1",
                            msg -> {
                                checkpoint.flag();
                                return Future.succeededFuture();
                            });
                    return cg.start();
                })
                .compose(v -> {
                    MessageProducer<String> producer = factory.createProducer(topic, String.class);
                    return producer.send("group-msg-1")
                            .compose(ignored -> producer.send("group-msg-2"));
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S8: consumer group should process 2 messages in schema " + schema + " within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S9: Message handler failure writes terminal status to correct schema
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S9: message failure writes FAILED/DEAD_LETTER status to correct schema only")
    void tcS9_messageFailureUsesCorrectSchema(Vertx vertx, VertxTestContext testContext) throws Exception {
        String schemaA = "tc_s9_schema_a";
        String schemaB = "tc_s9_schema_b";
        String suffix  = UUID.randomUUID().toString().substring(0, 8);
        String topicA  = "tc_s9_a_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaA, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaB, SchemaComponent.QUEUE_ALL);

        Properties propsA = PeeGeeQTestConfig.builder().from(postgres).schema(schemaA)
                .property("peegeeq.queue.max-retries", "1")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        Properties propsB = PeeGeeQTestConfig.builder().from(postgres).schema(schemaB).build();
        PeeGeeQConfiguration configA = new PeeGeeQConfiguration("default", propsA);
        PeeGeeQConfiguration configB = new PeeGeeQConfiguration("default", propsB);

        manager  = new PeeGeeQManager(configA, new SimpleMeterRegistry());
        managerB = new PeeGeeQManager(configB, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> managerB.start())
                .compose(v -> {
                    factory  = new OutboxFactory(new PgDatabaseService(manager),  configA);
                    factoryB = new OutboxFactory(new PgDatabaseService(managerB), configB);

                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                            + "The next ERROR logs ('TC-S9 handler always fails') are EXPECTED");

                    ConsumerGroup<String> cg = factory.createConsumerGroup("group-tc-s9", topicA, String.class);
                    consumerGroup = cg;
                    cg.addConsumer("member-1",
                            msg -> Future.failedFuture(new RuntimeException("TC-S9 handler always fails")));
                    return cg.start();
                })
                .compose(v -> factory.createProducer(topicA, String.class).send("failure-test-msg"))
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryOutboxStatus(manager.getPool(), topicA)
                                .map(s -> "FAILED".equals(s) || "DEAD_LETTER".equals(s)),
                        30_000,
                        schemaA + ".outbox message should reach FAILED or DEAD_LETTER status"))
                .compose(v -> queryOutboxStatus(manager.getPool(), topicA))
                .compose(statusA -> {
                    testContext.verify(() -> assertTrue(
                            "FAILED".equals(statusA) || "DEAD_LETTER".equals(statusA),
                            schemaA + ".outbox message status should be FAILED or DEAD_LETTER  was '" + statusA + "'"));
                    // Cross-schema check: schema B outbox must not contain this topic
                    return queryOutboxStatus(managerB.getPool(), topicA);
                })
                .onSuccess(statusB -> testContext.verify(() -> {
                    assertEquals("", statusB,
                            schemaB + ".outbox must NOT contain any entry for topic " + topicA
                            + " (cross-schema contamination)  status was '" + statusB + "'");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS),
                "TC-S9 should complete within 60 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S10: resetFilteredMessageToPending targets the correct schema
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S10: message filtered by exception resets to PENDING in the correct schema")
    void tcS10_resetFilteredMessageToPendingUsesCorrectSchema(VertxTestContext testContext) throws Exception {
        String schema = "tc_s10_schema";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic  = "tc_s10_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.QUEUE_ALL);

        Properties props = PeeGeeQTestConfig.builder().from(postgres).schema(schema)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        // Filter throws exactly once; on second poll the filter passes and the handler runs.
        AtomicBoolean filterHasThrown = new AtomicBoolean(false);
        Checkpoint messageCompleted  = testContext.checkpoint();

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);

                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                            + "The next ERROR log ('TC-S10 filter throws once') is EXPECTED");

                    ConsumerGroup<String> cg = factory.createConsumerGroup("group-tc-s10", topic, String.class);
                    consumerGroup = cg;
                    cg.addConsumer("member-1",
                            msg -> {
                                messageCompleted.flag();
                                return Future.succeededFuture();
                            },
                            msg -> {
                                if (filterHasThrown.compareAndSet(false, true)) {
                                    throw new RuntimeException("TC-S10 filter throws once");
                                }
                                return true; // Second invocation: filter passes
                            });
                    return cg.start();
                })
                .compose(v -> factory.createProducer(topic, String.class).send("filter-reset-msg"))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S10: message should reset to PENDING after filter exception and then complete within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S11: Server-side filter SQL code path uses schema-qualified table
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S11: server-side filter code path uses schema-qualified SQL")
    void tcS11_serverSideFilterPathUsesCorrectSchema(VertxTestContext testContext) throws Exception {
        String schema = "tc_s11_schema";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic  = "tc_s11_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.QUEUE_ALL);

        Properties props = PeeGeeQTestConfig.builder().from(postgres).schema(schema)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        String matchHeaderKey   = "msg-type";
        String matchHeaderValue = "tc-s11-match";
        Checkpoint matchReceived = testContext.checkpoint(3); // exactly 3 matching messages expected

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);

                    OutboxConsumerConfig consumerConfig = OutboxConsumerConfig.builder()
                            .serverSideFilter(ServerSideFilter.headerEquals(matchHeaderKey, matchHeaderValue))
                            .pollingInterval(Duration.ofMillis(100))
                            .build();

                    factory.createConsumer(topic, String.class, consumerConfig)
                            .subscribe(msg -> {
                                matchReceived.flag();
                                return Future.succeededFuture();
                            })
                            .onFailure(testContext::failNow);

                    MessageProducer<String> producer = factory.createProducer(topic, String.class);
                    Map<String, String> matchHeaders = Map.of(matchHeaderKey, matchHeaderValue);

                    return producer.send("match-1", matchHeaders)
                            .compose(ignored -> producer.send("match-2", matchHeaders))
                            .compose(ignored -> producer.send("match-3", matchHeaders))
                            .compose(ignored -> producer.send("no-match-1"))
                            .compose(ignored -> producer.send("no-match-2"));
                })
                .onFailure(testContext::failNow);

        // checkpoint(3) is satisfied only when all 3 matching messages are received
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S11: consumer with server-side filter should receive exactly 3 matching messages within 30 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S12: storeDeadLetterMessage() + DeadLetterQueueManager round-trip
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S12: DLQ write (schema-qualified) + DeadLetterQueueManager read round-trip")
    void tcS12_deadLetterRoundTripWithExplicitSchema(Vertx vertx, VertxTestContext testContext) throws Exception {
        String schemaA = "tc_s12_schema_a";
        String schemaB = "tc_s12_schema_b";
        String suffix  = UUID.randomUUID().toString().substring(0, 8);
        String topicA  = "tc_s12_a_" + suffix;
        String topicB  = "tc_s12_b_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaA, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaB, SchemaComponent.QUEUE_ALL);

        Properties propsA = PeeGeeQTestConfig.builder().from(postgres).schema(schemaA)
                .property("peegeeq.queue.max-retries", "1")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        Properties propsB = PeeGeeQTestConfig.builder().from(postgres).schema(schemaB)
                .property("peegeeq.queue.max-retries", "1")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration configA = new PeeGeeQConfiguration("default", propsA);
        PeeGeeQConfiguration configB = new PeeGeeQConfiguration("default", propsB);

        manager  = new PeeGeeQManager(configA, new SimpleMeterRegistry());
        managerB = new PeeGeeQManager(configB, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> managerB.start())
                .compose(v -> {
                    factory  = new OutboxFactory(new PgDatabaseService(manager),  configA);
                    factoryB = new OutboxFactory(new PgDatabaseService(managerB), configB);

                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                            + "The next ERROR logs ('TC-S12 filter always throws') are EXPECTED");

                    ConsumerGroup<String> cgA = factory.createConsumerGroup("group-tc-s12-a", topicA, String.class);
                    consumerGroup = cgA;
                    cgA.addConsumer("member-a",
                            msg -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-S12 filter always throws  tenant A"); });

                    ConsumerGroup<String> cgB = factoryB.createConsumerGroup("group-tc-s12-b", topicB, String.class);
                    consumerGroupB = cgB;
                    cgB.addConsumer("member-b",
                            msg -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-S12 filter always throws  tenant B"); });

                    return cgA.start().compose(ignored -> cgB.start());
                })
                .compose(v -> factory.createProducer(topicA, String.class).send("dlq-msg-a"))
                .compose(v -> factoryB.createProducer(topicB, String.class).send("dlq-msg-b"))
                // Wait for schema A message to land in DLQ
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryDLQCount(manager.getPool(), topicA).map(c -> c >= 1),
                        30_000,
                        schemaA + ".dead_letter_queue should have 1 entry for topic " + topicA))
                // Wait for schema B message to land in DLQ
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryDLQCount(managerB.getPool(), topicB).map(c -> c >= 1),
                        30_000,
                        schemaB + ".dead_letter_queue should have 1 entry for topic " + topicB))
                // Verify via DeadLetterQueueManager API  exercises the round-trip
                .compose(v -> manager.getDeadLetterQueueManager().getDeadLetterMessages(topicA, 10, 0))
                .compose(dlmsA -> {
                    testContext.verify(() -> assertEquals(1, dlmsA.size(),
                            "DeadLetterQueueManager for " + schemaA + " should return 1 DLQ entry"
                            + " for topic " + topicA + "  returned " + dlmsA.size()));
                    // Cross-schema check: schema A DLQ manager must not see schema B's topic
                    return manager.getDeadLetterQueueManager().getDeadLetterMessages(topicB, 10, 0);
                })
                .compose(dlmsAForTopicB -> {
                    testContext.verify(() -> assertEquals(0, dlmsAForTopicB.size(),
                            schemaA + " DLQ manager must NOT return entries for schema B topic "
                            + topicB + "  returned " + dlmsAForTopicB.size()));
                    return managerB.getDeadLetterQueueManager().getDeadLetterMessages(topicB, 10, 0);
                })
                .onSuccess(dlmsB -> testContext.verify(() -> {
                    assertEquals(1, dlmsB.size(),
                            "DeadLetterQueueManager for " + schemaB + " should return 1 DLQ entry"
                            + " for topic " + topicB + "  returned " + dlmsB.size());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS),
                "TC-S12 should complete within 60 seconds");
    }

    // -----------------------------------------------------------------------
    // TC-S13: reprocessDeadLetterMessageRecord re-inserts PENDING row in correct schema
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-S13: reprocessDeadLetterMessageRecord re-inserts PENDING row in correct schema")
    void tcS13_reprocessDeadLetterMessageGoesToCorrectSchema(Vertx vertx, VertxTestContext testContext)
            throws Exception {
        String schema = "tc_s13_schema";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic  = "tc_s13_" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.QUEUE_ALL);

        Properties props = PeeGeeQTestConfig.builder().from(postgres).schema(schema)
                .property("peegeeq.queue.max-retries", "1")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), config);

                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== "
                            + "The next ERROR logs ('TC-S13 filter always throws') are EXPECTED");

                    ConsumerGroup<String> cg = factory.createConsumerGroup("group-tc-s13", topic, String.class);
                    consumerGroup = cg;
                    cg.addConsumer("member-1",
                            msg -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-S13 filter always throws"); });
                    return cg.start();
                })
                .compose(v -> factory.createProducer(topic, String.class).send("reprocess-msg"))
                // Wait for the message to land in DLQ
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryDLQCount(manager.getPool(), topic).map(c -> c >= 1),
                        30_000,
                        schema + ".dead_letter_queue should have 1 entry for topic " + topic))
                // Stop the consumer group so the reprocessed message isn't immediately re-consumed
                .compose(v -> consumerGroup.stop())
                // Retrieve the DLQ entry and reprocess it
                .compose(v -> manager.getDeadLetterQueueManager().getDeadLetterMessages(topic, 1, 0))
                .compose(dlms -> {
                    testContext.verify(() -> assertEquals(1, dlms.size(),
                            "Should have exactly 1 DLQ entry for topic " + topic));
                    long dlmId = dlms.get(0).id();
                    return manager.getDeadLetterQueueManager()
                            .reprocessDeadLetterMessage(dlmId, "TC-S13 reprocess");
                })
                .compose(reprocessed -> {
                    testContext.verify(() -> assertTrue(reprocessed,
                            "reprocessDeadLetterMessageRecord should return true"));
                    // DLQ must now be empty
                    return queryDLQCount(manager.getPool(), topic);
                })
                .compose(dlqCountAfter -> {
                    testContext.verify(() -> assertEquals(0, dlqCountAfter,
                            schema + ".dead_letter_queue should be empty after reprocess  had " + dlqCountAfter));
                    // Outbox must have a new PENDING row
                    return queryPendingCount(manager.getPool(), topic);
                })
                .onSuccess(pendingCount -> testContext.verify(() -> {
                    assertEquals(1, pendingCount,
                            schema + ".outbox should have exactly 1 PENDING row after reprocess  had " + pendingCount);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS),
                "TC-S13 should complete within 60 seconds");
    }

    // -----------------------------------------------------------------------
    // Helpers  polling and query utilities
    // -----------------------------------------------------------------------

    private Future<Void> awaitDatabaseCondition(Vertx vertx,
                                                  Supplier<Future<Boolean>> condition,
                                                  long timeoutMillis,
                                                  String failureMessage) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        return pollCondition(vertx, condition, deadline, failureMessage);
    }

    private Future<Void> pollCondition(Vertx vertx,
                                        Supplier<Future<Boolean>> condition,
                                        long deadline,
                                        String failureMessage) {
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

    private Future<Integer> queryPendingCount(Pool pool, String topic) {
        return pool.preparedQuery(
                        "SELECT COUNT(*) AS cnt FROM outbox WHERE topic = $1 AND status = 'PENDING'")
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getInteger("cnt"));
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
