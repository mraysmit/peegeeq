package dev.mars.peegeeq.rest.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for RestServerConfig and MonitoringConfig.
 * Tests validation, parsing, defaults, and edge cases.
 */
@DisplayName("RestServerConfig Tests")
@org.junit.jupiter.api.Tag("core")
class RestServerConfigTest {

    @Nested
    @DisplayName("RestServerConfig Validation")
    class RestServerConfigValidationTests {

        @Test
        @DisplayName("Should accept valid port range (1-65535)")
        void testValidPortRange() {
            List<String> origins = List.of("http://localhost");
            assertDoesNotThrow(() -> new RestServerConfig(1, RestServerConfig.MonitoringConfig.defaults(), origins));
            assertDoesNotThrow(() -> new RestServerConfig(8080, RestServerConfig.MonitoringConfig.defaults(), origins));
            assertDoesNotThrow(
                    () -> new RestServerConfig(65535, RestServerConfig.MonitoringConfig.defaults(), origins));
        }

        @Test
        @DisplayName("Should reject port 0")
        void testPortZero() {
            var config = RestServerConfig.MonitoringConfig.defaults();
            List<String> origins = List.of("http://localhost");
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig(0, config, origins));
            assertTrue(exception.getMessage().contains("port must be 1-65535"));
        }

