package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PgNativeQueueConsumerCleanupIT {

    private static final String TOPIC = "it-cleanup-topic";

    @Container
    static final PostgreSQLContainer<?> postgres =
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
            try { manager.close(); } catch (Exception ignore) {}
        }
    }

    @Test
    void expired_lock_cleanup_in_hybrid_mode_resets_and_processes_message() {
        ConsumerConfig consumerConfig = ConsumerConfig.builder()
            .mode(ConsumerMode.HYBRID)
            .pollingInterval(Duration.ofMillis(200))
            .consumerThreads(1)
            .batchSize(1)
            .build();

        PgNativeQueueConsumer<String> consumer = new PgNativeQueueConsumer<>(
            adapter, mapper, TOPIC, String.class, null, null, consumerConfig
        );

        AtomicBoolean processed = new AtomicBoolean(false);
        CompletableFuture<Void> done = new CompletableFuture<>();

        consumer.subscribe(msg -> {
            assertEquals("locked-msg", msg.getPayload());
            processed.set(true);
            done.complete(null);
            return CompletableFuture.completedFuture(null);
        });

        // Insert a message that is locked in the past (should be reset by cleanup)
        String insertLocked = """
            INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, lock_until, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, 'LOCKED', now(), now(), now() - make_interval(secs => 5), 1)
            RETURNING id
        """;
        JsonObject payload = new JsonObject().put("value", "locked-msg");
        JsonObject headers = new JsonObject();
        pool.preparedQuery(insertLocked)
            .execute(Tuple.of(TOPIC, payload, headers, "c-lock"))
            .toCompletionStage().toCompletableFuture().join();

        // Notify to expedite wakeup (though polling will also run)
        pool.query("SELECT pg_notify('" + ("queue_" + TOPIC) + "', 'test')").execute()
            .toCompletionStage().toCompletableFuture().join();

        // Wait up to 20s for the 10s cleanup periodic to run and processing to occur
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(done::isDone);
        assertTrue(processed.get());

        consumer.close();
    }
}

