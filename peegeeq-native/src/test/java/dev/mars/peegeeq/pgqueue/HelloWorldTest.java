package dev.mars.peegeeq.pgqueue;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple Hello World test to verify JUnit is working
 */
public class HelloWorldTest {
    private static final Logger logger = LoggerFactory.getLogger(HelloWorldTest.class);

    @Test
    void testHelloWorld() {
        logger.info("🌍 Hello World test is running!");
        
        String message = "Hello, PeeGeeQ!";
        assertNotNull(message);
        assertEquals("Hello, PeeGeeQ!", message);
        assertTrue(message.contains("PeeGeeQ"));
        
        logger.info("✅ Hello World test completed successfully: {}", message);
    }
    
    @Test
    void testBasicMath() {
        logger.info("🧮 Testing basic math operations");
        
        int result = 2 + 2;
        assertEquals(4, result);
        
        double division = 10.0 / 2.0;
        assertEquals(5.0, division, 0.001);
        
        logger.info("✅ Math test completed: 2+2={}, 10/2={}", result, division);
    }
}
