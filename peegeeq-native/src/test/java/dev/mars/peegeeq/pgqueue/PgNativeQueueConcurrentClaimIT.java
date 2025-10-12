package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PgNativeQueueConcurrentClaimIT {

    private static final String TOPIC = "it-concurrent-claim-topic";

    @Container
    static final PostgreSQLContainer<?> postgres =
        PeeGeeQTestContainerFactory.createContainer(BASIC);

    private Vertx vertx;
    private VertxPoolAdapter adapter;
    private Pool pool;
    private ObjectMapper mapper;

    @BeforeAll
    static void beforeAll() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_VERSION, NATIVE_QUEUE, DEAD_LETTER_QUEUE);
    }

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        adapter = new VertxPoolAdapter(vertx);

        PgConnectionConfig connCfg = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .sslEnabled(false)
            .build();

        PgPoolConfig poolCfg = new PgPoolConfig.Builder()
            .maxSize(6)
            .maxWaitQueueSize(32)
            .shared(true)
            .build();

        pool = adapter.createPool(connCfg, poolCfg);
        assertNotNull(pool);

        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @AfterEach
    void tearDown() {
        if (adapter != null && adapter.getPool() != null) {
            try { adapter.getPool().close(); } catch (Exception ignore) {}
        }
        if (vertx != null) {
            try { vertx.close().toCompletionStage().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join(); } catch (Exception ignore) {}
        }
    }

    @Test
    void two_consumers_do_not_double_claim_messages_in_polling_mode() {
        // Insert two messages with unique payloads
        String insertSql = """
            INSERT INTO queue_messages (topic, payload, headers, correlation_id, status, created_at, visible_at, priority)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', now(), now(), 1)
        """;
        pool.preparedQuery(insertSql)
            .execute(Tuple.of(TOPIC, new JsonObject().put("value", "m1"), new JsonObject(), "c-1"))
            .toCompletionStage().toCompletableFuture().join();
        pool.preparedQuery(insertSql)
            .execute(Tuple.of(TOPIC, new JsonObject().put("value", "m2"), new JsonObject(), "c-2"))
            .toCompletionStage().toCompletableFuture().join();

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
        CompletableFuture<Void> done = new CompletableFuture<>();

        c1.subscribe(msg -> {
            payloads.add(msg.getPayload());
            if (processed.incrementAndGet() >= 2) {
                done.complete(null);
            }
            return CompletableFuture.completedFuture(null);
        });
        c2.subscribe(msg -> {
            payloads.add(msg.getPayload());
            if (processed.incrementAndGet() >= 2) {
                done.complete(null);
            }
            return CompletableFuture.completedFuture(null);
        });

        // Wait up to 10s for both messages to be processed exactly once
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(done::isDone);
        assertEquals(2, processed.get());
        assertEquals(Set.of("m1", "m2"), payloads);

        c1.close();
        c2.close();
    }
}

