package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
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
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.time.Duration;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fault-injection IT: forcibly closes the dedicated LISTEN connection and verifies
 * the consumer reconnects (with backoff) and continues to receive messages.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class ListenReconnectFaultInjectionIT {

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private static final Logger logger = LoggerFactory.getLogger(ListenReconnectFaultInjectionIT.class);

    private PeeGeeQManager manager;
    private PgNativeQueueFactory factory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    private static final String TOPIC = "reconnect-fault-test";

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        // Configure DB for this test run
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .build();

        // Initialize schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.DEAD_LETTER_QUEUE);

        // Start manager using a dedicated profile
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("listen-reconnect-test", testProps);
        manager = new PeeGeeQManager(cfg, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Use DatabaseService pattern for factory creation
            DatabaseService databaseService = new PgDatabaseService(manager);
            factory = new PgNativeQueueFactory(databaseService);
            producer = factory.createProducer(TOPIC, String.class);
            consumer = factory.createConsumer(TOPIC, String.class, new ConsumerConfig.Builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(1000))
                .consumerThreads(1)
                .build());
            testContext.completeNow();
        }).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        if (consumer != null) consumer.unsubscribe();
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.warn("Error during teardown: {}", err.getMessage());
                testContext.completeNow();
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testListenReconnectAfterForcedDisconnect(Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer.subscribe(msg -> {
        logger.info("Test: listen reconnect after forced disconnect");
            testContext.completeNow();
            return Future.succeededFuture();
        });

        // Wait for LISTEN connection to establish
        vertx.setPeriodic(100, waitId -> {
            try {
                if (getSubscriber((PgNativeQueueConsumer<?>) consumer) != null) {
                    vertx.cancelTimer(waitId);

                    // Fault injection: close the dedicated LISTEN PgConnection
                    forceCloseListenConnection(consumer);

                    // Wait for reconnect backoff to re-establish the connection
                    vertx.setPeriodic(100, reconnectId -> {
                        try {
                            if (getSubscriber((PgNativeQueueConsumer<?>) consumer) != null) {
                                vertx.cancelTimer(reconnectId);
                                // Send a message; should be received after reconnect
                                producer.send("after-reconnect");
                            }
                        } catch (Exception e) {
                            testContext.failNow(e);
                        }
                    });
                }
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Consumer should receive message after LISTEN reconnect");
    }

    @Test
    void testUnsubscribeDoesNotReestablishListenConnection(Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer.subscribe(msg -> Future.succeededFuture());

        PgNativeQueueConsumer<?> concrete = (PgNativeQueueConsumer<?>) consumer;

        // Wait for LISTEN connection to establish
        vertx.setPeriodic(100, waitId -> {
        logger.info("Test: unsubscribe does not reestablish listen connection");
            try {
                if (getSubscriber(concrete) != null) {
                    vertx.cancelTimer(waitId);
                    assertNotNull(getSubscriber(concrete), "Subscribed consumer should establish a LISTEN connection");

                    consumer.unsubscribe();

                    // Wait for LISTEN connection to be torn down
                    vertx.setPeriodic(100, checkId -> {
                        try {
                            if (getSubscriber(concrete) == null) {
                                vertx.cancelTimer(checkId);
                                testContext.verify(() -> {
                                    assertNull(getSubscriber(concrete), "Unsubscribed consumer must not establish a new LISTEN connection");
                                    assertEquals(-1L, getListenReconnectTimerId(concrete), "Unsubscribed consumer must not schedule LISTEN reconnect");
                                    assertFalse(isSubscribed(concrete), "Consumer should remain unsubscribed after teardown of LISTEN connection");
                                });
                                testContext.completeNow();
                            }
                        } catch (Exception e) {
                            testContext.failNow(e);
                        }
                    });
                }
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    private static void forceCloseListenConnection(MessageConsumer<String> c) throws Exception {
        PgNativeQueueConsumer<?> concrete = (PgNativeQueueConsumer<?>) c; // same package, safe cast
        concrete.closeSubscriberConnectionForTest();
    }

    private static Object getSubscriber(PgNativeQueueConsumer<?> consumer) throws Exception {
        Field field = PgNativeQueueConsumer.class.getDeclaredField("subscriber");
        field.setAccessible(true);
        return field.get(consumer);
    }

    private static long getListenReconnectTimerId(PgNativeQueueConsumer<?> consumer) throws Exception {
        Field field = PgNativeQueueConsumer.class.getDeclaredField("listenReconnectTimerId");
        field.setAccessible(true);
        return field.getLong(consumer);
    }

    @SuppressWarnings("unchecked")
    private static boolean isSubscribed(PgNativeQueueConsumer<?> consumer) throws Exception {
        Field field = PgNativeQueueConsumer.class.getDeclaredField("subscribed");
        field.setAccessible(true);
        return ((AtomicBoolean) field.get(consumer)).get();
    }

}

