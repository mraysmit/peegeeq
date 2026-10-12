package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Consumer Group + Subscription Options + SSE workflow.
 * 
 * Tests the complete REST API workflow from creating consumer groups through
 * setting subscription options to connecting via SSE with those options applied.
 * Also tests validation and error cases.
 * 
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end consumer group + SSE workflow
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsumerGroupSubscriptionIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupSubscriptionIntegrationTest.class);
    private static final int TEST_PORT = 18081;
    
    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_consumer_group_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }
    
    private PeeGeeQRestServer server;
    private DatabaseSetupService setupService;
    private String deploymentId;
    private String setupId;
    private HttpClient httpClient;
    private WebClient webClient;
    private static final String QUEUE_NAME = "test_consumer_group_queue";
    
    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Consumer Group + Subscription Integration Test ===");

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Start REST server
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);

                // Create HTTP client and WebClient
                httpClient = vertx.createHttpClient();
                webClient = WebClient.create(vertx);

                // Create database setup with queue - use databaseConfig format
                setupId = "consumer_group_test_" + System.currentTimeMillis();

                // Build databaseConfig from TestContainer connection info
                // Use a NEW database name so the REST API creates it fresh
                String newDbName = "cg_test_" + System.currentTimeMillis();
                JsonObject databaseConfig = new JsonObject()
                    .put("host", postgres.getHost())
                    .put("port", postgres.getMappedPort(5432))
                    .put("databaseName", newDbName)
                    .put("username", postgres.getUsername())
                    .put("password", postgres.getPassword())
                    .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                    .put("templateDatabase", "template0")
                    .put("encoding", "UTF8");

                JsonObject queueConfig = new JsonObject()
                    .put("queueName", QUEUE_NAME)
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", 30);

                JsonObject setupRequest = new JsonObject()
                    .put("setupId", setupId)
                    .put("databaseConfig", databaseConfig)
                    .put("queues", new JsonArray().add(queueConfig));

                logger.info("Creating database setup via REST API: {}", setupId);

                webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                    .sendJsonObject(setupRequest)
                    .compose(response -> {
                        if (response.statusCode() == 200 || response.statusCode() == 201) {
                            logger.info("Database setup created: {}", setupId);
                            String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                                postgres.getHost(), postgres.getMappedPort(5432), newDbName);
                            // Schema init is blocking (Flyway/JDBC)  must run on a worker thread.
                            return vertx.executeBlocking(() -> {
                                logger.info("Applying Consumer Group Fanout schema to new database: {}", newDbName);
                                PeeGeeQTestSchemaInitializer.initializeSchema(jdbcUrl,
                                    postgres.getUsername(), postgres.getPassword(),
                                    PostgreSQLTestConstants.TEST_SCHEMA,
                                    SchemaComponent.OUTBOX, SchemaComponent.CONSUMER_GROUP_FANOUT);
                                logger.info("Consumer Group Fanout schema applied successfully");
                                return null;
                            });
                        } else {
                            logger.error("Failed to create setup: {} - {}",
                                      response.statusCode(), response.bodyAsString());
                            return Future.failedFuture(new Exception("Failed to create setup: " + response.statusCode()));
                        }
                    })
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
    
    @AfterAll
    void teardown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Consumer Group + Subscription Integration Test ===");
        
        if (webClient != null) {
            webClient.delete(TEST_PORT, "localhost", "/api/v1/setups/" + setupId)
                .send()
                .onComplete(ar -> {
                    if (deploymentId != null) {
                        vertx.undeploy(deploymentId)
                            .onSuccess(v -> testContext.completeNow())
                            .onFailure(testContext::failNow);
                    } else {
                        testContext.completeNow();
                    }
                });
        } else {
            testContext.completeNow();
        }
        
        try {
            testContext.awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Test
    void testSetSubscriptionOptionsWithoutConsumerGroup(VertxTestContext testContext) {
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING")
            .put("heartbeatIntervalSeconds", 60);
        
        String path = String.format("/api/v1/consumer-groups/%s/%s/nonexistent-group/subscription",
                                    setupId, QUEUE_NAME);
        
        webClient.post(TEST_PORT, "localhost", path)
            .sendJsonObject(subscriptionOptions)
            .onSuccess(response -> testContext.verify(() -> {
                // Should return 404 - consumer group not found
                assertEquals(404, response.statusCode(), 
                           "Should return 404 when consumer group doesn't exist");
                
                JsonObject errorResponse = response.bodyAsJsonObject();
                assertNotNull(errorResponse.getString("error"));
                assertTrue(errorResponse.getString("error").contains("not found"));
                
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    void testGetSubscriptionOptionsForNonExistentGroup(VertxTestContext testContext) {
        getOptions("nonexistent-group")
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(),
                    "A group with no subscription must return the defaults. Response: " + response.bodyAsString());

                JsonObject responseObj = response.bodyAsJsonObject();
                assertEquals("NOT_CONFIGURED", responseObj.getString("status"));

                JsonObject options = responseObj.getJsonObject("subscriptionOptions");
                assertNotNull(options, "The response must carry the default options");
                assertEquals("FROM_NOW", options.getString("startPosition"));
                assertEquals(60, options.getInteger("heartbeatIntervalSeconds"));
                assertEquals(300, options.getInteger("heartbeatTimeoutSeconds"));

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDeleteSubscriptionOptions(VertxTestContext testContext) {
        String groupName = "test-delete-group";

        createGroup(groupName)
            .compose(v -> postOptions(groupName, new JsonObject().put("startPosition", "FROM_BEGINNING")))
            .compose(optionsResponse -> {
                assertEquals(200, optionsResponse.statusCode(),
                    "Setting the options must succeed. Response: " + optionsResponse.bodyAsString());
                return webClient.delete(TEST_PORT, "localhost", subscriptionPath(groupName)).send();
            })
            .compose(deleteResponse -> {
                assertEquals(204, deleteResponse.statusCode(), "Should return 204 No Content on successful delete");
                return getOptions(groupName);
            })
            .onSuccess(getResponse -> testContext.verify(() -> {
                assertEquals(200, getResponse.statusCode());
                assertEquals("CANCELLED", getResponse.bodyAsJsonObject().getString("status"),
                    "A deleted subscription is stored as CANCELLED");

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testFromMessageIdWithExplicitValue(VertxTestContext testContext) {
        String groupName = "test-message-id-group";

        createGroup(groupName)
            .compose(v -> postOptions(groupName, fromMessageId(42)))
            .compose(optionsResponse -> {
                assertEquals(200, optionsResponse.statusCode(),
                    "The first request creates the subscription. Response: " + optionsResponse.bodyAsString());

                JsonObject options = optionsResponse.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"));
                assertEquals(42, options.getInteger("startFromMessageId"));

                return getOptions(groupName);
            })
            .onSuccess(getResponse -> testContext.verify(() -> {
                JsonObject options = getResponse.bodyAsJsonObject()
                    .getJsonObject("subscriptionOptions");

                assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"),
                           "Retrieved startPosition should be FROM_MESSAGE_ID");
                assertEquals(42, options.getInteger("startFromMessageId"),
                           "Retrieved message ID should be 42");

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testUpdateWithDifferentStartPositionReturns409(VertxTestContext testContext) {
        String groupName = "test-update-start-position";

        createGroup(groupName)
            .compose(v -> postOptions(groupName, fromMessageId(42)))
            .compose(created -> {
                assertEquals(200, created.statusCode(), "Response: " + created.bodyAsString());
                return postOptions(groupName, fromMessageId(43));
            })
            .compose(otherMessageId -> {
                assertEquals(409, otherMessageId.statusCode(),
                    "Another message id must return 409. Response: " + otherMessageId.bodyAsString());
                assertNotNull(otherMessageId.bodyAsJsonObject().getString("error"));
                return postOptions(groupName, new JsonObject().put("startPosition", "FROM_BEGINNING"));
            })
            .compose(fromBeginning -> {
                assertEquals(409, fromBeginning.statusCode(),
                    "FROM_BEGINNING must return 409 when the stored bound is not 1. Response: "
                        + fromBeginning.bodyAsString());
                return postOptions(groupName, new JsonObject().put("startPosition", "FROM_NOW"));
            })
            .compose(fromNow -> {
                assertEquals(409, fromNow.statusCode(),
                    "FROM_NOW does not identify the stored bound and must return 409. Response: "
                        + fromNow.bodyAsString());
                return getOptions(groupName);
            })
            .onSuccess(getResponse -> testContext.verify(() -> {
                JsonObject options = getResponse.bodyAsJsonObject()
                    .getJsonObject("subscriptionOptions");

                assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"),
                           "A refused update must keep the stored start position");
                assertEquals(42, options.getInteger("startFromMessageId"),
                           "A refused update must keep the stored message id");

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testUpdateChangesOnlyNamedHeartbeatSetting(VertxTestContext testContext) {
        String groupName = "test-update-heartbeat-only";

        createGroup(groupName)
            // Stored settings that differ from the defaults (60 s, 300 s).
            .compose(v -> postOptions(groupName, fromMessageId(42)
                .put("heartbeatIntervalSeconds", 45)
                .put("heartbeatTimeoutSeconds", 200)))
            .compose(created -> {
                assertEquals(200, created.statusCode(), "Response: " + created.bodyAsString());
                return postOptions(groupName, new JsonObject().put("heartbeatIntervalSeconds", 30));
            })
            .compose(updated -> {
                assertEquals(200, updated.statusCode(), "Response: " + updated.bodyAsString());

                // The response returns the stored options, not the submitted object.
                JsonObject options = updated.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals(30, options.getInteger("heartbeatIntervalSeconds"));
                assertEquals(200, options.getInteger("heartbeatTimeoutSeconds"),
                    "The omitted timeout must keep its stored value and must not become the default");
                assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"),
                    "The omitted start position must not be reported as the default");
                assertEquals(42, options.getInteger("startFromMessageId"));

                return getOptions(groupName);
            })
            .onSuccess(getResponse -> testContext.verify(() -> {
                JsonObject body = getResponse.bodyAsJsonObject();
                JsonObject options = body.getJsonObject("subscriptionOptions");

                assertEquals(30, options.getInteger("heartbeatIntervalSeconds"));
                assertEquals(200, options.getInteger("heartbeatTimeoutSeconds"));
                assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"));
                assertEquals(42, options.getInteger("startFromMessageId"));
                assertEquals("ACTIVE", body.getString("status"));

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testInvalidStartPositionValues(VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (Test 10: invalid start position → 400, IllegalArgumentException) ---");
        
        String groupName = "test-invalid-group";

        createGroup(groupName)
            .compose(v -> postOptions(groupName, new JsonObject().put("startPosition", "INVALID_POSITION")))
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(400, response.statusCode(),
                         "An invalid start position must return 400 Bad Request");
                
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    void testConsumerGroupWithMessageFiltering(VertxTestContext testContext) {
        String groupName = "filtered_group_" + System.currentTimeMillis();

        // Step 1: Create consumer group with group-level filter (only US region)
        JsonObject createRequest = new JsonObject()
            .put("groupName", groupName)
            .put("groupFilter", new JsonObject()
                .put("type", "header")
                .put("headerKey", "region")
                .put("headerValue", "US"));

        String createPath = String.format("/api/v1/queues/%s/%s/consumer-groups", setupId, QUEUE_NAME);

        webClient.post(TEST_PORT, "localhost", createPath)
            .putHeader("content-type", "application/json")
            .sendJsonObject(createRequest)
            .compose(createResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, createResponse.statusCode(),
                                "Consumer group creation should succeed. Response: " + createResponse.bodyAsString());
                });

                // Step 2: Add consumer with per-consumer filter (only HIGH priority)
                JsonObject joinRequest = new JsonObject()
                    .put("memberName", "priority-consumer")
                    .put("messageFilter", new JsonObject()
                        .put("type", "priority")
                        .put("minPriority", "HIGH"));

                String joinPath = String.format("/api/v1/queues/%s/%s/consumer-groups/%s/members",
                                               setupId, QUEUE_NAME, groupName);

                return webClient.post(TEST_PORT, "localhost", joinPath)
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(joinRequest);
            })
            .onSuccess(joinResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, joinResponse.statusCode(),
                                "Consumer join should succeed. Response: " + joinResponse.bodyAsString());

                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("POST subscription options for non-existent setup returns 404 with JSON error body")
    void testPostSubscriptionOptionsNonExistentSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/consumer-groups/%s/%s/%s/subscription",
                "nonexistent-setup-" + System.currentTimeMillis(), QUEUE_NAME, "some-group");

        webClient.post(TEST_PORT, "localhost", path)
                .sendJsonObject(new JsonObject().put("startPosition", "FROM_NOW"))
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                            "Expected 404 for nonexistent setup, got: " + response.statusCode()
                            + " - " + response.bodyAsString());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body.getString("error"), "Response must contain 'error' field");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("An update that names the stored start position is accepted")
    void testUpdateWithEqualStartPositionIsAccepted(VertxTestContext testContext) {
        String groupName = "test-update-equal-position";

        createGroup(groupName)
            .compose(v -> postOptions(groupName, fromMessageId(42)
                .put("heartbeatIntervalSeconds", 45)
                .put("heartbeatTimeoutSeconds", 200)))
            .compose(created -> {
                assertEquals(200, created.statusCode(), "Response: " + created.bodyAsString());
                return postOptions(groupName, fromMessageId(42).put("heartbeatTimeoutSeconds", 400));
            })
            .onSuccess(updated -> testContext.verify(() -> {
                assertEquals(200, updated.statusCode(),
                    "The stored start position must be accepted. Response: " + updated.bodyAsString());

                JsonObject options = updated.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals(42, options.getInteger("startFromMessageId"));
                assertEquals(45, options.getInteger("heartbeatIntervalSeconds"));
                assertEquals(400, options.getInteger("heartbeatTimeoutSeconds"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("An update leaves the lifecycle status unchanged")
    void testUpdateKeepsLifecycleStatus(VertxTestContext testContext) {
        String groupName = "test-update-keeps-status";
        // The subscription has the topic of the consumer group: the queue name.
        String topic = QUEUE_NAME;

        createGroup(groupName)
            .compose(v -> postOptions(groupName, fromMessageId(42)))
            .compose(created -> {
                assertEquals(200, created.statusCode(), "Response: " + created.bodyAsString());
                // A graceful stop of the whole group stores STOPPED. Only a start makes it ACTIVE again.
                return setupService.getSubscriptionServiceForSetup(setupId).stopGroup(topic, groupName);
            })
            .compose(v -> postOptions(groupName, new JsonObject().put("heartbeatIntervalSeconds", 30)))
            .compose(updated -> {
                assertEquals(200, updated.statusCode(), "Response: " + updated.bodyAsString());
                return getOptions(groupName);
            })
            .onSuccess(getResponse -> testContext.verify(() -> {
                JsonObject body = getResponse.bodyAsJsonObject();
                assertEquals("STOPPED", body.getString("status"),
                    "An update must not make a stopped subscription active");
                assertEquals(30, body.getJsonObject("subscriptionOptions").getInteger("heartbeatIntervalSeconds"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("An update whose timeout does not exceed the stored interval returns 400")
    void testUpdateWithTimeoutNotGreaterThanStoredIntervalReturns400(VertxTestContext testContext) {
        String groupName = "test-update-timeout-low";

        createGroup(groupName)
            .compose(v -> postOptions(groupName, fromMessageId(42)
                .put("heartbeatIntervalSeconds", 150)
                .put("heartbeatTimeoutSeconds", 400)))
            .compose(created -> {
                assertEquals(200, created.statusCode(), "Response: " + created.bodyAsString());
                // 100 exceeds the default interval of 60, but not the stored interval of 150.
                return postOptions(groupName, new JsonObject().put("heartbeatTimeoutSeconds", 100));
            })
            .compose(rejected -> {
                assertEquals(400, rejected.statusCode(),
                    "The timeout is checked against the stored interval. Response: " + rejected.bodyAsString());
                return getOptions(groupName);
            })
            .onSuccess(getResponse -> testContext.verify(() -> {
                JsonObject options = getResponse.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals(150, options.getInteger("heartbeatIntervalSeconds"));
                assertEquals(400, options.getInteger("heartbeatTimeoutSeconds"),
                    "A refused update must change no heartbeat setting");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Creating a consumer group fails when its subscription cannot be created")
    void testCreateConsumerGroupFailsWhenSubscriptionIsCancelled(VertxTestContext testContext) {
        String groupName = "test-create-with-cancelled-subscription";
        String groupPath = String.format("/api/v1/queues/%s/%s/consumer-groups/%s", setupId, QUEUE_NAME, groupName);
        String createGroupPath = String.format("/api/v1/queues/%s/%s/consumer-groups", setupId, QUEUE_NAME);

        createGroup(groupName)
            .compose(v -> postOptions(groupName, fromMessageId(42)))
            .compose(created -> {
                assertEquals(200, created.statusCode(), "Response: " + created.bodyAsString());
                // Cancel the subscription, then delete the in-memory group.
                return webClient.delete(TEST_PORT, "localhost", subscriptionPath(groupName)).send();
            })
            .compose(cancelled -> {
                assertEquals(204, cancelled.statusCode());
                return webClient.delete(TEST_PORT, "localhost", groupPath).send();
            })
            .compose(deleted -> {
                assertEquals(200, deleted.statusCode(), "Response: " + deleted.bodyAsString());
                // The subscription of this group is CANCELLED, so it cannot be created again.
                return webClient.post(TEST_PORT, "localhost", createGroupPath)
                    .sendJsonObject(new JsonObject()
                        .put("groupName", groupName)
                        .put("subscriptionOptions", new JsonObject().put("startPosition", "FROM_BEGINNING")));
            })
            .compose(createResponse -> {
                assertEquals(409, createResponse.statusCode(),
                    "The request must fail when the subscription cannot be created. Response: "
                        + createResponse.bodyAsString());
                return webClient.get(TEST_PORT, "localhost", groupPath).send();
            })
            .onSuccess(getGroupResponse -> testContext.verify(() -> {
                assertEquals(404, getGroupResponse.statusCode(),
                    "No consumer group must exist after the failed request. Response: "
                        + getGroupResponse.bodyAsString());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("An update with a malformed startFromTimestamp returns 400 and keeps the stored bound")
    void testUpdateWithMalformedStartFromTimestampReturns400(VertxTestContext testContext) {
        String groupName = "test-update-malformed-timestamp";

        createGroup(groupName)
            .compose(v -> postOptions(groupName, fromMessageId(42)))
            .compose(created -> {
                assertEquals(200, created.statusCode(), "Response: " + created.bodyAsString());
                return postOptions(groupName, new JsonObject()
                    .put("startPosition", "FROM_TIMESTAMP")
                    .put("startFromTimestamp", "not-a-timestamp"));
            })
            .compose(rejected -> {
                assertEquals(400, rejected.statusCode(),
                    "A malformed timestamp is an invalid request. Response: " + rejected.bodyAsString());
                return getOptions(groupName);
            })
            .onSuccess(getResponse -> testContext.verify(() -> {
                JsonObject options = getResponse.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"));
                assertEquals(42, options.getInteger("startFromMessageId"),
                    "A refused update must keep the stored start bound");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("An update with no request body returns 400 and creates no subscription")
    void testUpdateWithoutBodyReturns400(VertxTestContext testContext) {
        String groupName = "test-update-no-body";

        createGroup(groupName)
            .compose(v -> webClient.post(TEST_PORT, "localhost", subscriptionPath(groupName)).send())
            .compose(rejected -> {
                assertEquals(400, rejected.statusCode(),
                    "A request with no body is an invalid request. Response: " + rejected.bodyAsString());
                return getOptions(groupName);
            })
            .onSuccess(getResponse -> testContext.verify(() -> {
                assertEquals("NOT_CONFIGURED", getResponse.bodyAsJsonObject().getString("status"),
                    "A refused request must create no subscription");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String subscriptionPath(String groupName) {
        return String.format("/api/v1/consumer-groups/%s/%s/%s/subscription", setupId, QUEUE_NAME, groupName);
    }

    private static JsonObject fromMessageId(int messageId) {
        return new JsonObject()
            .put("startPosition", "FROM_MESSAGE_ID")
            .put("startFromMessageId", messageId);
    }

    /** Creates a consumer group and fails when the server does not answer 201. */
    private Future<Void> createGroup(String groupName) {
        JsonObject createGroupRequest = new JsonObject()
            .put("groupName", groupName)
            .put("maxMembers", 5);

        String createGroupPath = String.format("/api/v1/queues/%s/%s/consumer-groups", setupId, QUEUE_NAME);

        return webClient.post(TEST_PORT, "localhost", createGroupPath)
            .sendJsonObject(createGroupRequest)
            .compose(response -> {
                if (response.statusCode() == 201) {
                    return Future.<Void>succeededFuture();
                }
                return Future.failedFuture("Failed to create consumer group: "
                    + response.statusCode() + " " + response.bodyAsString());
            });
    }

    private Future<HttpResponse<Buffer>> postOptions(String groupName, JsonObject options) {
        return webClient.post(TEST_PORT, "localhost", subscriptionPath(groupName))
            .sendJsonObject(options);
    }

    private Future<HttpResponse<Buffer>> getOptions(String groupName) {
        return webClient.get(TEST_PORT, "localhost", subscriptionPath(groupName)).send();
    }
}
