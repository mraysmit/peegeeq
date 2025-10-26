package dev.mars.peegeeq.api;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageFilter;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.time.Instant;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to isolate the null pointer exception in MessageFilter.
 */
@Tag(TestCategories.CORE)
public class MessageFilterDebugTest {

    private static class TestMessage implements Message<String> {
        private final String id;
        private final String payload;
        private final Instant timestamp;
        private final Map<String, String> headers;

        public TestMessage(String id, String payload, Map<String, String> headers) {
            this.id = id;
            this.payload = payload;
            this.timestamp = Instant.now();
            this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        }

        @Override
        public String getId() { return id; }

        @Override
        public String getPayload() { return payload; }

        @Override
        public Instant getCreatedAt() { return timestamp; }

        @Override
        public Map<String, String> getHeaders() { return headers; }
    }

    @Test
    public void testByHeaderInWithNullValues() {
        // Test with null header value
        TestMessage message1 = new TestMessage("1", "test", Map.of("region", "US", "type", "PREMIUM"));
        TestMessage message2 = new TestMessage("2", "test", Map.of("region", "US")); // missing type header
        TestMessage message3 = new TestMessage("3", "test", new HashMap<>()); // no headers

        Predicate<Message<String>> regionFilter = MessageFilter.byHeaderIn("region", Set.of("US"));
        Predicate<Message<String>> typeFilter = MessageFilter.byHeaderIn("type", Set.of("PREMIUM"));
        
        // Test individual filters
        assertTrue(regionFilter.test(message1));
        assertTrue(regionFilter.test(message2));
        assertFalse(regionFilter.test(message3));
        
        assertTrue(typeFilter.test(message1));
        assertFalse(typeFilter.test(message2)); // should not throw NPE
        assertFalse(typeFilter.test(message3)); // should not throw NPE
        
        // Test combined filter
        Predicate<Message<String>> combinedFilter = MessageFilter.and(regionFilter, typeFilter);
        
        assertTrue(combinedFilter.test(message1));
        assertFalse(combinedFilter.test(message2)); // should not throw NPE
        assertFalse(combinedFilter.test(message3)); // should not throw NPE
    }

    @Test
    public void testByHeaderInWithNullSet() {
        TestMessage message = new TestMessage("1", "test", Map.of("region", "US"));
        
        // Test with null set - should not throw NPE
        Predicate<Message<String>> filter = MessageFilter.byHeaderIn("region", null);
        assertFalse(filter.test(message));
    }

    @Test
    public void testByHeaderInWithNullKey() {
        TestMessage message = new TestMessage("1", "test", Map.of("region", "US"));
        
        // Test with null key - should not throw NPE
        Predicate<Message<String>> filter = MessageFilter.byHeaderIn(null, Set.of("US"));
        assertFalse(filter.test(message));
    }

    @Test
    public void testByHeaderWithNullValues() {
        TestMessage message1 = new TestMessage("1", "test", Map.of("region", "US"));
        TestMessage message2 = new TestMessage("2", "test", new HashMap<>());
        
        // Test with null expected value
        Predicate<Message<String>> filter1 = MessageFilter.byHeader("region", null);
        assertFalse(filter1.test(message1)); // "US" != null
        assertTrue(filter1.test(message2)); // null == null
        
        // Test with null key
        Predicate<Message<String>> filter2 = MessageFilter.byHeader(null, "US");
        assertFalse(filter2.test(message1));
        assertFalse(filter2.test(message2));
    }

    @Test
    public void testComplexScenario() {
        // Simulate the exact scenario from the failing test
        TestMessage message = new TestMessage("test-1", "order", Map.of(
            "region", "US",
            "priority", "HIGH", 
            "type", "PREMIUM",
            "source", "test-service"
        ));

        Predicate<Message<String>> regionFilter = MessageFilter.byRegion(Set.of("US"));
        Predicate<Message<String>> typeFilter = MessageFilter.byType(Set.of("PREMIUM"));
        Predicate<Message<String>> combinedFilter = MessageFilter.and(regionFilter, typeFilter);

        assertTrue(regionFilter.test(message));
        assertTrue(typeFilter.test(message));
        assertTrue(combinedFilter.test(message));
    }
}
