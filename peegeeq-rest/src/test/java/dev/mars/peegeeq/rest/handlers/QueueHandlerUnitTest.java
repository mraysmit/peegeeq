package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueueHandler message sending functionality.
 * NO MOCKITO - Uses manual stub objects following project testing standards.
 *
 * TODO: This test class is currently broken and needs to be rewritten.
 * The hand-rolled stub implementations (TestRoutingContext, TestHttpServerResponse,
 * TestRequestBody, etc.) are a form of mocking that violates project testing standards.
 * These stubs also don't match Vert.x 5.x interface signatures.
 *
 * Options to fix:
 * 1. Convert to integration tests using real Vert.x test infrastructure (VertxTestContext)
 * 2. Use Vert.x's built-in test utilities instead of hand-rolled stubs
 * 3. Test at a higher level through HTTP requests to a real server
 */
@Tag(TestCategories.CORE)
@DisplayName("Queue Handler Unit Tests")
class QueueHandlerUnitTest {

    private TestDatabaseSetupService setupService;
    private ObjectMapper objectMapper;
    private QueueHandler queueHandler;

    @BeforeEach
    void setUp() {
        setupService = new TestDatabaseSetupService();
        objectMapper = new ObjectMapper();
        queueHandler = new QueueHandler(setupService, objectMapper);
    }

    @Test
    @DisplayName("Should send message successfully")
    void testSendMessage_Success() throws Exception {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        String payload = "{\"payload\":\"test-data\"}";
        
        TestMessageProducer<Object> producer = new TestMessageProducer<>();
        TestQueueFactory factory = new TestQueueFactory(producer);
        TestDatabaseSetupResult setupResult = new TestDatabaseSetupResult(DatabaseSetupStatus.ACTIVE);
        setupResult.addQueueFactory(queueName, factory);
        
        setupService.addSetupResult(setupId, setupResult);
        
        TestRoutingContext context = new TestRoutingContext();
        context.addPathParam("setupId", setupId);
        context.addPathParam("queueName", queueName);
        context.setBodyString(payload);

        // Act
        queueHandler.sendMessage(context);

        // Assert
        assertEquals(200, context.response().getStatusCode());
        assertTrue(context.response().getBody().contains("Message sent successfully"));
        assertTrue(producer.wasClosed());
        assertEquals(1, producer.getSentMessages().size());
    }

    @Test
    @DisplayName("Should handle setup not found")
    void testSendMessage_SetupNotFound() {
        // Arrange
        String setupId = "missing-setup";
        String queueName = "test-queue";
        String payload = "{\"payload\":\"test-data\"}";
        
        // Don't add setupId to service - it will throw exception
        setupService.setShouldThrowNotFound(true);
        
        TestRoutingContext context = new TestRoutingContext();
        context.addPathParam("setupId", setupId);
        context.addPathParam("queueName", queueName);
        context.setBodyString(payload);

        // Act
        queueHandler.sendMessage(context);

        // Assert
        assertEquals(404, context.response().getStatusCode());
        assertTrue(context.response().getBody().contains("Setup not found"));
    }

    @Test
    @DisplayName("Should handle queue not found")
    void testSendMessage_QueueNotFound() {
        // Arrange
        String setupId = "test-setup";
        String queueName = "missing-queue";
        String payload = "{\"payload\":\"test-data\"}";
        
        TestDatabaseSetupResult setupResult = new TestDatabaseSetupResult(DatabaseSetupStatus.ACTIVE);
        // Don't add the queue factory
        setupService.addSetupResult(setupId, setupResult);
        
        TestRoutingContext context = new TestRoutingContext();
        context.addPathParam("setupId", setupId);
        context.addPathParam("queueName", queueName);
        context.setBodyString(payload);

        // Act
        queueHandler.sendMessage(context);

        // Assert
        assertEquals(404, context.response().getStatusCode());
    }

    @Test
    @DisplayName("Should validate message request")
    void testSendMessage_InvalidRequest() {
        // Arrange
        TestRoutingContext context = new TestRoutingContext();
        context.setBodyString("{}"); // Missing payload

        // Act
        queueHandler.sendMessage(context);

        // Assert
        assertEquals(400, context.response().getStatusCode());
        assertTrue(context.response().getBody().contains("Invalid request"));
    }
    
