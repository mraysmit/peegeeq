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
 * Utility class for registering queue factories in tests.
 * 
 * This class uses reflection only for testing purposes to register
 * factories that may or may not be available on the classpath.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public class TestFactoryRegistration {
    
    private static final Logger logger = LoggerFactory.getLogger(TestFactoryRegistration.class);
    
    /**
     * Registers all available factory implementations for testing.
     * This method registers the mock factory (always available) and attempts
     * to register native and outbox factories if they are available on the classpath.
     *
     * @param registrar The factory registrar to register with
     */
    public static void registerAvailableFactories(QueueFactoryRegistrar registrar) {
        registerMockFactory(registrar);
        registerNativeFactory(registrar);
        registerOutboxFactory(registrar);
    }

    /**
     * Registers the mock factory (always available for testing).
     *
     * @param registrar The factory registrar to register with
     */
    public static void registerMockFactory(QueueFactoryRegistrar registrar) {
        try {
            MockFactoryRegistrar.registerWith(registrar);
            logger.info("Successfully registered mock factory for testing");
        } catch (Exception e) {
            logger.error("Failed to register mock factory for testing: {}", e.getMessage(), e);
        }
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
        registrar.unregisterFactory("mock");
        registrar.unregisterFactory("native");
        registrar.unregisterFactory("outbox");
        logger.info("Unregistered all factories");
    }
}
