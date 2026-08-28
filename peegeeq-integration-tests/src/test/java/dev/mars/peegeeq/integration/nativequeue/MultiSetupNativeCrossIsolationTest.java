package dev.mars.peegeeq.integration.nativequeue;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.pgqueue.ConsumerConfig;
import dev.mars.peegeeq.pgqueue.ConsumerMode;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Tag("integration")
class MultiSetupNativeCrossIsolationTest extends SmokeTestBase {

    private String setupA;
    private String setupB;
    private boolean setupACreated;
    private boolean setupBCreated;
    private boolean setupADestroyed;

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        List<Throwable> cleanupFailures = new ArrayList<>();
        Future<Void> cleanup = Future.succeededFuture();

        if (setupACreated && !setupADestroyed) {
            cleanup = cleanup.compose(ignored -> captureCleanupFailure(
                    setupService.destroySetup(setupA), cleanupFailures));
        }
        if (setupBCreated) {
            cleanup = cleanup.compose(ignored -> captureCleanupFailure(
                    setupService.destroySetup(setupB), cleanupFailures));
        }

        cleanup.compose(ignored -> failIfCleanupFailed(cleanupFailures))
                .onSuccess(ignored -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void destroyingOneSetupDoesNotAffectAnotherLiveConsumer(VertxTestContext testContext) {
        setupA = generateSetupId();
        setupB = generateSetupId();
        String queueName = "shared_queue";
        String markerA = "setup-a-" + System.nanoTime();
        String markerB = "setup-b-" + System.nanoTime();
        List<String> observedByB = new CopyOnWriteArrayList<>();
        Promise<Void> receivedByA = Promise.promise();
        Promise<Void> receivedByB = Promise.promise();

        setupService.createCompleteSetup(createSetupRequest(setupA, queueName))
                .compose(resultA -> {
                    setupACreated = true;
                    return setupService.createCompleteSetup(createSetupRequest(setupB, queueName))
                            .map(resultB -> {
                                setupBCreated = true;
                                return new SetupPair(resultA, resultB);
                            });
                })
                .compose(setups -> {
                    QueueFactory factoryA = setups.setupA().getQueueFactories().get(queueName);
                    QueueFactory factoryB = setups.setupB().getQueueFactories().get(queueName);
                    assertNotNull(factoryA, "Setup A queue factory must exist");
                    assertNotNull(factoryB, "Setup B queue factory must exist");

                    MessageProducer<String> producerA = factoryA.createProducer(queueName, String.class);
                    MessageProducer<String> producerB = factoryB.createProducer(queueName, String.class);
                    MessageConsumer<String> consumerA = factoryA.createConsumer(
                            queueName, String.class, listenOnlyConfig());
                    MessageConsumer<String> consumerB = factoryB.createConsumer(
                            queueName, String.class, listenOnlyConfig());

                    Future<Void> subscriptionA = consumerA.subscribe(message -> {
                        if (markerA.equals(message.getPayload())) {
                            receivedByA.tryComplete();
                        }
                        return Future.succeededFuture();
                    });
                    Future<Void> subscriptionB = consumerB.subscribe(message -> {
                        observedByB.add(message.getPayload());
                        if (markerB.equals(message.getPayload())) {
                            receivedByB.tryComplete();
                        }
                        return Future.succeededFuture();
                    });

                    return Future.all(subscriptionA, subscriptionB)
                            .compose(ignored -> producerA.send(markerA))
                            .compose(ignored -> receivedByA.future())
                            .compose(ignored -> setupService.destroySetup(setupA))
                            .onSuccess(ignored -> setupADestroyed = true)
                            .compose(ignored -> producerB.send(markerB))
                            .compose(ignored -> receivedByB.future());
                })
                .onSuccess(ignored -> testContext.verify(() -> {
                    assertFalse(observedByB.contains(markerA),
                            "Setup A traffic must never surface in setup B");
                    assertTrue(observedByB.contains(markerB),
                            "Setup B consumer must remain live after setup A is destroyed");
                    assertTrue(observedByB.stream().allMatch(markerB::equals),
                            "Setup B consumer must observe only setup B traffic");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private static ConsumerConfig listenOnlyConfig() {
        return ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();
    }

    private DatabaseSetupRequest createSetupRequest(String setupId, String queueName) {
        DatabaseConfig databaseConfig = new DatabaseConfig.Builder()
                .host(getPostgresHost())
                .port(getPostgresPort())
                .databaseName("native_isolation_" + setupId.replace('-', '_'))
                .username(getPostgresUsername())
                .password(getPostgresPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName(queueName)
                .implementationType("native")
                .maxRetries(3)
                .visibilityTimeoutSeconds(30)
                .build();

        return new DatabaseSetupRequest(
                setupId, databaseConfig, List.of(queueConfig), List.of(), Map.of());
    }

    private static Future<Void> captureCleanupFailure(
            Future<Void> cleanup, List<Throwable> cleanupFailures) {
        return cleanup.transform(result -> {
            if (result.failed()) {
                cleanupFailures.add(result.cause());
            }
            return Future.succeededFuture();
        });
    }

    private static Future<Void> failIfCleanupFailed(List<Throwable> cleanupFailures) {
        if (cleanupFailures.isEmpty()) {
            return Future.succeededFuture();
        }

        Throwable firstFailure = cleanupFailures.get(0);
        cleanupFailures.stream().skip(1).forEach(firstFailure::addSuppressed);
        return Future.failedFuture(firstFailure);
    }

    private record SetupPair(DatabaseSetupResult setupA, DatabaseSetupResult setupB) {
    }
}
