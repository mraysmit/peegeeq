package dev.mars.peegeeq.rest.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick test to verify CORS configuration changes work correctly.
 */
@Tag("core")
class CorsConfigQuickTest {

    @Test
    void testCorsConfigurationRequired() {
        // Test that allowedOrigins is required in JSON
        JsonObject jsonWithoutCors = new JsonObject().put("port", 8080);

        Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> RestServerConfig.from(jsonWithoutCors));

        assertTrue(exception.getMessage().contains("allowedOrigins must be provided"));
    }

    @Test
    void testCorsConfigurationWithOrigins() {
        // Test that allowedOrigins works with explicit origins
        JsonObject json = new JsonObject()
                .put("port", 8080)
                .put("allowedOrigins", new JsonArray()
                        .add("http://localhost:5173")
                        .add("https://example.com"));

        RestServerConfig config = RestServerConfig.from(json);

        assertEquals(8080, config.port());
        assertEquals(List.of("http://localhost:5173", "https://example.com"), config.allowedOrigins());
    }

    @Test
    void testEmptyCorsArrayRejected() {
        // Test that empty allowedOrigins array is rejected
        JsonObject json = new JsonObject()
                .put("port", 8080)
                .put("allowedOrigins", new JsonArray());

        Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> RestServerConfig.from(json));

        assertTrue(exception.getMessage().contains("allowedOrigins must be provided"));
    }

    @Test
    void testThreeParameterConstructor() {
        // Test that 3-parameter constructor works
        List<String> origins = List.of("http://localhost", "https://example.com");
        RestServerConfig config = new RestServerConfig(
                8080,
                RestServerConfig.MonitoringConfig.defaults(),
                origins);

        assertEquals(8080, config.port());
        assertEquals(origins, config.allowedOrigins());
        assertNotNull(config.monitoring());
    }

    @Test
    void testNullCorsArrayRejected() {
        // Test that null allowedOrigins is rejected
        Exception exception = assertThrows(
                NullPointerException.class,
                () -> new RestServerConfig(8080, RestServerConfig.MonitoringConfig.defaults(), null));

        assertTrue(exception.getMessage().contains("allowedOrigins must not be null"));
    }
}
