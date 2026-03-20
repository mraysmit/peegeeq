package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
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
import java.util.Set;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@Testcontainers
class PgNativeQueueConcurrentClaimIT {

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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_VERSION, NATIVE_QUEUE, DEAD_LETTER_QUEUE);
    }

    @BeforeEach
    void setUp() {
        // Configure system properties for TestContainers
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

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
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            try {
                CountDownLatch closeLatch = new CountDownLatch(1);
                manager.closeReactive().onComplete(ar -> closeLatch.countDown());
                closeLatch.await(10, TimeUnit.SECONDS);
            } catch (Exception ignore) {}
        }
    }

    @Test
    void two_consumers_do_not_double_claim_messages_in_polling_mode(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Insert two messages with unique payloads
        String insertSql = """
            INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', now(), now(), 1)
        """;
        CountDownLatch insertLatch = new CountDownLatch(2);
        pool.preparedQuery(insertSql)
            .execute(Tuple.of(TOPIC, new JsonObject().put("value", "m1"), new JsonObject(), "c-1"))
            .onComplete(ar -> insertLatch.countDown());
        pool.preparedQuery(insertSql)
            .execute(Tuple.of(TOPIC, new JsonObject().put("value", "m2"), new JsonObject(), "c-2"))
            .onComplete(ar -> insertLatch.countDown());
        assertTrue(insertLatch.await(5, TimeUnit.SECONDS), "Inserts should complete");

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