        @Test
        @DisplayName("Should reject negative port")
        void testNegativePort() {
            var config = RestServerConfig.MonitoringConfig.defaults();
            List<String> origins = List.of("http://localhost");
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig(-1, config, origins));
            assertTrue(exception.getMessage().contains("port must be 1-65535"));
        }

        @Test
        @DisplayName("Should reject port above 65535")
        void testPortTooHigh() {
            var config = RestServerConfig.MonitoringConfig.defaults();
            List<String> origins = List.of("http://localhost");
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig(65536, config, origins));
            assertTrue(exception.getMessage().contains("port must be 1-65535"));
        }

        @Test
        @DisplayName("Should reject null monitoring config")
        void testNullMonitoringConfig() {
            List<String> origins = List.of("http://localhost");
            assertThrows(NullPointerException.class, () -> new RestServerConfig(8080, null, origins));
        }

        @Test
        @DisplayName("Should reject null allowedOrigins")
        void testNullAllowedOrigins() {
            var config = RestServerConfig.MonitoringConfig.defaults();
            assertThrows(NullPointerException.class, () -> new RestServerConfig(8080, config, null));
        }
    }

    @Nested
    @DisplayName("MonitoringConfig Validation")
    class MonitoringConfigValidationTests {

        @Test
        @DisplayName("Should accept valid maxConnections")
        void testValidMaxConnections() {
            assertDoesNotThrow(() -> new RestServerConfig.MonitoringConfig(
                    1, 10, 5, 1, 60, 300000, 5000, 1000));
            assertDoesNotThrow(() -> new RestServerConfig.MonitoringConfig(
                    10000, 10, 5, 1, 60, 300000, 5000, 1000));
        }

        @Test
        @DisplayName("Should reject maxConnections = 0")
        void testMaxConnectionsZero() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(0, 10, 5, 1, 60, 300000, 5000, 1000));
            assertTrue(exception.getMessage().contains("maxConnections must be positive"));
        }

        @Test
        @DisplayName("Should reject negative maxConnections")
        void testMaxConnectionsNegative() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(-1, 10, 5, 1, 60, 300000, 5000, 1000));
            assertTrue(exception.getMessage().contains("maxConnections must be positive"));
        }

        @Test
        @DisplayName("Should reject maxConnectionsPerIp = 0")
        void testMaxConnectionsPerIpZero() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(1000, 0, 5, 1, 60, 300000, 5000, 1000));
            assertTrue(exception.getMessage().contains("maxConnectionsPerIp must be positive"));
        }

        @Test
        @DisplayName("Should reject defaultIntervalSeconds = 0")
        void testDefaultIntervalSecondsZero() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(1000, 10, 0, 1, 60, 300000, 5000, 1000));
            assertTrue(exception.getMessage().contains("defaultIntervalSeconds must be positive"));
        }

        @Test
        @DisplayName("Should reject minIntervalSeconds = 0")
        void testMinIntervalSecondsZero() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(1000, 10, 5, 0, 60, 300000, 5000, 1000));
            assertTrue(exception.getMessage().contains("minIntervalSeconds must be positive"));
        }

        @Test
        @DisplayName("Should reject maxIntervalSeconds = 0")
        void testMaxIntervalSecondsZero() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(1000, 10, 5, 1, 0, 300000, 5000, 1000));
            assertTrue(exception.getMessage().contains("maxIntervalSeconds must be positive"));
        }

        @Test
        @DisplayName("Should reject minIntervalSeconds > defaultIntervalSeconds")
        void testMinGreaterThanDefault() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(1000, 10, 5, 10, 60, 300000, 5000, 1000));
            assertTrue(exception.getMessage().contains("minIntervalSeconds must be <= defaultIntervalSeconds"));
        }

        @Test
        @DisplayName("Should reject defaultIntervalSeconds > maxIntervalSeconds")
        void testDefaultGreaterThanMax() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(1000, 10, 65, 1, 60, 300000, 5000, 1000));
            assertTrue(exception.getMessage().contains("defaultIntervalSeconds must be <= maxIntervalSeconds"));
        }

        @Test
        @DisplayName("Should accept valid interval hierarchy: min <= default <= max")
        void testValidIntervalHierarchy() {
            assertDoesNotThrow(() -> new RestServerConfig.MonitoringConfig(
                    1000, 10, 5, 1, 10, 300000, 5000, 1000));
            assertDoesNotThrow(() -> new RestServerConfig.MonitoringConfig(
                    1000, 10, 30, 30, 30, 300000, 5000, 1000));
        }

        @Test
        @DisplayName("Should reject idleTimeoutMs = 0")
        void testIdleTimeoutMsZero() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(1000, 10, 5, 1, 60, 0, 5000, 1000));
            assertTrue(exception.getMessage().contains("idleTimeoutMs must be positive"));
        }

        @Test
        @DisplayName("Should reject cacheTtlMs = 0")
        void testCacheTtlMsZero() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestServerConfig.MonitoringConfig(1000, 10, 5, 1, 60, 300000, 0, 1000));
            assertTrue(exception.getMessage().contains("cacheTtlMs must be positive"));
        }

        @Test
        @DisplayName("Should allow jitterMs = 0 (no jitter)")
        void testJitterMsZero() {
            // jitterMs = 0 is valid (means no jitter variation)
            assertDoesNotThrow(
                    () -> new RestServerConfig.MonitoringConfig(1000, 10, 5, 1, 60, 300000, 5000, 0),
                    "jitterMs = 0 should be allowed (no jitter)");
        }
    }

    @Nested
    @DisplayName("MonitoringConfig Defaults")
    class MonitoringConfigDefaultsTests {

        @Test
        @DisplayName("Should provide production-ready defaults")
        void testDefaults() {
            var defaults = RestServerConfig.MonitoringConfig.defaults();

            assertNotNull(defaults);
            assertEquals(1000, defaults.maxConnections());
            assertEquals(10, defaults.maxConnectionsPerIp());
            assertEquals(5, defaults.defaultIntervalSeconds());
            assertEquals(1, defaults.minIntervalSeconds());
            assertEquals(60, defaults.maxIntervalSeconds());
            assertEquals(300000, defaults.idleTimeoutMs()); // 5 minutes
            assertEquals(5000, defaults.cacheTtlMs());
            assertEquals(1000, defaults.jitterMs());
        }

        @Test
        @DisplayName("Default config should pass validation")
        void testDefaultsAreValid() {
            assertDoesNotThrow(() -> {
                var defaults = RestServerConfig.MonitoringConfig.defaults();
                List<String> origins = List.of("http://localhost");
                new RestServerConfig(8080, defaults, origins);
            });
        }

        @Test
        @DisplayName("Defaults should satisfy interval hierarchy")
        void testDefaultIntervalHierarchy() {
            var defaults = RestServerConfig.MonitoringConfig.defaults();

            assertTrue(defaults.minIntervalSeconds() <= defaults.defaultIntervalSeconds(),
                    "min <= default");
            assertTrue(defaults.defaultIntervalSeconds() <= defaults.maxIntervalSeconds(),
                    "default <= max");
        }
    }

    @Nested
    @DisplayName("JSON Parsing")
    class JsonParsingTests {

        @Test
        @DisplayName("Should parse complete config from JSON")
        void testParseCompleteConfig() {
            JsonObject json = new JsonObject()
                    .put("port", 9090)
                    .put("allowedOrigins", new JsonArray().add("http://localhost").add("https://example.com"))
                    .put("monitoring", new JsonObject()
                            .put("maxConnections", 2000)
                            .put("maxConnectionsPerIp", 20)
                            .put("defaultIntervalSeconds", 10)
                            .put("minIntervalSeconds", 2)
                            .put("maxIntervalSeconds", 120)
                            .put("idleTimeoutMs", 600000)
                            .put("cacheTtlMs", 10000)
                            .put("jitterMs", 2000));

            RestServerConfig config = RestServerConfig.from(json);

            assertEquals(9090, config.port());
            assertEquals(List.of("http://localhost", "https://example.com"), config.allowedOrigins());
            assertEquals(2000, config.monitoring().maxConnections());
            assertEquals(20, config.monitoring().maxConnectionsPerIp());
            assertEquals(10, config.monitoring().defaultIntervalSeconds());
            assertEquals(2, config.monitoring().minIntervalSeconds());
            assertEquals(120, config.monitoring().maxIntervalSeconds());
            assertEquals(600000, config.monitoring().idleTimeoutMs());
            assertEquals(10000, config.monitoring().cacheTtlMs());
            assertEquals(2000, config.monitoring().jitterMs());
        }

        @Test
        @DisplayName("Should require allowedOrigins in JSON")
        void testParseMissingAllowedOrigins() {
            JsonObject json = new JsonObject().put("port", 8080);

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> RestServerConfig.from(json));
            assertTrue(exception.getMessage().contains("allowedOrigins must be provided"));
        }

        @Test
        @DisplayName("Should reject empty allowedOrigins array")
        void testParseEmptyAllowedOrigins() {
            JsonObject json = new JsonObject()
                    .put("port", 8080)
                    .put("allowedOrigins", new JsonArray());

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> RestServerConfig.from(json));
            assertTrue(exception.getMessage().contains("allowedOrigins must be provided"));
        }

        @Test
        @DisplayName("Should use defaults for missing monitoring section")
        void testParseMissingMonitoring() {
            JsonObject json = new JsonObject()
                    .put("port", 8080)
                    .put("allowedOrigins", new JsonArray().add("http://localhost"));

            RestServerConfig config = RestServerConfig.from(json);

            assertEquals(8080, config.port());
            assertNotNull(config.monitoring());
            assertEquals(RestServerConfig.MonitoringConfig.defaults(), config.monitoring());
        }

        @Test
        @DisplayName("Should use defaults for partially specified monitoring")
        void testParsePartialMonitoring() {
            JsonObject json = new JsonObject()
                    .put("port", 8080)
                    .put("allowedOrigins", new JsonArray().add("http://localhost"))
                    .put("monitoring", new JsonObject()
                            .put("maxConnections", 5000));

            RestServerConfig config = RestServerConfig.from(json);

            assertEquals(8080, config.port());
            assertEquals(5000, config.monitoring().maxConnections());
            // Other fields should use defaults
            assertEquals(10, config.monitoring().maxConnectionsPerIp());
            assertEquals(5, config.monitoring().defaultIntervalSeconds());
        }

        @Test
        @DisplayName("Should use default port 8080 if missing")
        void testParseMissingPort() {
            JsonObject json = new JsonObject()
                    .put("allowedOrigins", new JsonArray().add("http://localhost"));

            RestServerConfig config = RestServerConfig.from(json);

            assertEquals(8080, config.port());
        }

        @Test
        @DisplayName("Should reject invalid JSON port")
        void testParseInvalidPort() {
            JsonObject json = new JsonObject()
                    .put("port", 70000)
                    .put("allowedOrigins", new JsonArray().add("http://localhost"));

            assertThrows(IllegalArgumentException.class, () -> RestServerConfig.from(json));
        }

        @Test
        @DisplayName("Should reject invalid JSON monitoring values")
        void testParseInvalidMonitoring() {
            JsonObject json = new JsonObject()
                    .put("port", 8080)
                    .put("monitoring", new JsonObject()
                            .put("maxConnections", -1));

            assertThrows(IllegalArgumentException.class, () -> RestServerConfig.from(json));
        }
    }

    @Nested
    @DisplayName("Record Accessor Methods")
    class RecordAccessorTests {

        @Test
        @DisplayName("Should use record accessors (not getters)")
        void testRecordAccessors() {
            var monitoring = new RestServerConfig.MonitoringConfig(
                    1000, 10, 5, 1, 60, 300000, 5000, 1000);
            var config = new RestServerConfig(8080, monitoring, java.util.List.of("*"));

            // Record accessors are methods without 'get' prefix
            assertEquals(8080, config.port());
            assertEquals(monitoring, config.monitoring());
            assertEquals(1000, config.monitoring().maxConnections());
            assertEquals(10, config.monitoring().maxConnectionsPerIp());
            assertEquals(5, config.monitoring().defaultIntervalSeconds());
            assertEquals(1, config.monitoring().minIntervalSeconds());
            assertEquals(60, config.monitoring().maxIntervalSeconds());
            assertEquals(300000, config.monitoring().idleTimeoutMs());
            assertEquals(5000, config.monitoring().cacheTtlMs());
            assertEquals(1000, config.monitoring().jitterMs());
        }

        @Test
        @DisplayName("Records should be immutable")
        void testImmutability() {
            var monitoring = RestServerConfig.MonitoringConfig.defaults();
            List<String> origins = List.of("http://localhost");
            var config = new RestServerConfig(8080, monitoring, origins);

            // Records are immutable - no setters exist
            // This test just verifies we can create and read, not modify
            assertNotNull(config.port());
            assertNotNull(config.monitoring());
            assertNotNull(config.allowedOrigins());
        }
    }

    @Nested
    @DisplayName("Record Equality and HashCode")
    class RecordEqualityTests {

        @Test
        @DisplayName("RestServerConfig equality based on all fields")
        void testRestServerConfigEquality() {
            var monitoring = RestServerConfig.MonitoringConfig.defaults();
            List<String> origins = List.of("http://localhost");
            var config1 = new RestServerConfig(8080, monitoring, origins);
            var config2 = new RestServerConfig(8080, monitoring, origins);
            var config3 = new RestServerConfig(9090, monitoring, origins);

            assertEquals(config1, config2);
            assertNotEquals(config1, config3);
        }

        @Test
        @DisplayName("RestServerConfig hashCode contract")
        void testRestServerConfigHashCode() {
            var monitoring = RestServerConfig.MonitoringConfig.defaults();
            List<String> origins = List.of("http://localhost");
            var config1 = new RestServerConfig(8080, monitoring, origins);
            var config2 = new RestServerConfig(8080, monitoring, origins);

            assertEquals(config1.hashCode(), config2.hashCode());
        }

        @Test
        @DisplayName("MonitoringConfig equality based on all fields")
        void testMonitoringConfigEquality() {
            var config1 = new RestServerConfig.MonitoringConfig(
                    1000, 10, 5, 1, 60, 300000, 5000, 1000);
            var config2 = new RestServerConfig.MonitoringConfig(
                    1000, 10, 5, 1, 60, 300000, 5000, 1000);
            var config3 = new RestServerConfig.MonitoringConfig(
                    2000, 10, 5, 1, 60, 300000, 5000, 1000);

            assertEquals(config1, config2);
            assertNotEquals(config1, config3);
        }

        @Test
        @DisplayName("MonitoringConfig hashCode contract")
        void testMonitoringConfigHashCode() {
            var config1 = new RestServerConfig.MonitoringConfig(
                    1000, 10, 5, 1, 60, 300000, 5000, 1000);
            var config2 = new RestServerConfig.MonitoringConfig(
                    1000, 10, 5, 1, 60, 300000, 5000, 1000);

            assertEquals(config1.hashCode(), config2.hashCode());
        }
    }

    @Nested
    @DisplayName("Edge Cases and Boundary Values")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle minimum valid port (1)")
        void testMinimumPort() {
            var config = RestServerConfig.MonitoringConfig.defaults();
            List<String> origins = List.of("http://localhost");
            assertDoesNotThrow(() -> new RestServerConfig(1, config, origins));
        }

        @Test
        @DisplayName("Should handle maximum valid port (65535)")
        void testMaximumPort() {
            var config = RestServerConfig.MonitoringConfig.defaults();
            List<String> origins = List.of("http://localhost");
            assertDoesNotThrow(() -> new RestServerConfig(65535, config, origins));
        }

        @Test
        @DisplayName("Should handle all minimum valid monitoring values")
        void testMinimumMonitoringValues() {
            assertDoesNotThrow(() -> new RestServerConfig.MonitoringConfig(
                    1, 1, 1, 1, 1, 1, 1, 1));
        }

        @Test
        @DisplayName("Should handle very large monitoring values")
        void testLargeMonitoringValues() {
            assertDoesNotThrow(() -> new RestServerConfig.MonitoringConfig(
                    100000, 1000, 300, 1, 3600, 3600000, 60000, 10000));
        }

        @Test
        @DisplayName("Should handle equal min/default/max intervals")
        void testEqualIntervals() {
            assertDoesNotThrow(() -> new RestServerConfig.MonitoringConfig(
                    1000, 10, 30, 30, 30, 300000, 5000, 1000));
        }
    }
}
