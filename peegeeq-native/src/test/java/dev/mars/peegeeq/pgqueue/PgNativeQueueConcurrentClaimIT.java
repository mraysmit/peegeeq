package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(VertxExtension.class)
@Testcontainers
class PgNativeQueueConcurrentClaimIT {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueConcurrentClaimIT.class);


    private static final String TOPIC = "it-concurrent-claim-topic";

    @Container
    static final PostgreSQLContainer postgres =
        PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private VertxPoolAdapter adapter;
    private Pool pool;
    private ObjectMapper mapper;

    @BeforeAll
    static void beforeAll() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SCHEMA_VERSION, NATIVE_QUEUE, DEAD_LETTER_QUEUE);
    }

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure system properties for TestContainers
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    // Create adapter using DatabaseService interfaces
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    adapter = new VertxPoolAdapter(
                        databaseService.getVertx(),
                        databaseService.getPool(),
                        databaseService
                    );
                    pool = adapter.getPool();
                    mapper = new ObjectMapper();
                    mapper.registerModule(new JavaTimeModule());
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(err -> {
                    logger.error("Teardown close failed", err);
                    ctx.failNow(err);
                });
        assertTrue(ctx.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void two_consumers_do_not_double_claim_messages_in_polling_mode(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: two consumers do not double claim messages in polling mode");
        // Insert two messages with unique payloads
        String insertSql = """
            INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', now(), now(), 1)
        """;
        pool.preparedQuery(insertSql)
            .execute(Tuple.of(TOPIC, new JsonObject().put("value", "m1"), new JsonObject(), "c-1"))
            .compose(v -> pool.preparedQuery(insertSql)
                .execute(Tuple.of(TOPIC, new JsonObject().put("value", "m2"), new JsonObject(), "c-2")))
            .onFailure(testContext::failNow);

        ConsumerConfig cfg = ConsumerConfig.builder()
            .mode(ConsumerMode.POLLING_ONLY)
            .pollingInterval(Duration.ofMillis(100))
            .consumerThreads(1)
            .batchSize(1)
            .build();

        PgNativeQueueConsumer<String> c1 = new PgNativeQueueConsumer<>(adapter, mapper, TOPIC, String.class, null, null, cfg);
        PgNativeQueueConsumer<String> c2 = new PgNativeQueueConsumer<>(adapter, mapper, TOPIC, String.class, null, null, cfg);

        AtomicInteger processed = new AtomicInteger();
        Set<String> payloads = Collections.synchronizedSet(new HashSet<>());
        Checkpoint bothDone = testContext.checkpoint(2);

        c1.subscribe(msg -> {
            payloads.add(msg.getPayload());
            processed.incrementAndGet();
            bothDone.flag();
            return Future.succeededFuture();
        });
        c2.subscribe(msg -> {
            payloads.add(msg.getPayload());
            processed.incrementAndGet();
            bothDone.flag();
            return Future.succeededFuture();
        });

        // Wait up to 10s for both messages to be processed exactly once
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertEquals(2, processed.get());
        assertEquals(Set.of("m1", "m2"), payloads);

        c1.close();
        c2.close();
    }
}



