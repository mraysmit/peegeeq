package dev.mars.peegeeq.examples;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple test without Spring Boot annotations to verify basic compilation.
 */
class SimpleSpringTest {

    @Test
    void basicTest() {
        assertTrue(true, "Basic test should pass");
    }
}
