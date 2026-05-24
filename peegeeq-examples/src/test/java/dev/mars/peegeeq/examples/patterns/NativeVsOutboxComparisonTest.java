package dev.mars.peegeeq.examples.patterns;

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
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive comparison tests between native and outbox queue implementations.
 * This test class validates that both implementations work correctly and highlights
 * their different characteristics and use cases.
 *
 * Tests cover:
 * - Performance differences
 * - Reliability characteristics
 * - Feature availability
 * - Use case suitability
 *
 * <h3>Refactored Test Design</h3>
 * This test class has been refactored to eliminate poorly structured test design patterns:
 * <ul>
 *   <li><strong>Property Management</strong>: Uses standardized TestContainers configuration</li>
 *   <li><strong>Thread Management</strong>: Uses chained {@link io.vertx.core.Future} composition with {@link io.vertx.core.Promise} continuations instead of manual Thread.sleep()</li>
 *   <li><strong>Test Independence</strong>: Each test uses unique queue and consumer group names</li>
 *   <li><strong>Clean Structure</strong>: Streamlined setup/teardown with essential functionality only</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-03
 * @version 2.0 (Refactored)
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class NativeVsOutboxComparisonTest {
    private static final Logger logger = LoggerFactory.getLogger(NativeVsOutboxComparisonTest.class);
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }
    
    private PeeGeeQManager manager;
    private QueueFactory nativeFactory;
    private QueueFactory outboxFactory;

    /**
     * Generate unique queue name for test independence
     */
    private String getUniqueQueueName(String baseName) {
        return baseName + "-" + System.nanoTime();
    }

    /**
     * Generate unique consumer group name for test independence
     */
    private String getUniqueGroupName(String baseName) {
        return baseName + "-" + System.nanoTime();
    }
    
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                nativeFactory = provider.createFactory("native", databaseService);
                outboxFactory = provider.createFactory("outbox", databaseService);
                logger.info("Native vs Outbox comparison test setup completed successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (nativeFactory != null ? nativeFactory.close() : Future.<Void>succeededFuture())
            .compose(v -> outboxFactory != null ? outboxFactory.close() : Future.succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> {
                logger.info("Native vs Outbox comparison test teardown completed");
                testContext.completeNow();
            })
            .onFailure(err -> {
                logger.warn("Error during teardown: {}", err.getMessage());
                testContext.completeNow();
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
    
    @Test
    void testImplementationTypeIdentification() {
        // Verify that both factories are properly identified
        assertEquals("native", nativeFactory.getImplementationType());
        assertEquals("outbox", outboxFactory.getImplementationType());
        
        // Verify they are different classes
        assertNotEquals(nativeFactory.getClass(), outboxFactory.getClass());
        
        logger.info("Implementation type identification test passed");
    }
    
    @Test
    void testBasicFunctionalityComparison(VertxTestContext testContext) {
        // Test that both implementations provide basic messaging functionality
        String testMessage = "Comparison test message";

        // Test native implementation with unique queue name
        String nativeQueueName = getUniqueQueueName("comparison-native");
        MessageProducer<String> nativeProducer = nativeFactory.createProducer(nativeQueueName, String.class);
        MessageConsumer<String> nativeConsumer = nativeFactory.createConsumer(nativeQueueName, String.class);

        io.vertx.core.Promise<Void> nativeDone = io.vertx.core.Promise.promise();
        List<String> nativeMessages = new ArrayList<>();

        nativeConsumer.subscribe(message -> {
            nativeMessages.add(message.getPayload());
            nativeDone.tryComplete();
            return Future.succeededFuture();
        }).onSuccess(ready -> nativeProducer.send(testMessage).onFailure(testContext::failNow))
          .onFailure(testContext::failNow);

        nativeDone.future().onSuccess(v -> testContext.verify(() -> {
            assertEquals(1, nativeMessages.size());
            assertEquals(testMessage, nativeMessages.get(0));

            // Test outbox implementation with unique queue name
            String outboxQueueName = getUniqueQueueName("comparison-outbox");
            MessageProducer<String> outboxProducer = outboxFactory.createProducer(outboxQueueName, String.class);
            MessageConsumer<String> outboxConsumer = outboxFactory.createConsumer(outboxQueueName, String.class);

            io.vertx.core.Promise<Void> outboxDone = io.vertx.core.Promise.promise();
            List<String> outboxMessages = new ArrayList<>();

            outboxConsumer.subscribe(message -> {
                outboxMessages.add(message.getPayload());
                outboxDone.tryComplete();
                return Future.succeededFuture();
            }).onSuccess(ready -> outboxProducer.send(testMessage).onFailure(testContext::failNow))
              .onFailure(testContext::failNow);

            outboxDone.future().onSuccess(v2 -> testContext.verify(() -> {
                assertEquals(1, outboxMessages.size());
                assertEquals(testMessage, outboxMessages.get(0));

                // Cleanup
                nativeProducer.close();
                nativeConsumer.close();
                outboxProducer.close();
                outboxConsumer.close();

                logger.info("Basic functionality comparison test passed");
                testContext.completeNow();
            })).onFailure(testContext::failNow);
        })).onFailure(testContext::failNow);
    }
    
    @Test
    void testPerformanceCharacteristics(Vertx vertx, VertxTestContext testContext) {
        // Compare performance characteristics between native and outbox
        int messageCount = 10; // Reduced for more reliable testing
        String testMessage = "Performance test message";

        // Test native performance with unique queue name
        long nativeStartTime = System.currentTimeMillis();
        String nativePerfQueueName = getUniqueQueueName("perf-native");
        MessageProducer<String> nativeProducer = nativeFactory.createProducer(nativePerfQueueName, String.class);
        MessageConsumer<String> nativeConsumer = nativeFactory.createConsumer(nativePerfQueueName, String.class);

        AtomicInteger nativeRemaining = new AtomicInteger(messageCount);
        io.vertx.core.Promise<Void> nativeDone = io.vertx.core.Promise.promise();
        AtomicLong nativeFirstReceiveTime = new AtomicLong();
        AtomicLong nativeLastReceiveTime = new AtomicLong();

        nativeConsumer.subscribe(message -> {
            if (nativeFirstReceiveTime.get() == 0) {
                nativeFirstReceiveTime.set(System.currentTimeMillis());
            }
            nativeLastReceiveTime.set(System.currentTimeMillis());
            if (nativeRemaining.decrementAndGet() == 0) nativeDone.tryComplete();
            return Future.succeededFuture();
        }).onFailure(testContext::failNow);

        // Send messages to native with small delays (chained Future sequence)
        Future<Void> nativeChain = Future.succeededFuture();
        for (int i = 0; i < messageCount; i++) {
            final int idx = i;
            nativeChain = nativeChain
                .compose(v -> nativeProducer.send(testMessage + " " + idx).mapEmpty())
                .compose(v -> vertx.timer(100).mapEmpty());
        }
        nativeChain.onFailure(testContext::failNow);

        nativeDone.future().onSuccess(v -> {
            long nativeEndTime = System.currentTimeMillis();
            long nativeTotalTime = nativeEndTime - nativeStartTime;
            long nativeFirstMessageLatency = nativeFirstReceiveTime.get() - nativeStartTime;

            // Test outbox performance with unique queue name
            long outboxStartTime = System.currentTimeMillis();
            String outboxPerfQueueName = getUniqueQueueName("perf-outbox");
            MessageProducer<String> outboxProducer = outboxFactory.createProducer(outboxPerfQueueName, String.class);
            MessageConsumer<String> outboxConsumer = outboxFactory.createConsumer(outboxPerfQueueName, String.class);

            AtomicInteger outboxRemaining = new AtomicInteger(messageCount);
            io.vertx.core.Promise<Void> outboxDone = io.vertx.core.Promise.promise();
            AtomicLong outboxFirstReceiveTime = new AtomicLong();
            AtomicLong outboxLastReceiveTime = new AtomicLong();

            outboxConsumer.subscribe(message -> {
                if (outboxFirstReceiveTime.get() == 0) {
                    outboxFirstReceiveTime.set(System.currentTimeMillis());
                }
                outboxLastReceiveTime.set(System.currentTimeMillis());
                if (outboxRemaining.decrementAndGet() == 0) outboxDone.tryComplete();
                return Future.succeededFuture();
            }).onFailure(testContext::failNow);

            // Send messages to outbox with small delays (chained Future sequence)
            Future<Void> outboxChain = Future.succeededFuture();
            for (int i = 0; i < messageCount; i++) {
                final int idx = i;
                outboxChain = outboxChain
                    .compose(vv -> outboxProducer.send(testMessage + " " + idx).mapEmpty())
                    .compose(vv -> vertx.timer(100).mapEmpty());
            }
            outboxChain.onFailure(testContext::failNow);

            outboxDone.future().onSuccess(v2 -> testContext.verify(() -> {
                long outboxEndTime = System.currentTimeMillis();
                long outboxTotalTime = outboxEndTime - outboxStartTime;
                long outboxFirstMessageLatency = outboxFirstReceiveTime.get() - outboxStartTime;

                logger.info("Performance Comparison Results:");
                logger.info("Native - Total time: {}ms, First message latency: {}ms", nativeTotalTime, nativeFirstMessageLatency);
                logger.info("Outbox - Total time: {}ms, First message latency: {}ms", outboxTotalTime, outboxFirstMessageLatency);

                assertTrue(nativeTotalTime < 30000, "Native should complete within 30 seconds");
                assertTrue(outboxTotalTime < 30000, "Outbox should complete within 30 seconds");

                nativeProducer.close();
                nativeConsumer.close();
                outboxProducer.close();
                outboxConsumer.close();

                logger.info("Performance characteristics test passed");
                testContext.completeNow();
            })).onFailure(testContext::failNow);
        }).onFailure(testContext::failNow);
    }
    
    @Test
    void testConsumerGroupComparison(VertxTestContext testContext) {
        // Compare consumer group functionality between implementations with unique names
        String topic = getUniqueQueueName("consumer-group-comparison");

        // Test native consumer group with unique group name
        String nativeGroupName = getUniqueGroupName("native-group");
        ConsumerGroup<String> nativeGroup = nativeFactory.createConsumerGroup(nativeGroupName, topic, String.class);
        MessageProducer<String> nativeProducer = nativeFactory.createProducer(topic, String.class);

        AtomicInteger nativeRemaining = new AtomicInteger(6);
        io.vertx.core.Promise<Void> nativeDone = io.vertx.core.Promise.promise();
        AtomicInteger nativeProcessed = new AtomicInteger();

        nativeGroup.addConsumer("native-member-1", message -> {
            nativeProcessed.incrementAndGet();
            if (nativeRemaining.decrementAndGet() == 0) nativeDone.tryComplete();
            return Future.succeededFuture();
        });

        nativeGroup.addConsumer("native-member-2", message -> {
            nativeProcessed.incrementAndGet();
            if (nativeRemaining.decrementAndGet() == 0) nativeDone.tryComplete();
            return Future.succeededFuture();
        });

        nativeGroup.start();

        // Send messages to native via chained Future sequence
        Future<Void> nativeSendChain = Future.succeededFuture();
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            nativeSendChain = nativeSendChain.compose(v ->
                nativeProducer.send("Native group message " + idx).mapEmpty());
        }
        nativeSendChain.onFailure(testContext::failNow);

        nativeDone.future().onSuccess(v -> testContext.verify(() -> {
            assertEquals(6, nativeProcessed.get());

            // Test outbox consumer group with unique group name
            String outboxGroupName = getUniqueGroupName("outbox-group");
            ConsumerGroup<String> outboxGroup = outboxFactory.createConsumerGroup(outboxGroupName, topic, String.class);
            MessageProducer<String> outboxProducer = outboxFactory.createProducer(topic, String.class);

            AtomicInteger outboxRemaining = new AtomicInteger(6);
            io.vertx.core.Promise<Void> outboxDone = io.vertx.core.Promise.promise();
            AtomicInteger outboxProcessed = new AtomicInteger();

            outboxGroup.addConsumer("outbox-member-1", message -> {
                outboxProcessed.incrementAndGet();
                if (outboxRemaining.decrementAndGet() == 0) outboxDone.tryComplete();
                return Future.succeededFuture();
            });

            outboxGroup.addConsumer("outbox-member-2", message -> {
                outboxProcessed.incrementAndGet();
                if (outboxRemaining.decrementAndGet() == 0) outboxDone.tryComplete();
                return Future.succeededFuture();
            });

            outboxGroup.start();

            // Send messages to outbox via chained Future sequence
            Future<Void> outboxSendChain = Future.succeededFuture();
            for (int i = 0; i < 6; i++) {
                final int idx = i;
                outboxSendChain = outboxSendChain.compose(vv ->
                    outboxProducer.send("Outbox group message " + idx).mapEmpty());
            }
            outboxSendChain.onFailure(testContext::failNow);

            outboxDone.future().onSuccess(v2 -> testContext.verify(() -> {
                assertEquals(6, outboxProcessed.get());

                // Verify stats for both
                ConsumerGroupStats nativeStats = nativeGroup.getStats();
                ConsumerGroupStats outboxStats = outboxGroup.getStats();

                assertNotNull(nativeStats);
                assertNotNull(outboxStats);
                assertEquals(2, nativeStats.getActiveConsumerCount());
                assertEquals(2, outboxStats.getActiveConsumerCount());

                nativeGroup.stopGracefully().onFailure(testContext::failNow);
                outboxGroup.stopGracefully().onFailure(testContext::failNow);
                nativeProducer.close();
                outboxProducer.close();

                logger.info("Consumer group comparison test passed");
                testContext.completeNow();
            })).onFailure(testContext::failNow);
        })).onFailure(testContext::failNow);
    }
    
    @Test
    void testHealthAndResourceManagement(VertxTestContext testContext) {
        // Test that both implementations properly manage health and resources
        io.vertx.core.Future.all(nativeFactory.isHealthy(), outboxFactory.isHealthy())
                .compose(cf -> {
                    assertTrue((Boolean) cf.resultAt(0));
                    assertTrue((Boolean) cf.resultAt(1));

                    // Create resources with unique queue names
                    String healthTestQueueName = getUniqueQueueName("health-test");
                    MessageProducer<String> nativeProducer = nativeFactory.createProducer(healthTestQueueName, String.class);
                    MessageConsumer<String> nativeConsumer = nativeFactory.createConsumer(healthTestQueueName, String.class);
                    MessageProducer<String> outboxProducer = outboxFactory.createProducer(healthTestQueueName, String.class);
                    MessageConsumer<String> outboxConsumer = outboxFactory.createConsumer(healthTestQueueName, String.class);

                    // Both should still be healthy
                    return io.vertx.core.Future.all(nativeFactory.isHealthy(), outboxFactory.isHealthy())
                            .compose(cf2 -> {
                                assertTrue((Boolean) cf2.resultAt(0));
                                assertTrue((Boolean) cf2.resultAt(1));

                                // Close resources
                                nativeProducer.close();
                                nativeConsumer.close();
                                outboxProducer.close();
                                outboxConsumer.close();

                                // Should still be healthy after cleanup
                                return io.vertx.core.Future.all(nativeFactory.isHealthy(), outboxFactory.isHealthy());
                            });
                })
                .onSuccess(cf -> testContext.verify(() -> {
                    assertTrue((Boolean) cf.resultAt(0));
                    assertTrue((Boolean) cf.resultAt(1));
                    logger.info("Health and resource management test passed");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}


