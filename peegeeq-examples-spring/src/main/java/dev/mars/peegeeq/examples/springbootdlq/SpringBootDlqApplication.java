package dev.mars.peegeeq.examples.springbootdlq;

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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Dead Letter Queue (DLQ) Example Application.
 * 
 * Demonstrates the CORRECT way to implement Dead Letter Queue pattern with PeeGeeQ:
 * - Automatic retry with configurable max retries
 * - Dead letter queue for permanently failed messages
 * - DLQ monitoring and alerting
 * - Manual DLQ message reprocessing
 * - REST endpoints for DLQ management
 * - Metrics for DLQ depth and processing
 * 
 * Key Principles:
 * 1. Use PeeGeeQ's built-in retry mechanism
 * 2. Configure max retries via properties
 * 3. Failed messages automatically move to DLQ after max retries
 * 4. Monitor DLQ depth for alerting
 * 5. Provide admin endpoints for DLQ inspection and reprocessing
 * 
 * Architecture:
 * - MessageConsumer processes messages from main queue
 * - Failed messages are retried up to max retries
 * - After max retries, messages move to DLQ
 * - DLQ service monitors and manages failed messages
 * - Admin endpoints allow manual reprocessing
 * 
 * @see dev.mars.peegeeq.examples.springbootdlq.service.PaymentProcessorService
 * @see dev.mars.peegeeq.examples.springbootdlq.service.DlqManagementService
 * @see dev.mars.peegeeq.examples.springbootdlq.controller.DlqAdminController
 */
@SpringBootApplication
public class SpringBootDlqApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootDlqApplication.class, args);
    }
}