    @Test
    @DisplayName("Should get queue stats")
    void testGetQueueStats_Success() {
        // Arrange
        String setupId = "test-setup";
        String queueName = "test-queue";
        
        setupService.addSetupStatus(setupId, DatabaseSetupStatus.ACTIVE);
        
        TestRoutingContext context = new TestRoutingContext();
        context.addPathParam("setupId", setupId);
        context.addPathParam("queueName", queueName);

        // Act
        queueHandler.getQueueStats(context);

        // Assert
        assertEquals(200, context.response().getStatusCode());
        assertTrue(context.response().getBody().contains(queueName));
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

    // ==================== Test Stub Classes - NO MOCKITO ====================
    
    /**
     * Test stub for DatabaseSetupService - NO MOCKING
     */
    static class TestDatabaseSetupService implements DatabaseSetupService {
        private final Map<String, DatabaseSetupResult> setupResults = new HashMap<>();
        private final Map<String, DatabaseSetupStatus> setupStatuses = new HashMap<>();
        private boolean shouldThrowNotFound = false;
        
        void addSetupResult(String id, DatabaseSetupResult result) {
            setupResults.put(id, result);
        }
        
        void addSetupStatus(String id, DatabaseSetupStatus status) {
            setupStatuses.put(id, status);
        }
        
        void setShouldThrowNotFound(boolean value) {
            this.shouldThrowNotFound = value;
        }
        
        @Override
        public CompletableFuture<DatabaseSetupResult> getSetupResult(String setupId) {
            if (shouldThrowNotFound || !setupResults.containsKey(setupId)) {
                CompletableFuture<DatabaseSetupResult> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("Setup not found"));
                return future;
            }
            return CompletableFuture.completedFuture(setupResults.get(setupId));
        }
        
        @Override
        public CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId) {
            if (!setupStatuses.containsKey(setupId)) {
                CompletableFuture<DatabaseSetupStatus> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("Status not found"));
                return future;
            }
            return CompletableFuture.completedFuture(setupStatuses.get(setupId));
        }
        
        @Override
        public Future<DatabaseSetupResult> setupDatabase(String setupId) {
            return null; // Not used in these tests
        }
        
        @Override
        public Future<Void> teardownDatabase(String setupId) {
            return null; // Not used in these tests
        }
    }
    
    /**
     * Test stub for DatabaseSetupResult - NO MOCKING
     */
    static class TestDatabaseSetupResult implements DatabaseSetupResult {
        private final DatabaseSetupStatus status;
        private final Map<String, QueueFactory> queueFactories = new HashMap<>();
        
        TestDatabaseSetupResult(DatabaseSetupStatus status) {
            this.status = status;
        }
        
        void addQueueFactory(String name, QueueFactory factory) {
            queueFactories.put(name, factory);
        }
        
        @Override
        public DatabaseSetupStatus getStatus() {
            return status;
        }
        
        @Override
        public Map<String, QueueFactory> getQueueFactories() {
            return queueFactories;
        }
        
        @Override
        public String getSetupId() {
            return "test-setup";
        }
    }
    
    /**
     * Test stub for QueueFactory - NO MOCKING
     */
    static class TestQueueFactory implements QueueFactory {
        private final MessageProducer<?> producer;
        
        TestQueueFactory(MessageProducer<?> producer) {
            this.producer = producer;
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public <T> MessageProducer<T> createProducer(String queueName, Class<T> messageType) {
            return (MessageProducer<T>) producer;
        }
        
        @Override
        public <T> dev.mars.peegeeq.api.messaging.MessageConsumer<T> createConsumer(String queueName, Class<T> messageType) {
            return null; // Not used in these tests
        }
        
        @Override
        public <T> dev.mars.peegeeq.api.messaging.ConsumerGroup<T> createConsumerGroup(String groupName, String queueName, Class<T> messageType) {
            return null; // Not used in these tests
        }
        
        @Override
        public String getImplementationType() {
            return "test";
        }
        
        @Override
        public void close() {
            // No-op for test
        }
    }
    
    /**
     * Test stub for MessageProducer - NO MOCKING
     */
    static class TestMessageProducer<T> implements MessageProducer<T> {
        private final List<T> sentMessages = new ArrayList<>();
        private boolean closed = false;
        private boolean shouldFail = false;
        
        List<T> getSentMessages() {
            return sentMessages;
        }
        
        boolean wasClosed() {
            return closed;
        }
        
        void setShouldFail(boolean value) {
            this.shouldFail = value;
        }
        
        @Override
        public CompletableFuture<Void> send(T message) {
            return send(message, null, null, null);
        }
        
        @Override
        public CompletableFuture<Void> send(T message, Map<String, String> headers, Integer priority, Long delaySeconds) {
            if (shouldFail) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("Send failed"));
                return future;
            }
            sentMessages.add(message);
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public void close() {
            closed = true;
        }
    }
    
    /**
     * Test stub for RoutingContext - NO MOCKING
     */
    static class TestRoutingContext implements RoutingContext {
        private final Map<String, String> pathParams = new HashMap<>();
        private final TestHttpServerResponse response = new TestHttpServerResponse();
        private final TestRequestBody requestBody = new TestRequestBody();
        
        void addPathParam(String name, String value) {
            pathParams.put(name, value);
        }
        
        void setBodyString(String body) {
            requestBody.setBody(body);
        }
        
        @Override
        public String pathParam(String name) {
            return pathParams.get(name);
        }
        
        @Override
        public TestHttpServerResponse response() {
            return response;
        }
        
        @Override
        public RequestBody body() {
            return requestBody;
        }
        
        // All other methods - return null or no-op (not used in tests)
        @Override public HttpServerRequest request() { return null; }
        @Override public io.vertx.core.Vertx vertx() { return null; }
        @Override public String normalizedPath() { return null; }
        @Override public io.vertx.core.http.Cookie getCookie(String name) { return null; }
        @Override public RoutingContext addCookie(io.vertx.core.http.Cookie cookie) { return this; }
        @Override public io.vertx.core.http.Cookie removeCookie(String name, boolean invalidate) { return null; }
        @Override public int cookieCount() { return 0; }
        @Override public Map<String, io.vertx.core.http.Cookie> cookieMap() { return null; }
        @Override public int statusCode() { return response.getStatusCode(); }
        @Override public String getAcceptableContentType() { return null; }
        @Override public io.vertx.ext.web.ParsedHeaderValues parsedHeaders() { return null; }
        @Override public int addHeadersEndHandler(io.vertx.core.Handler<Void> handler) { return 0; }
        @Override public boolean removeHeadersEndHandler(int handlerID) { return false; }
        @Override public int addBodyEndHandler(io.vertx.core.Handler<Void> handler) { return 0; }
        @Override public boolean removeBodyEndHandler(int handlerID) { return false; }
        @Override public int addEndHandler(io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { return 0; }
        @Override public boolean removeEndHandler(int handlerID) { return false; }
        @Override public boolean failed() { return false; }
        @Override public void fail(int statusCode) { }
        @Override public void fail(Throwable throwable) { }
        @Override public void fail(int statusCode, Throwable throwable) { }
        @Override public Throwable failure() { return null; }
        @Override public String mountPoint() { return null; }
        @Override public io.vertx.ext.web.Route currentRoute() { return null; }
        @Override public String getBodyAsString() { return requestBody.asString(); }
        @Override public String getBodyAsString(String encoding) { return requestBody.asString(); }
        @Override public io.vertx.core.json.JsonObject getBodyAsJson() { return null; }
        @Override public io.vertx.core.json.JsonArray getBodyAsJsonArray() { return null; }
        @Override public io.vertx.core.buffer.Buffer getBody() { return null; }
        @Override public Set<io.vertx.ext.web.FileUpload> fileUploads() { return null; }
        @Override public void cancelAndCleanupFileUploads() { }
        @Override public io.vertx.ext.web.Session session() { return null; }
        @Override public boolean isSessionAccessed() { return false; }
        @Override public io.vertx.ext.auth.User user() { return null; }
        @Override public io.vertx.core.Future<io.vertx.ext.auth.User> user(io.vertx.core.Future<io.vertx.ext.auth.User> user) { return null; }
        @Override public void clearUser() { }
        @Override public String[] queryParam(String name) { return new String[0]; }
        @Override public List<String> queryParams(String name) { return null; }
        @Override public MultiMap queryParams() { return null; }
        @Override public Map<String, String> pathParams() { return pathParams; }
        @Override public <T> T get(String key) { return null; }
        @Override public <T> T get(String key, T defaultValue) { return null; }
        @Override public <T> T remove(String key) { return null; }
        @Override public Map<String, Object> data() { return null; }
        @Override public RoutingContext put(String key, Object obj) { return this; }
        @Override public void next() { }
        @Override public void end() { }
        @Override public void end(io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { }
        @Override public Future<Void> end() { return null; }
        @Override public void redirect(String url) { }
        @Override public void redirect(String url, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { }
        @Override public Future<Void> redirect(String url) { return null; }
        @Override public void reroute(String path) { }
        @Override public void reroute(io.vertx.core.http.HttpMethod method, String path) { }
        @Override public List<io.vertx.ext.web.Locale> acceptableLocales() { return null; }
        @Override public Map<String, String> data(int index) { return null; }
        @Override public io.vertx.ext.web.Locale preferredLocale() { return null; }
        @Override public Map<String, io.vertx.ext.web.LanguageHeader> acceptableLanguages() { return null; }
        @Override public io.vertx.ext.web.LanguageHeader preferredLanguage() { return null; }
        @Override public io.vertx.core.json.JsonObject json() { return null; }
        @Override public io.vertx.core.Future<io.vertx.core.json.JsonObject> json() { return null; }
        @Override public io.vertx.core.json.JsonArray jsonArray() { return null; }
        @Override public io.vertx.core.Future<io.vertx.core.json.JsonArray> jsonArray() { return null; }
    }
    
    /**
     * Test stub for HttpServerResponse - NO MOCKING
     */
    static class TestHttpServerResponse implements HttpServerResponse {
        private int statusCode = 200;
        private final Map<String, String> headers = new HashMap<>();
        private String body = "";
        
        int getStatusCode() {
            return statusCode;
        }
        
        String getBody() {
            return body;
        }
        
        @Override
        public TestHttpServerResponse setStatusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }
        
        @Override
        public TestHttpServerResponse putHeader(String name, String value) {
            headers.put(name, value);
            return this;
        }
        
        @Override
        public Future<Void> end(String chunk) {
            this.body = chunk;
            return Future.succeededFuture();
        }
        
        @Override
        public void end(String chunk, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) {
            this.body = chunk;
            handler.handle(Future.succeededFuture());
        }
        
        @Override
        public Future<Void> end() {
            return Future.succeededFuture();
        }
        
        // All other methods - return null or no-op (not used in tests)
        @Override public int getStatusCode() { return statusCode; }
        @Override public String getStatusMessage() { return null; }
        @Override public TestHttpServerResponse setStatusMessage(String statusMessage) { return this; }
        @Override public TestHttpServerResponse setChunked(boolean chunked) { return this; }
        @Override public boolean isChunked() { return false; }
        @Override public MultiMap headers() { return null; }
        @Override public TestHttpServerResponse putHeader(CharSequence name, CharSequence value) { return this; }
        @Override public TestHttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) { return this; }
        @Override public TestHttpServerResponse putHeader(String name, Iterable<String> values) { return this; }
        @Override public MultiMap trailers() { return null; }
        @Override public TestHttpServerResponse putTrailer(String name, String value) { return this; }
        @Override public TestHttpServerResponse putTrailer(CharSequence name, CharSequence value) { return this; }
        @Override public TestHttpServerResponse putTrailer(String name, Iterable<String> values) { return this; }
        @Override public TestHttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> values) { return this; }
        @Override public TestHttpServerResponse closeHandler(io.vertx.core.Handler<Void> handler) { return this; }
        @Override public TestHttpServerResponse endHandler(io.vertx.core.Handler<Void> handler) { return this; }
        @Override public TestHttpServerResponse writeContinue() { return this; }
        @Override public Future<Void> write(io.vertx.core.buffer.Buffer chunk) { return null; }
        @Override public void write(io.vertx.core.buffer.Buffer chunk, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { }
        @Override public Future<Void> write(String chunk, String enc) { return null; }
        @Override public void write(String chunk, String enc, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { }
        @Override public Future<Void> write(String chunk) { return null; }
        @Override public void write(String chunk, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { }
        @Override public TestHttpServerResponse writeCustomFrame(int type, int flags, io.vertx.core.buffer.Buffer payload) { return this; }
        @Override public Future<Void> writeCustomFrame(io.vertx.core.http.HttpFrame frame) { return null; }
        @Override public boolean writeQueueFull() { return false; }
        @Override public TestHttpServerResponse setWriteQueueMaxSize(int maxSize) { return this; }
        @Override public TestHttpServerResponse drainHandler(io.vertx.core.Handler<Void> handler) { return this; }
        @Override public Future<Void> sendFile(String filename, long offset, long length) { return null; }
        @Override public TestHttpServerResponse sendFile(String filename, long offset, long length, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> resultHandler) { return this; }
        @Override public void close() { }
        @Override public boolean ended() { return false; }
        @Override public boolean closed() { return false; }
        @Override public boolean headWritten() { return false; }
        @Override public TestHttpServerResponse headersEndHandler(io.vertx.core.Handler<Void> handler) { return this; }
        @Override public TestHttpServerResponse bodyEndHandler(io.vertx.core.Handler<Void> handler) { return this; }
        @Override public long bytesWritten() { return 0; }
        @Override public int streamId() { return 0; }
        @Override public Future<Void> push(io.vertx.core.http.HttpMethod method, String host, String path, MultiMap headers) { return null; }
        @Override public TestHttpServerResponse push(io.vertx.core.http.HttpMethod method, String host, String path, io.vertx.core.Handler<io.vertx.core.AsyncResult<HttpServerResponse>> handler) { return this; }
        @Override public TestHttpServerResponse push(io.vertx.core.http.HttpMethod method, String path, MultiMap headers, io.vertx.core.Handler<io.vertx.core.AsyncResult<HttpServerResponse>> handler) { return this; }
        @Override public Future<Void> push(io.vertx.core.http.HttpMethod method, String path, MultiMap headers) { return null; }
        @Override public TestHttpServerResponse push(io.vertx.core.http.HttpMethod method, String path, io.vertx.core.Handler<io.vertx.core.AsyncResult<HttpServerResponse>> handler) { return this; }
        @Override public Future<Void> push(io.vertx.core.http.HttpMethod method, String path) { return null; }
        @Override public boolean reset(long code) { return false; }
        @Override public Future<Void> push(io.vertx.core.http.HttpMethod method, String host, String path) { return null; }
        @Override public TestHttpServerResponse push(io.vertx.core.http.HttpMethod method, String host, String path, MultiMap headers, io.vertx.core.Handler<io.vertx.core.AsyncResult<HttpServerResponse>> handler) { return this; }
        @Override public TestHttpServerResponse writeEarlyHints(MultiMap headers) { return this; }
        @Override public TestHttpServerResponse writeEarlyHints(MultiMap headers, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { return this; }
        @Override public Future<Void> writeEarlyHints(MultiMap headers) { return null; }
        @Override public void end(io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { handler.handle(Future.succeededFuture()); }
        @Override public Future<Void> end(io.vertx.core.buffer.Buffer chunk) { return null; }
        @Override public void end(io.vertx.core.buffer.Buffer chunk, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { }
        @Override public TestHttpServerResponse exceptionHandler(io.vertx.core.Handler<Throwable> handler) { return this; }
        @Override public Future<HttpServerResponse> send() { return null; }
        @Override public TestHttpServerResponse send(io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { return this; }
        @Override public TestHttpServerResponse send(String body, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { return this; }
        @Override public Future<HttpServerResponse> send(String body) { return null; }
        @Override public TestHttpServerResponse send(io.vertx.core.buffer.Buffer body, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { return this; }
        @Override public Future<HttpServerResponse> send(io.vertx.core.buffer.Buffer body) { return null; }
        @Override public TestHttpServerResponse send(io.vertx.core.streams.ReadStream<io.vertx.core.buffer.Buffer> body, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) { return this; }
        @Override public Future<HttpServerResponse> send(io.vertx.core.streams.ReadStream<io.vertx.core.buffer.Buffer> body) { return null; }
        @Override public TestHttpServerResponse addCookie(io.vertx.core.http.Cookie cookie) { return this; }
        @Override public io.vertx.core.http.Cookie removeCookie(String name, boolean invalidate) { return null; }
        @Override public io.vertx.core.http.Cookie removeCookie(String name, String domain, String path, boolean invalidate) { return null; }
        @Override public Set<io.vertx.core.http.Cookie> removeCookies(String name, boolean invalidate) { return null; }
        @Override public io.vertx.core.http.Cookie removeCookie(String name) { return null; }
    }
    
    /**
     * Test stub for RequestBody - NO MOCKING
     */
    static class TestRequestBody implements RequestBody {
        private String body = "";
        
        void setBody(String body) {
            this.body = body;
        }
        
        @Override
        public String asString() {
            return body;
        }
        
        @Override
        public String asString(String encoding) {
            return body;
        }
        
        @Override
        public io.vertx.core.json.JsonObject asJsonObject() {
            return new io.vertx.core.json.JsonObject(body);
        }
        
        @Override
        public io.vertx.core.json.JsonArray asJsonArray() {
            return new io.vertx.core.json.JsonArray(body);
        }
        
        @Override
        public io.vertx.core.buffer.Buffer buffer() {
            return io.vertx.core.buffer.Buffer.buffer(body);
        }
        
        @Override
        public boolean available() {
            return body != null && !body.isEmpty();
        }
        
        @Override
        public long length() {
            return body != null ? body.length() : 0;
        }
    }
}
