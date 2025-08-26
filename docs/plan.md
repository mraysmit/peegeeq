You're absolutely right! I apologize for the confusion. You asked me to help implement the features that control the actual runtime behavior, not just test the configuration loading. Let me create a proper implementation plan.

Perfect! Now I have a clear picture of the current implementation. Let me create a comprehensive implementation plan.

## 🎯 **Implementation Plan: System Properties Runtime Control**

### **Current State Analysis**

✅ **Already Implemented (Configuration Loading)**:
- `peegeeq.queue.max-retries` - Loaded in config but hardcoded in runtime (line 333: `int maxRetries = 3; // TODO: Make configurable`)
- `peegeeq.queue.batch-size` - Loaded in config but not used in runtime processing
- `peegeeq.queue.polling-interval` - Loaded in config but not used in polling logic
- `peegeeq.consumer.threads` - Referenced in config files but no thread management implementation

❌ **Missing Runtime Implementation**:
- Properties are loaded into configuration objects but not passed to runtime components
- Hardcoded values are used instead of configuration values
- No thread pool management for consumer threads
- No batch processing implementation
- No configurable polling intervals

---

## 📋 **Implementation Plan**

### **Phase 1: Configuration Injection Infrastructure**
*Estimated Time: 2-3 hours*

**Goal**: Pass configuration objects to runtime components

**Tasks**:
1. **Modify Queue Factory Creation**
    - Pass `PeeGeeQConfiguration` to queue factories
    - Update `PgNativeQueueConsumer` constructor to accept configuration
    - Update `OutboxConsumer` constructor to accept configuration

2. **Update Consumer Constructors**
    - Add configuration parameter to all consumer classes
    - Store configuration as instance variable
    - Update factory methods to pass configuration

**Files to Modify**:
- `PgQueueFactoryProvider.java` - Pass config to factories
- `PgNativeQueueConsumer.java` - Accept and store config
- `OutboxConsumer.java` - Accept and store config
- `PgNativeConsumerGroup.java` - Pass config to underlying consumer

---

### **Phase 2: Max Retries Implementation**
*Estimated Time: 1 hour*

**Goal**: Replace hardcoded retry limit with configuration value

**Current Issue**:
```java
int maxRetries = 3; // TODO: Make configurable (line 333)
```

**Implementation**:
1. **Replace Hardcoded Value**
    - Use `configuration.getQueueConfig().getMaxRetries()` instead of hardcoded `3`
    - Update retry logic in `handleMessageFailure()` method

**Files to Modify**:
- `PgNativeQueueConsumer.java` - Line 333, use config value

---

### **Phase 3: Polling Interval Implementation**
*Estimated Time: 2 hours*

**Goal**: Implement configurable polling intervals for message processing

**Current Issue**: No polling interval control in message processing loops

**Implementation**:
1. **Add Polling Timer**
    - Create scheduled executor with configurable interval
    - Replace immediate processing with interval-based polling
    - Use `configuration.getQueueConfig().getPollingInterval()`

2. **Update Processing Loop**
    - Modify `processAvailableMessages()` to respect polling interval
    - Add configurable delay between polling attempts

**Files to Modify**:
- `PgNativeQueueConsumer.java` - Add polling timer
- `OutboxConsumer.java` - Add polling timer

---

### **Phase 4: Consumer Threads Implementation**
*Estimated Time: 3-4 hours*

**Goal**: Implement configurable thread pool for concurrent message processing

**Current Issue**: No thread pool management, single-threaded processing

**Implementation**:
1. **Add Thread Pool Management**
    - Create `ExecutorService` with configurable thread count
    - Use `peegeeq.consumer.threads` property (add to configuration)
    - Implement proper thread pool lifecycle management

2. **Concurrent Message Processing**
    - Process messages concurrently using thread pool
    - Maintain thread safety for message locking
    - Add proper shutdown handling

**Files to Modify**:
- `PeeGeeQConfiguration.java` - Add consumer threads property
- `PgNativeQueueConsumer.java` - Add thread pool
- `OutboxConsumer.java` - Add thread pool

---

### **Phase 5: Batch Size Implementation**
*Estimated Time: 2-3 hours*

**Goal**: Implement configurable batch processing for messages

**Current Issue**: Single message processing, no batching

**Implementation**:
1. **Batch Query Implementation**
    - Modify SQL queries to fetch multiple messages (LIMIT = batch size)
    - Use `configuration.getQueueConfig().getBatchSize()`
    - Process messages in batches

2. **Batch Processing Logic**
    - Collect messages into batches
    - Process batches concurrently
    - Handle partial batch failures

**Files to Modify**:
- `PgNativeQueueConsumer.java` - Modify message fetching and processing
- `OutboxConsumer.java` - Add batch processing

---

### **Phase 6: Integration and Testing**
*Estimated Time: 2 hours*

**Goal**: Ensure all components work together and validate with tests

**Tasks**:
1. **Integration Testing**
    - Update existing tests to use new configuration-driven behavior
    - Verify all properties control actual runtime behavior
    - Test edge cases and error conditions

2. **Performance Validation**
    - Verify thread pool performance
    - Validate batch processing efficiency
    - Test polling interval accuracy

---

## 🎯 **Implementation Priority**

**High Priority** (Core functionality):
1. ✅ **Max Retries** - Simple, high impact
2. ✅ **Polling Interval** - Performance critical
3. ✅ **Configuration Injection** - Foundation for others

**Medium Priority** (Performance):
4. ✅ **Consumer Threads** - Concurrency improvement
5. ✅ **Batch Size** - Throughput optimization

---

## 📁 **Expected File Changes**

**Core Files**:
- `PeeGeeQConfiguration.java` - Add consumer threads property
- `PgNativeQueueConsumer.java` - Major changes for all properties
- `OutboxConsumer.java` - Similar changes to native consumer
- `PgQueueFactoryProvider.java` - Pass configuration to consumers

**Test Files**:
- Update existing comprehensive tests to validate runtime behavior
- All tests should pass once implementation is complete

---

## ⚠️ **Risks and Considerations**

1. **Thread Safety**: Ensure concurrent processing doesn't break message locking
2. **Performance Impact**: Thread pools and batching may affect latency
3. **Backward Compatibility**: Ensure existing behavior is preserved when properties aren't set
4. **Resource Management**: Proper cleanup of thread pools and connections

---

**Would you like me to proceed with this implementation plan? I can start with Phase 1 (Configuration Injection) and work through each phase systematically.**
