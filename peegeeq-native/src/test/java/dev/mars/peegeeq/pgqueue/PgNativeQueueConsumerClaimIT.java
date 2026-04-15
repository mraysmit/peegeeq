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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@Testcontainers
class PgNativeQueueConsumerClaimIT {

    private static final String TOPIC = "it-claim-topic";

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
                manager.closeReactive().await();
            } catch (Exception ignore) {}
        }
    }

    @Test
    void visibleAt_serverTime_is_honored_and_consumer_batchSize_from_consumerConfig(Vertx vertx, VertxTestContext testContext) throws Exception {
        ConsumerConfig consumerConfig = ConsumerConfig.builder()
            .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
            .pollingInterval(Duration.ofSeconds(1))
            .consumerThreads(1)
            .batchSize(1) // ensure single-claim batches
            .build();

        PgNativeQueueConsumer<String> consumer = new PgNativeQueueConsumer<>(
            adapter, mapper, TOPIC, String.class, null, null, consumerConfig
        );

        AtomicInteger processedCount = new AtomicInteger();
        Promise<Void> firstReceived = Promise.promise();

        consumer.subscribe(msg -> {
            int n = processedCount.incrementAndGet();
            if (n == 1) {
                testContext.verify(() -> assertEquals("now-msg", msg.getPayload()));
                firstReceived.tryComplete();
            } else if (n == 2) {
                testContext.verify(() -> assertEquals("future-msg", msg.getPayload()));
                testContext.completeNow();
            }
            return Future.succeededFuture();
        });

        // Insert one message visible now
        String insertNow = """
            INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', now(), now(), 5)
            RETURNING id
        """;
        JsonObject payloadNow = new JsonObject().put("value", "now-msg");
        JsonObject headers = new JsonObject();
        pool.preparedQuery(insertNow)
            .execute(Tuple.of(TOPIC, payloadNow, headers, "c1"))
            .await();

        // Insert one message visible in the future (10s)
        String insertFuture = """
            INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', now(), now() + make_interval(secs => 10), 5)
            RETURNING id
        """;
        JsonObject payloadFuture = new JsonObject().put("value", "future-msg");
        pool.preparedQuery(insertFuture)
            .execute(Tuple.of(TOPIC, payloadFuture, headers, "c2"))
            .await();

        // Trigger consumer via NOTIFY
        pool.query("SELECT pg_notify('" + ("queue_" + TOPIC) + "', 'test')").execute()
            .await();

        // Wait for first message to be processed
        firstReceived.future().await();
        assertEquals(1, processedCount.get(), "Only the visible-now message should be processed initially");

        // Update the future message to become visible now and notify again
        String makeVisibleNow = "UPDATE queue_messages SET visible_at = now() WHERE topic = $1 AND payload = $2::jsonb";
        pool.preparedQuery(makeVisibleNow)
            .execute(Tuple.of(TOPIC, payloadFuture))
            .await();
        pool.query("SELECT pg_notify('" + ("queue_" + TOPIC) + "', 'test2')").execute()
            .await();

        // Now the second should be processed
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertEquals(2, processedCount.get());

        consumer.close();
    }
}



