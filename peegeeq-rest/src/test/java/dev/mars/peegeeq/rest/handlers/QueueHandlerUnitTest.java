package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QueueHandler message sending functionality.
 */
@Tag(TestCategories.CORE)
@ExtendWith(MockitoExtension.class)
@DisplayName("Queue Handler Unit Tests")
class QueueHandlerUnitTest {

    @Mock
    private DatabaseSetupService setupService;
    
    @Mock
    private RoutingContext routingContext;
    
    @Mock
    private HttpServerResponse response;
    
    @Mock
    private RequestBody requestBody;
    
    @Mock
    private DatabaseSetupResult setupResult;
    
    @Mock
    private QueueFactory queueFactory;
    
    @Mock
    private MessageProducer<Object> messageProducer;

    private ObjectMapper objectMapper;
    private QueueHandler queueHandler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        queueHandler = new QueueHandler(setupService, objectMapper);
        
        // Common mock setup
        lenient().when(routingContext.response()).thenReturn(response);
        lenient().when(response.setStatusCode(anyInt())).thenReturn(response);
        lenient().when(response.putHeader(anyString(), anyString())).thenReturn(response);
    }

    @Test
    @DisplayName("Should send message successfully")
    void testSendMessage_Success() throws Exception {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        String payload = "{\"payload\":\"test-data\"}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        when(setupService.getSetupResult(setupId)).thenReturn(CompletableFuture.completedFuture(setupResult));
        when(setupResult.getStatus()).thenReturn(DatabaseSetupStatus.ACTIVE);
        Map<String, QueueFactory> factories = new HashMap<>();
        factories.put(queueName, queueFactory);
        when(setupResult.getQueueFactories()).thenReturn(factories);
        
        when(queueFactory.createProducer(eq(queueName), eq(Object.class))).thenReturn(messageProducer);
        when(messageProducer.send(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // Act
        queueHandler.sendMessage(routingContext);

        // Assert
        verify(response).setStatusCode(200);
        verify(response).end(anyString());
        verify(messageProducer).close();
    }

    @Test
    @DisplayName("Should handle setup not found")
    void testSendMessage_SetupNotFound() {
        // Arrange
        String setupId = "missing-setup";
        String queueName = "test-queue";
        String payload = "{\"payload\":\"test-data\"}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        CompletableFuture<DatabaseSetupResult> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Setup not found"));
        when(setupService.getSetupResult(setupId)).thenReturn(failedFuture);

        // Act
        queueHandler.sendMessage(routingContext);

        // Assert
        verify(response).setStatusCode(404);
        verify(response).end(contains("Setup not found"));
    }

    @Test
    @DisplayName("Should handle queue not found")
    void testSendMessage_QueueNotFound() {
        // Arrange
        String setupId = "test-setup";
        String queueName = "missing-queue";
        String payload = "{\"payload\":\"test-data\"}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        when(setupService.getSetupResult(setupId)).thenReturn(CompletableFuture.completedFuture(setupResult));
        when(setupResult.getStatus()).thenReturn(DatabaseSetupStatus.ACTIVE);
        when(setupResult.getQueueFactories()).thenReturn(Collections.emptyMap());

        // Act
        queueHandler.sendMessage(routingContext);

        // Assert
        verify(response).setStatusCode(404);
    }

    @Test
    @DisplayName("Should validate message request")
    void testSendMessage_InvalidRequest() {
        // Arrange
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn("{}"); // Missing payload

        // Act
        queueHandler.sendMessage(routingContext);

        // Assert
        verify(response).setStatusCode(400);
        verify(response).end(contains("Invalid request"));
    }
    
    @Test
    @DisplayName("Should get queue stats")
    void testGetQueueStats_Success() {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        
        when(setupService.getSetupStatus(setupId)).thenReturn(CompletableFuture.completedFuture(DatabaseSetupStatus.ACTIVE));

        // Act
        queueHandler.getQueueStats(routingContext);

        // Assert
        verify(response).setStatusCode(200);
        verify(response).end(contains(queueName));
    }

    @Test
    @DisplayName("MessageRequest serialization")
    void testMessageRequestSerialization() throws Exception {
        String jsonString = "{\"payload\":\"test message\",\"priority\":5,\"delaySeconds\":10}";
        QueueHandler.MessageRequest request = objectMapper.readValue(jsonString, QueueHandler.MessageRequest.class);
        assertEquals("test message", request.getPayload());
        assertEquals(Integer.valueOf(5), request.getPriority());
        assertEquals(Long.valueOf(10), request.getDelaySeconds());
    }

    @Test
    @DisplayName("MessageRequest validation")
    void testMessageRequestValidation() {
        QueueHandler.MessageRequest request = new QueueHandler.MessageRequest();
        assertThrows(IllegalArgumentException.class, request::validate);
        
        request.setPayload("test");
        request.setPriority(5);
        request.setDelaySeconds(10L);
        assertDoesNotThrow(request::validate);
    }
    
    @Test
    @DisplayName("MessageRequest validation - Invalid Priority")
    void testMessageRequestValidation_InvalidPriority() {
        QueueHandler.MessageRequest request = new QueueHandler.MessageRequest();
        request.setPayload("test");
        request.setPriority(15); // Invalid - should be 1-10
        
        try {
            request.validate();
            fail("Should have thrown exception for invalid priority");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Priority must be between 1 and 10"));
        }
    }

    @Test
    @DisplayName("MessageRequest validation - Negative Delay")
    void testMessageRequestValidation_NegativeDelay() {
        QueueHandler.MessageRequest request = new QueueHandler.MessageRequest();
        request.setPayload("test");
        request.setDelaySeconds(-5L); // Invalid - should be non-negative
        
        try {
            request.validate();
            fail("Should have thrown exception for negative delay");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Delay seconds cannot be negative"));
        }
    }

    @Test
    @DisplayName("MessageRequest Getters and Setters")
    void testMessageRequestGettersAndSetters() {
        // Test that all getters and setters work correctly
        QueueHandler.MessageRequest request = new QueueHandler.MessageRequest();

        request.setPayload("test payload");
        request.setPriority(7);
        request.setDelaySeconds(30L);
        request.setMessageType("TestMessage");

        assertEquals("test payload", request.getPayload());
        assertEquals(Integer.valueOf(7), request.getPriority());
        assertEquals(Long.valueOf(30), request.getDelaySeconds());
        assertEquals("TestMessage", request.getMessageType());
    }

    @Test
    @DisplayName("Should send batch messages successfully")
    void testSendMessages_Success() throws Exception {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        String payload = "{\"messages\":[{\"payload\":\"msg1\"},{\"payload\":\"msg2\"}]}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        when(setupService.getSetupResult(setupId)).thenReturn(CompletableFuture.completedFuture(setupResult));
        when(setupResult.getStatus()).thenReturn(DatabaseSetupStatus.ACTIVE);
        Map<String, QueueFactory> factories = new HashMap<>();
        factories.put(queueName, queueFactory);
        when(setupResult.getQueueFactories()).thenReturn(factories);
        
        when(queueFactory.createProducer(eq(queueName), eq(Object.class))).thenReturn(messageProducer);
        when(messageProducer.send(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // Act
        queueHandler.sendMessages(routingContext);

        // Assert
        verify(response).setStatusCode(200);
        verify(response).end(contains("Batch messages processed"));
        verify(messageProducer, times(2)).send(any(), any(), any(), any());
        verify(messageProducer).close();
    }

    @Test
    @DisplayName("Should handle invalid batch request")
    void testSendMessages_InvalidRequest() {
        // Arrange
        when(routingContext.pathParam("setupId")).thenReturn("test-setup");
        when(routingContext.pathParam("queueName")).thenReturn("test-queue");
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn("{}"); // Missing messages list

        // Act
        queueHandler.sendMessages(routingContext);

        // Assert
        verify(response).setStatusCode(400);
        verify(response).end(contains("Invalid batch request"));
    }

    @Test
    @DisplayName("Should handle batch failure with failOnError=true")
    void testSendMessages_FailOnError() {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        String payload = "{\"messages\":[{\"payload\":\"msg1\"}], \"failOnError\":true}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        when(setupService.getSetupResult(setupId)).thenReturn(CompletableFuture.completedFuture(setupResult));
        when(setupResult.getStatus()).thenReturn(DatabaseSetupStatus.ACTIVE);
        Map<String, QueueFactory> factories = new HashMap<>();
        factories.put(queueName, queueFactory);
        when(setupResult.getQueueFactories()).thenReturn(factories);
        
        when(queueFactory.createProducer(eq(queueName), eq(Object.class))).thenReturn(messageProducer);
        when(messageProducer.send(any(), any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Send failed")));

        // Act
        queueHandler.sendMessages(routingContext);

        // Assert
        verify(response).setStatusCode(500);
        verify(response).end(contains("Failed to send batch messages"));
    }

    @Test
    @DisplayName("Should handle batch failure with failOnError=false")
    void testSendMessages_ContinueOnError() {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        // Two messages, failOnError=false
        String payload = "{\"messages\":[{\"payload\":\"msg1\"}, {\"payload\":\"msg2\"}], \"failOnError\":false}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        when(setupService.getSetupResult(setupId)).thenReturn(CompletableFuture.completedFuture(setupResult));
        when(setupResult.getStatus()).thenReturn(DatabaseSetupStatus.ACTIVE);
        Map<String, QueueFactory> factories = new HashMap<>();
        factories.put(queueName, queueFactory);
        when(setupResult.getQueueFactories()).thenReturn(factories);
        
        when(queueFactory.createProducer(eq(queueName), eq(Object.class))).thenReturn(messageProducer);
        
        // First fails, second succeeds
        when(messageProducer.send(eq("msg1"), any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Send failed")));
        when(messageProducer.send(eq("msg2"), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // Act
        queueHandler.sendMessages(routingContext);

        // Assert
        verify(response).setStatusCode(207); // Multi-status
        verify(response).end(contains("successfulMessages\":1"));
        verify(response).end(contains("failedMessages\":1"));
    }

    @Test
    @DisplayName("Should handle getQueueStats failure")
    void testGetQueueStats_Failure() {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        
        CompletableFuture<DatabaseSetupStatus> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("DB Error"));
        when(setupService.getSetupStatus(setupId)).thenReturn(failedFuture);

        // Act
        queueHandler.getQueueStats(routingContext);

        // Assert
        verify(response).setStatusCode(404);
    }
    
    @Test
    @DisplayName("Should handle producer creation failure")
    void testSendMessage_ProducerCreationFailure() {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        String payload = "{\"payload\":\"test-data\"}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        when(setupService.getSetupResult(setupId)).thenReturn(CompletableFuture.completedFuture(setupResult));
        when(setupResult.getStatus()).thenReturn(DatabaseSetupStatus.ACTIVE);
        Map<String, QueueFactory> factories = new HashMap<>();
        factories.put(queueName, queueFactory);
        when(setupResult.getQueueFactories()).thenReturn(factories);
        
        when(queueFactory.createProducer(eq(queueName), eq(Object.class))).thenThrow(new RuntimeException("Producer error"));

        // Act
        queueHandler.sendMessage(routingContext);

        // Assert
        verify(response).setStatusCode(500);
        verify(response).end(contains("Failed to send message"));
    }

    @Test
    @DisplayName("Should send message with full options")
    void testSendMessage_WithFullOptions() throws Exception {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        String payload = "{\"payload\":\"test-data\",\"priority\":5,\"delaySeconds\":10,\"headers\":{\"custom\":\"value\",\"correlationId\":\"my-id\"}}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        when(setupService.getSetupResult(setupId)).thenReturn(CompletableFuture.completedFuture(setupResult));
        when(setupResult.getStatus()).thenReturn(DatabaseSetupStatus.ACTIVE);
        Map<String, QueueFactory> factories = new HashMap<>();
        factories.put(queueName, queueFactory);
        when(setupResult.getQueueFactories()).thenReturn(factories);
        
        when(queueFactory.createProducer(eq(queueName), eq(Object.class))).thenReturn(messageProducer);
        when(messageProducer.send(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        // Act
        queueHandler.sendMessage(routingContext);

        // Assert
        verify(response).setStatusCode(200);
        verify(messageProducer).send(any(), argThat(headers -> 
            headers.get("priority").equals("5") &&
            headers.get("delaySeconds").equals("10") &&
            headers.get("custom").equals("value") &&
            headers.get("correlationId").equals("my-id")
        ), any(), any());
    }

    @Test
    @DisplayName("Should handle send message failure")
    void testSendMessage_SendFailure() throws Exception {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        String payload = "{\"payload\":\"test-data\"}";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        when(routingContext.body()).thenReturn(requestBody);
        when(requestBody.asString()).thenReturn(payload);
        
        when(setupService.getSetupResult(setupId)).thenReturn(CompletableFuture.completedFuture(setupResult));
        when(setupResult.getStatus()).thenReturn(DatabaseSetupStatus.ACTIVE);
        Map<String, QueueFactory> factories = new HashMap<>();
        factories.put(queueName, queueFactory);
        when(setupResult.getQueueFactories()).thenReturn(factories);
        
        when(queueFactory.createProducer(eq(queueName), eq(Object.class))).thenReturn(messageProducer);
        
        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Send failed"));
        when(messageProducer.send(any(), any(), any(), any())).thenReturn(failedFuture);

        // Act
        queueHandler.sendMessage(routingContext);

        // Assert
        verify(response).setStatusCode(500);
        verify(response).end(contains("Failed to send message"));
    }

    @Test
    @DisplayName("Should handle serialization error in getQueueStats")
    void testGetQueueStats_SerializationError() throws Exception {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        
        // Create a handler with a mocked ObjectMapper just for this test
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        QueueHandler handlerWithMockMapper = new QueueHandler(setupService, mockMapper);
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        
        when(setupService.getSetupStatus(setupId)).thenReturn(CompletableFuture.completedFuture(DatabaseSetupStatus.ACTIVE));
        when(mockMapper.writeValueAsString(any())).thenThrow(new RuntimeException("Serialization failed"));

        // Act
        handlerWithMockMapper.getQueueStats(routingContext);

        // Assert
        verify(response).setStatusCode(500);
        verify(response).end(contains("Internal server error"));
    }

    @Test
    @DisplayName("Should handle unexpected error in getQueueStats")
    void testGetQueueStats_UnexpectedError() {
        // Arrange
        // Use an ID that doesn't trigger isTestScenario (doesn't start with "test-")
        String setupId = "prod-setup"; 
        String queueName = "prod-queue";
        
        when(routingContext.pathParam("setupId")).thenReturn(setupId);
        when(routingContext.pathParam("queueName")).thenReturn(queueName);
        
        CompletableFuture<DatabaseSetupStatus> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Unexpected DB Error"));
        when(setupService.getSetupStatus(setupId)).thenReturn(failedFuture);

        // Act
        queueHandler.getQueueStats(routingContext);

        // Assert
        verify(response).setStatusCode(404);
    }
}
