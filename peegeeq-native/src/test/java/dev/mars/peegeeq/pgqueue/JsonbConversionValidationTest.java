package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to validate that JSONB conversion is working correctly for Native Queue.
 * This test verifies that data is stored as proper JSONB objects rather than JSON strings.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class JsonbConversionValidationTest {

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled"
    };

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private PgNativeQueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("jsonb-native-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        databaseService = new PgDatabaseService(manager);
        factory = new PgNativeQueueFactory(databaseService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }

    /**
     * Test that simple string payloads are stored as proper JSONB objects.
     */
    @Test
    void testSimpleStringPayloadStoredAsJsonb() throws Exception {
        String testMessage = "Hello, Native JSONB World!";
        String topic = "jsonb-native-test-simple";

        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        try {
            producer.send(testMessage).await();

            Pool pool = databaseService.getPool();
            RowSet<Row> rows = pool.preparedQuery("""
                    SELECT jsonb_typeof(payload) as payload_type,
                           payload->>'value' as extracted_value
                    FROM queue_messages
                    WHERE topic = $1
                    ORDER BY id DESC
                    LIMIT 1
                    """)
                    .execute(Tuple.of(topic)).await();

            assertTrue(rows.iterator().hasNext(), "Should find the inserted message");
            Row row = rows.iterator().next();

            assertEquals("object", row.getString("payload_type"),
                    "Payload should be stored as JSONB object, not string");
            assertEquals(testMessage, row.getString("extracted_value"),
                    "Should be able to extract value using JSON operators");
        } finally {
            producer.close();
        }
    }

    /**
     * Test that headers are stored as proper JSONB objects.
     */
    @Test
    void testHeadersStoredAsJsonb() throws Exception {
        String testMessage = "Message with headers";
        String topic = "jsonb-native-test-headers";

        Map<String, String> headers = Map.of(
                "source", "jsonb-native-test",
                "version", "1.0",
                "correlationId", "test-correlation-456");

        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        try {
            producer.send(testMessage, headers).await();

            Pool pool = databaseService.getPool();
            RowSet<Row> rows = pool.preparedQuery("""
                    SELECT jsonb_typeof(payload) as payload_type,
                           jsonb_typeof(headers) as headers_type,
                           headers->>'correlationId' as correlation_id,
                           headers->>'source' as source_header
                    FROM queue_messages
                    WHERE topic = $1
                    ORDER BY id DESC
                    LIMIT 1
                    """)
                    .execute(Tuple.of(topic)).await();

            assertTrue(rows.iterator().hasNext(), "Should find the inserted message");
            Row row = rows.iterator().next();

            assertEquals("object", row.getString("payload_type"),
                    "Payload should be stored as JSONB object");
            assertEquals("object", row.getString("headers_type"),
                    "Headers should be stored as JSONB object");
            assertEquals("test-correlation-456", row.getString("correlation_id"),
                    "Should extract correlationId from headers");
            assertEquals("jsonb-native-test", row.getString("source_header"),
                    "Should extract source from headers");
        } finally {
            producer.close();
        }
    }

    /**
     * Test that consumers can properly read and parse JSONB objects.
     */
    @Test
    void testConsumerCanReadJsonbObjects(VertxTestContext testContext) throws Exception {
        String topic = "jsonb-native-test-consumer";
        String testMessage = "Consumer test message";

        Map<String, String> headers = Map.of(
                "source", "consumer-test",
                "priority", "HIGH");

        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topic, String.class);

        try {
            producer.send(testMessage, headers).await();

            consumer.subscribe(message -> {
                testContext.verify(() -> {
                    String receivedMessage = message.getPayload();
                    assertNotNull(receivedMessage, "Payload should not be null");
                    assertEquals(testMessage, receivedMessage, "Message should match");

                    Map<String, String> receivedHeaders = message.getHeaders();
                    assertNotNull(receivedHeaders, "Headers should not be null");
                    assertEquals("consumer-test", receivedHeaders.get("source"), "Source header should match");
                    assertEquals("HIGH", receivedHeaders.get("priority"), "Priority header should match");
                });
                testContext.completeNow();
                return Future.succeededFuture();
            });

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
                    "Message should be processed within 10 seconds");
        } finally {
            consumer.close();
            producer.close();
        }
    }
}


