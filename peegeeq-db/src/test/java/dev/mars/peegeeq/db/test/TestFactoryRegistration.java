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
 * The peegeeq-db module cannot depend on implementation modules such as
 * peegeeq-native or peegeeq-outbox without creating a Maven cycle, so tests in this
 * module do not auto-register those factories. Tests that need the concrete
 * implementations must run from the owning module instead.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public class TestFactoryRegistration {

    private static final Logger logger = LoggerFactory.getLogger(TestFactoryRegistration.class);

    /**
     * Registers all available real factory implementations for testing.
     *
     * In peegeeq-db tests this is currently a no-op because the concrete queue
     * implementations live in downstream modules.
     *
     * @param registrar The factory registrar to register with
     */
    public static void registerAvailableFactories(QueueFactoryRegistrar registrar) {
        registerNativeFactory(registrar);
        registerOutboxFactory(registrar);
    }

    /**
     * Native factory registration is not available from peegeeq-db tests.
     * 
     * @param registrar The factory registrar to register with
     */
    public static void registerNativeFactory(QueueFactoryRegistrar registrar) {
        logger.info("Native factory registration is skipped in peegeeq-db tests to avoid module cycles");
    }
    
    /**
     * Outbox factory registration is not available from peegeeq-db tests.
     * 
     * @param registrar The factory registrar to register with
     */
    public static void registerOutboxFactory(QueueFactoryRegistrar registrar) {
        logger.info("Outbox factory registration is skipped in peegeeq-db tests to avoid module cycles");
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
