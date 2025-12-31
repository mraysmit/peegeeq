package dev.mars.peegeeq.rest.config;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ConfigRetriever usage with RestServerConfig.
 * Tests configuration loading from files, environment variables, and system
 * properties.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("ConfigRetriever Integration Tests")
@Tag("core")
class ConfigRetrieverIntegrationTest {

    private Path tempConfigFile;

    @BeforeEach
    void setUp() throws IOException {
        tempConfigFile = Files.createTempFile("test-config", ".json");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempConfigFile != null && Files.exists(tempConfigFile)) {
            Files.delete(tempConfigFile);
        }
    }

    @Nested
    @DisplayName("File-based Configuration")
    class FileBasedConfigTests {

        @Test
        @DisplayName("Should load configuration from JSON file")
        void testLoadFromFile(Vertx vertx, VertxTestContext testContext) throws Exception {
            // Write test config to file
            String configJson = """
                    {
                      "port": 9090,
                      "monitoring": {
                        "maxConnections": 2000,
                        "maxConnectionsPerIp": 20,
                        "defaultIntervalSeconds": 10
                      },
                      "allowedOrigins": ["*"]
                    }
                    """;
            Files.writeString(tempConfigFile, configJson);

            ConfigStoreOptions fileStore = new ConfigStoreOptions()
                    .setType("file")
                    .setOptional(true)
                    .setConfig(new JsonObject().put("path", tempConfigFile.toString()));

            ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                    .addStore(fileStore);

            ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

            retriever.getConfig()
                    .onComplete(testContext.succeeding(json -> testContext.verify(() -> {
                        RestServerConfig config = RestServerConfig.from(json);

                        assertEquals(9090, config.port());
                        assertEquals(2000, config.monitoring().maxConnections());
                        assertEquals(20, config.monitoring().maxConnectionsPerIp());
                        assertEquals(10, config.monitoring().defaultIntervalSeconds());

                        retriever.close();
                        testContext.completeNow();
                    })));

            assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should use defaults when file is missing (optional=true)")
        void testMissingFileWithOptional(Vertx vertx, VertxTestContext testContext) throws Exception {
            ConfigStoreOptions fileStore = new ConfigStoreOptions()
                    .setType("file")
                    .setOptional(true)
                    .setConfig(new JsonObject().put("path", "/nonexistent/config.json"));

            ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                    .addStore(fileStore);

            ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

            retriever.getConfig()
                    .onComplete(testContext.succeeding(json -> testContext.verify(() -> {
                        // Should imply failure because defaults are no longer allowed for
                        // allowedOrigins
                        assertThrows(IllegalArgumentException.class, () -> RestServerConfig.from(json));

                        retriever.close();
                        testContext.completeNow();
                    })));

            assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should handle partial configuration in file")
        void testPartialConfigInFile(Vertx vertx, VertxTestContext testContext) throws Exception {
            String configJson = """
                    {
                      "monitoring": {
                        "maxConnections": 5000
                      },
                      "allowedOrigins": ["*"]
                    }
                    """;
            Files.writeString(tempConfigFile, configJson);

            ConfigStoreOptions fileStore = new ConfigStoreOptions()
                    .setType("file")
                    .setOptional(true)
                    .setConfig(new JsonObject().put("path", tempConfigFile.toString()));

            ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                    .addStore(fileStore);

            ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

            retriever.getConfig()
                    .onComplete(testContext.succeeding(json -> testContext.verify(() -> {
                        RestServerConfig config = RestServerConfig.from(json);

                        // Port should use default
                        assertEquals(8080, config.port());
                        // maxConnections from file
                        assertEquals(5000, config.monitoring().maxConnections());
                        // Other monitoring fields should use defaults
                        assertEquals(10, config.monitoring().maxConnectionsPerIp());

                        retriever.close();
                        testContext.completeNow();
                    })));

            assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("System Properties Configuration")
    class SystemPropertiesConfigTests {

        @Test
        @DisplayName("Should override with system properties")
        void testSystemPropertiesOverride(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
            try {
                // Set system properties
                System.setProperty("port", "7070");
                System.setProperty("monitoring.maxConnections", "3000");

                ConfigStoreOptions sysPropsStore = new ConfigStoreOptions()
                        .setType("sys")
                        .setConfig(new JsonObject().put("cache", false).put("hierarchical", true)); // Enable nested
                                                                                                    // properties

                ConfigStoreOptions fallbackStore = new ConfigStoreOptions()
                        .setType("json")
                        .setConfig(new JsonObject().put("allowedOrigins", new io.vertx.core.json.JsonArray().add("*")));

                ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                        .addStore(fallbackStore)
                        .addStore(sysPropsStore);

                ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

                retriever.getConfig()
                        .onComplete(testContext.succeeding(json -> testContext.verify(() -> {
                            RestServerConfig config = RestServerConfig.from(json);

                            assertEquals(7070, config.port());
                            assertEquals(3000, config.monitoring().maxConnections());

                            retriever.close();
                            testContext.completeNow();
                        })));

                assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
            } finally {
                System.clearProperty("port");
                System.clearProperty("monitoring.maxConnections");
            }
        }
    }

    @Nested
    @DisplayName("Configuration Precedence")
    class ConfigPrecedenceTests {

        @Test
        @DisplayName("System properties should override file configuration")
        void testPrecedenceSystemPropsOverFile(Vertx vertx, VertxTestContext testContext)
                throws Exception, InterruptedException {
            // Write file config
            String configJson = """
                    {
                      "port": 9090,
                      "monitoring": {
                        "maxConnections": 2000
                      },
                      "allowedOrigins": ["*"]
                    }
                    """;
            Files.writeString(tempConfigFile, configJson);

            try {
                // Set system property to override
                System.setProperty("port", "7070");

                ConfigStoreOptions fileStore = new ConfigStoreOptions()
                        .setType("file")
                        .setOptional(true)
                        .setConfig(new JsonObject().put("path", tempConfigFile.toString()));

                ConfigStoreOptions sysPropsStore = new ConfigStoreOptions()
                        .setType("sys")
                        .setConfig(new JsonObject().put("cache", false).put("hierarchical", true));

                ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                        .addStore(fileStore)
                        .addStore(sysPropsStore); // Later stores override earlier ones

                ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

                retriever.getConfig()
                        .onComplete(testContext.succeeding(json -> testContext.verify(() -> {
                            RestServerConfig config = RestServerConfig.from(json);

                            // Port from system property (override)
                            assertEquals(7070, config.port());
                            // maxConnections from file (not overridden)
                            assertEquals(2000, config.monitoring().maxConnections());

                            retriever.close();
                            testContext.completeNow();
                        })));

                assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
            } finally {
                System.clearProperty("port");
            }
        }

        @Test
        @DisplayName("Multiple store precedence: file < env < sysprops")
        void testMultiStorePrecedence(Vertx vertx, VertxTestContext testContext)
                throws Exception, InterruptedException {
            String configJson = """
                    {
                      "port": 9090,
                      "monitoring": {
                        "maxConnections": 2000,
                        "maxConnectionsPerIp": 20,
                        "defaultIntervalSeconds": 10
                      },
                      "allowedOrigins": ["*"]
                    }
                    """;
            Files.writeString(tempConfigFile, configJson);

            try {
                System.setProperty("monitoring.maxConnections", "5000");

                ConfigStoreOptions fileStore = new ConfigStoreOptions()
                        .setType("file")
                        .setOptional(true)
                        .setConfig(new JsonObject().put("path", tempConfigFile.toString()));

                ConfigStoreOptions sysPropsStore = new ConfigStoreOptions()
                        .setType("sys")
                        .setConfig(new JsonObject().put("cache", false).put("hierarchical", true));

                ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                        .addStore(fileStore)
                        .addStore(sysPropsStore);

                ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

                retriever.getConfig()
                        .onComplete(testContext.succeeding(json -> testContext.verify(() -> {
                            RestServerConfig config = RestServerConfig.from(json);

                            assertEquals(9090, config.port()); // from file
                            assertEquals(5000, config.monitoring().maxConnections()); // from sysprops (override)
                            assertEquals(20, config.monitoring().maxConnectionsPerIp()); // from file
                            assertEquals(10, config.monitoring().defaultIntervalSeconds()); // from file

                            retriever.close();
                            testContext.completeNow();
                        })));

                assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
            } finally {
                System.clearProperty("monitoring.maxConnections");
            }
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle invalid JSON in file")
        void testInvalidJsonInFile(Vertx vertx, VertxTestContext testContext) throws Exception {
            Files.writeString(tempConfigFile, "{ invalid json }");

            ConfigStoreOptions fileStore = new ConfigStoreOptions()
                    .setType("file")
                    .setOptional(false)
                    .setConfig(new JsonObject().put("path", tempConfigFile.toString()));

            ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                    .addStore(fileStore);

            ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

            retriever.getConfig()
                    .onComplete(testContext.failing(error -> testContext.verify(() -> {
                        assertNotNull(error);
                        retriever.close();
                        testContext.completeNow();
                    })));

            assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should validate configuration after loading")
        void testValidationAfterLoading(Vertx vertx, VertxTestContext testContext) throws Exception {
            // Invalid port in file
            String configJson = """
                    {
                      "port": 70000,
                      "allowedOrigins": ["*"]
                    }
                    """;
            Files.writeString(tempConfigFile, configJson);

            ConfigStoreOptions fileStore = new ConfigStoreOptions()
                    .setType("file")
                    .setOptional(true)
                    .setConfig(new JsonObject().put("path", tempConfigFile.toString()));

            ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                    .addStore(fileStore);

            ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

            retriever.getConfig()
                    .onComplete(testContext.succeeding(json -> testContext.verify(() -> {
                        // Should throw when trying to create config
                        assertThrows(IllegalArgumentException.class, () -> RestServerConfig.from(json));

                        retriever.close();
                        testContext.completeNow();
                    })));

            assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("Real-world Scenarios")
    class RealWorldScenariosTests {

        @Test
        @DisplayName("Production scenario: File defaults + sysprop overrides")
        void testProductionScenario(Vertx vertx, VertxTestContext testContext) throws Exception, InterruptedException {
            // Production config file
            String configJson = """
                    {
                      "port": 8080,
                      "monitoring": {
                        "maxConnections": 1000,
                        "maxConnectionsPerIp": 10,
                        "defaultIntervalSeconds": 5,
                        "minIntervalSeconds": 1,
                        "maxIntervalSeconds": 60,
                        "idleTimeoutMs": 300000,
                        "cacheTtlMs": 5000,
                        "jitterMs": 1000
                      },
                      "allowedOrigins": ["*"]
                    }
                    """;
            Files.writeString(tempConfigFile, configJson);

            try {
                // Runtime override via system property
                System.setProperty("port", "8888");
                System.setProperty("monitoring.maxConnections", "5000");

                ConfigStoreOptions fileStore = new ConfigStoreOptions()
                        .setType("file")
                        .setOptional(true)
                        .setConfig(new JsonObject().put("path", tempConfigFile.toString()));

                ConfigStoreOptions sysPropsStore = new ConfigStoreOptions()
                        .setType("sys")
                        .setConfig(new JsonObject().put("cache", false).put("hierarchical", true));

                ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                        .addStore(fileStore)
                        .addStore(sysPropsStore);

                ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

                retriever.getConfig()
                        .onComplete(testContext.succeeding(json -> testContext.verify(() -> {
                            RestServerConfig config = RestServerConfig.from(json);

                            // Overridden values
                            assertEquals(8888, config.port());
                            assertEquals(5000, config.monitoring().maxConnections());

                            // File values (not overridden)
                            assertEquals(10, config.monitoring().maxConnectionsPerIp());
                            assertEquals(5, config.monitoring().defaultIntervalSeconds());
                            assertEquals(1, config.monitoring().minIntervalSeconds());
                            assertEquals(60, config.monitoring().maxIntervalSeconds());
                            assertEquals(300000, config.monitoring().idleTimeoutMs());
                            assertEquals(5000, config.monitoring().cacheTtlMs());
                            assertEquals(1000, config.monitoring().jitterMs());

                            retriever.close();
                            testContext.completeNow();
                        })));

                assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
            } finally {
                System.clearProperty("port");
                System.clearProperty("monitoring.maxConnections");
            }
        }
    }
}
