package dev.mars.peegeeq.db.test;

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

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for registering real queue factory implementations in tests.
 *
 * This class uses reflection to register factory implementations that may or may not
 * be available on the classpath. All tests use real implementations with TestContainers
 * - no mocking is used as per project standards.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public class TestFactoryRegistration {

    private static final Logger logger = LoggerFactory.getLogger(TestFactoryRegistration.class);

    /**
     * Registers all available real factory implementations for testing.
     * Attempts to register native and outbox factories if they are available on the classpath.
     * Uses TestContainers with real PostgreSQL instances - no mocking.
     *
     * @param registrar The factory registrar to register with
     */
    public static void registerAvailableFactories(QueueFactoryRegistrar registrar) {
        registerNativeFactory(registrar);
        registerOutboxFactory(registrar);
    }

    /**
     * Attempts to register the native factory if available.
     * 
     * @param registrar The factory registrar to register with
     */
    public static void registerNativeFactory(QueueFactoryRegistrar registrar) {
        try {
            Class<?> registrarClass = Class.forName("dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar");
            var registerMethod = registrarClass.getMethod("registerWith", QueueFactoryRegistrar.class);
            registerMethod.invoke(null, registrar);
            logger.info("Successfully registered native factory for testing");
        } catch (ClassNotFoundException e) {
            logger.info("Native factory not available on classpath - this is normal in some test environments");
        } catch (Exception e) {
            logger.warn("Failed to register native factory for testing: {}", e.getMessage());
        }
    }
    
    /**
     * Attempts to register the outbox factory if available.
     * 
     * @param registrar The factory registrar to register with
     */
    public static void registerOutboxFactory(QueueFactoryRegistrar registrar) {
        try {
            Class<?> registrarClass = Class.forName("dev.mars.peegeeq.outbox.OutboxFactoryRegistrar");
            var registerMethod = registrarClass.getMethod("registerWith", QueueFactoryRegistrar.class);
            registerMethod.invoke(null, registrar);
            logger.info("Successfully registered outbox factory for testing");
        } catch (ClassNotFoundException e) {
            logger.info("Outbox factory not available on classpath - this is normal in some test environments");
        } catch (Exception e) {
            logger.warn("Failed to register outbox factory for testing: {}", e.getMessage());
        }
    }
    


    /**
     * Unregisters all factory implementations.
     *
     * @param registrar The factory registrar to unregister from
     */
    public static void unregisterAllFactories(QueueFactoryRegistrar registrar) {
        registrar.unregisterFactory("native");
        registrar.unregisterFactory("outbox");
        logger.info("Unregistered all real factory implementations");
    }
}
