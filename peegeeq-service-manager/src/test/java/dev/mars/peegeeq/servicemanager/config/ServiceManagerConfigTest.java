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

package dev.mars.peegeeq.servicemanager.config;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ServiceManagerConfig.
 */
class ServiceManagerConfigTest {

    @Test
    @DisplayName("default constructor uses default values")
    void defaultConstructor_usesDefaultValues() {
        ServiceManagerConfig config = new ServiceManagerConfig();
        
        assertEquals("localhost", config.getConsulHost());
        assertEquals(8500, config.getConsulPort());
        assertEquals(9090, config.getServicePort());
        assertEquals(10000, config.getRequestTimeout());
        assertEquals(30000, config.getCacheRefreshInterval());
        assertEquals("peegeeq-service-manager", config.getServiceName());
        assertEquals("development", config.getEnvironment());
        assertEquals("default", config.getRegion());
    }

    @Test
    @DisplayName("constructor with JsonObject uses provided values")
    void constructorWithJsonObject_usesProvidedValues() {
        JsonObject json = new JsonObject()
            .put("consul.host", "consul.example.com")
            .put("consul.port", 8501)
            .put("service.port", 9091)
            .put("request.timeout", 5000)
            .put("cache.refresh.interval", 15000)
            .put("service.name", "my-service")
            .put("environment", "production")
            .put("region", "us-east-1");
        
        ServiceManagerConfig config = new ServiceManagerConfig(json);
        
        assertEquals("consul.example.com", config.getConsulHost());
        assertEquals(8501, config.getConsulPort());
        assertEquals(9091, config.getServicePort());
        assertEquals(5000, config.getRequestTimeout());
        assertEquals(15000, config.getCacheRefreshInterval());
        assertEquals("my-service", config.getServiceName());
        assertEquals("production", config.getEnvironment());
        assertEquals("us-east-1", config.getRegion());
    }

    @Test
    @DisplayName("builder creates config with specified values")
    void builder_createsConfigWithSpecifiedValues() {
        ServiceManagerConfig config = ServiceManagerConfig.builder()
            .consulHost("consul.local")
            .consulPort(8502)
            .servicePort(9092)
            .requestTimeout(3000)
            .cacheRefreshInterval(10000)
            .serviceName("test-service")
            .environment("staging")
            .region("eu-west-1")
            .build();
        
        assertEquals("consul.local", config.getConsulHost());
        assertEquals(8502, config.getConsulPort());
        assertEquals(9092, config.getServicePort());
        assertEquals(3000, config.getRequestTimeout());
        assertEquals(10000, config.getCacheRefreshInterval());
        assertEquals("test-service", config.getServiceName());
        assertEquals("staging", config.getEnvironment());
        assertEquals("eu-west-1", config.getRegion());
    }

    @Test
    @DisplayName("getConsulAddress returns formatted address")
    void getConsulAddress_returnsFormattedAddress() {
        ServiceManagerConfig config = ServiceManagerConfig.builder()
            .consulHost("consul.example.com")
            .consulPort(8500)
            .build();
        
        assertEquals("consul.example.com:8500", config.getConsulAddress());
    }

    @Test
    @DisplayName("getServiceAddress returns localhost with port")
    void getServiceAddress_returnsLocalhostWithPort() {
        ServiceManagerConfig config = ServiceManagerConfig.builder()
            .servicePort(9090)
            .build();
        
        assertEquals("localhost:9090", config.getServiceAddress());
    }

    @Test
    @DisplayName("toString contains all fields")
    void toString_containsAllFields() {
        ServiceManagerConfig config = ServiceManagerConfig.builder()
            .consulHost("consul.local")
            .consulPort(8500)
            .servicePort(9090)
            .serviceName("test")
            .environment("dev")
            .region("local")
            .build();
        
        String str = config.toString();
        
        assertTrue(str.contains("consul.local"));
        assertTrue(str.contains("8500"));
        assertTrue(str.contains("9090"));
        assertTrue(str.contains("test"));
        assertTrue(str.contains("dev"));
        assertTrue(str.contains("local"));
    }

    @Test
    @DisplayName("partial builder uses defaults for unset values")
    void partialBuilder_usesDefaultsForUnsetValues() {
        ServiceManagerConfig config = ServiceManagerConfig.builder()
            .serviceName("custom-service")
            .build();
        
        assertEquals("custom-service", config.getServiceName());
        assertEquals("localhost", config.getConsulHost()); // default
        assertEquals(8500, config.getConsulPort()); // default
    }
}

