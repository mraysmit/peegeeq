package dev.mars.peegeeq.test.base;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Base class for tests that require Vert.x and external configuration.
 * Provides standardized configuration loading from JSON files and System properties.
 */
public abstract class BaseConfigurableTest {
    protected static final Logger baseLogger = LoggerFactory.getLogger(BaseConfigurableTest.class);
    
    protected static Vertx vertx;
    protected static JsonObject testConfig;

    /**
     * Initializes Vert.x and loads configuration from the specified file.
     * This method blocks until configuration is loaded or timeout occurs.
     * 
     * @param configFileName The name of the JSON configuration file in the classpath (e.g., "test-config.json")
     */
    protected static void setupVertxAndConfig(String configFileName) {
        if (vertx != null) {
            baseLogger.warn("Vert.x instance already exists, skipping initialization");
            return;
        }

        baseLogger.info("Initializing Vert.x and loading configuration from {}", configFileName);
        vertx = Vertx.vertx();
        
        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];

        ConfigStoreOptions fileStore = new ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(new JsonObject().put("path", configFileName));

        // Allow overriding via system properties (e.g. -Dserver.port=9090)
        ConfigStoreOptions sysPropsStore = new ConfigStoreOptions()
                .setType("sys");

        ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                .addStore(fileStore)
                .addStore(sysPropsStore);

        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

        retriever.getConfig().onSuccess(config -> {
            testConfig = config;
            baseLogger.info("Loaded test configuration successfully");
            latch.countDown();
        }).onFailure(err -> {
            baseLogger.error("Failed to load test configuration", err);
            error[0] = err;
            latch.countDown();
        });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for configuration load");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for configuration load", e);
        }

        if (error[0] != null) {
            throw new RuntimeException("Failed to load configuration", error[0]);
        }
    }
    
    protected static JsonObject getConfig() {
        if (testConfig == null) {
            throw new IllegalStateException("Configuration not loaded. Call setupVertxAndConfig() first.");
        }
        return testConfig;
    }
}
