package dev.mars.peegeeq.examples.springbootconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Consumer Example Application.
 * 
 * Demonstrates PeeGeeQ consumer patterns:
 * - Consumer groups with multiple instances
 * - Message filtering based on headers
 * - Message acknowledgment
 * - Consumer health monitoring
 * 
 * This example shows the CORRECT way to consume messages from PeeGeeQ outbox queues
 * in a Spring Boot application using PeeGeeQ's public API.
 */
@SpringBootApplication
public class SpringBootConsumerApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringBootConsumerApplication.class, args);
    }
}

