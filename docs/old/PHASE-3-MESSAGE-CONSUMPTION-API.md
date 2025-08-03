# Phase 3: Message Consumption & Real-time Features

#### © Mark Andrew Ray-Smith Cityline Ltd 2025

## Overview

Phase 3 completes the PeeGeeQ REST API by adding comprehensive message consumption capabilities, transforming it from a send-only API into a full bidirectional messaging platform.

## 🎯 Phase 3 Features Implemented

### 📥 1. Message Consumption REST API

#### **Single Message Polling**
```http
GET /api/v1/queues/{setupId}/{queueName}/messages/next
```

**Query Parameters:**
- `timeout` - Maximum wait time in milliseconds (default: 30000)
- `maxWait` - Polling interval in milliseconds (default: 5000)
- `consumerGroup` - Optional consumer group name (future feature)

**Response (200 OK):**
```json
{
  "message": "Message retrieved successfully",
  "queueName": "orders",
  "setupId": "my-setup",
  "messageId": "msg-uuid-123",
  "payload": {"orderId": "12345", "amount": 99.99},
  "headers": {"source": "order-service", "priority": "5"},
  "timestamp": 1752929815000,
  "priority": 5,
  "messageType": "OrderCreated"
}
```

**Response (204 No Content):**
```json
{
  "message": "No messages available",
  "queueName": "orders",
  "setupId": "my-setup",
  "timestamp": 1752929815000
}
```

#### **Batch Message Polling**
```http
GET /api/v1/queues/{setupId}/{queueName}/messages
```

**Query Parameters:**
- `limit` - Number of messages to retrieve (1-100, default: 10)
- `timeout` - Maximum wait time in milliseconds (default: 5000)
- `consumerGroup` - Optional consumer group name (future feature)

**Response (200 OK):**
```json
{
  "message": "Messages retrieved successfully",
  "queueName": "orders",
  "setupId": "my-setup",
  "messageCount": 2,
  "timestamp": 1752929815000,
  "messages": [
    {
      "messageId": "msg-1",
      "payload": "Message 1",
      "headers": {"priority": "5"},
      "timestamp": 1752929815000,
      "priority": 5,
      "messageType": "Text"
    },
    {
      "messageId": "msg-2",
      "payload": "Message 2",
      "headers": {"priority": "3"},
      "timestamp": 1752929816000,
      "priority": 3,
      "messageType": "Text"
    }
  ]
}
```

#### **Message Acknowledgment**
```http
DELETE /api/v1/queues/{setupId}/{queueName}/messages/{messageId}
```

**Response (200 OK):**
```json
{
  "message": "Message acknowledged successfully",
  "queueName": "orders",
  "setupId": "my-setup",
  "messageId": "msg-uuid-123",
  "timestamp": 1752929815000
}
```

**Response (404 Not Found):**
```json
{
  "error": "Message not found or already acknowledged",
  "queueName": "orders",
  "setupId": "my-setup",
  "messageId": "msg-uuid-123",
  "timestamp": 1752929815000
}
```

### 🔄 2. Message Consumption Workflow

#### **Standard Polling Workflow**
```bash
# 1. Poll for next message
curl -X GET "http://localhost:8080/api/v1/queues/my-setup/orders/messages/next?timeout=30000"

# 2. Process the message (application logic)

# 3. Acknowledge successful processing
curl -X DELETE "http://localhost:8080/api/v1/queues/my-setup/orders/messages/{messageId}"
```

#### **Batch Processing Workflow**
```bash
# 1. Get multiple messages
curl -X GET "http://localhost:8080/api/v1/queues/my-setup/orders/messages?limit=10&timeout=5000"

# 2. Process each message in the batch

# 3. Acknowledge each message individually
for messageId in "${messageIds[@]}"; do
  curl -X DELETE "http://localhost:8080/api/v1/queues/my-setup/orders/messages/$messageId"
done
```

### 📊 3. HTTP Status Codes

| Status Code | Description | Usage |
|-------------|-------------|-------|
| **200 OK** | Success with message(s) | Message(s) retrieved or acknowledged |
| **204 No Content** | No messages available | Polling returned no messages |
| **400 Bad Request** | Invalid parameters | Invalid limit, timeout, etc. |
| **404 Not Found** | Resource not found | Setup, queue, or message not found |
| **500 Internal Server Error** | Server error | Consumer creation failed, etc. |

### 🏗️ 4. Implementation Architecture

#### **QueueHandler Extensions**
- `getNextMessage()` - Single message polling
- `getMessages()` - Batch message polling  
- `acknowledgeMessage()` - Message acknowledgment
- `MessageResponse` class - Response structure

#### **Helper Methods**
- `pollForMessage()` - Single message polling logic
- `pollForMessages()` - Batch message polling logic
- `acknowledgeMessageWithConsumer()` - Acknowledgment logic

#### **Route Registration**
```java
// Phase 3: Message Consumption routes
router.get("/api/v1/queues/:setupId/:queueName/messages/next").handler(queueHandler::getNextMessage);
router.get("/api/v1/queues/:setupId/:queueName/messages").handler(queueHandler::getMessages);
router.delete("/api/v1/queues/:setupId/:queueName/messages/:messageId").handler(queueHandler::acknowledgeMessage);
```

## 🧪 Testing & Validation

### **Comprehensive Test Coverage**
- ✅ **Phase3ConsumptionTest** - Core consumption functionality
- ✅ **Phase3IntegrationTest** - End-to-end workflow validation
- ✅ **Backward compatibility** - All existing tests pass

### **Test Scenarios Covered**
- ✅ Message response structure validation
- ✅ Complex payload handling (objects, arrays, primitives)
- ✅ Header processing and validation
- ✅ Error handling (invalid parameters, not found, server errors)
- ✅ Workflow documentation and validation

## 🚀 Usage Examples

### **JavaScript/Node.js Consumer**
```javascript
async function consumeMessages() {
  while (true) {
    try {
      // Poll for next message
      const response = await fetch(
        'http://localhost:8080/api/v1/queues/my-setup/orders/messages/next?timeout=30000'
      );
      
      if (response.status === 204) {
        console.log('No messages available, continuing...');
        continue;
      }
      
      const messageData = await response.json();
      console.log('Received message:', messageData.messageId);
      
      // Process the message
      await processMessage(messageData.payload);
      
      // Acknowledge successful processing
      await fetch(
        `http://localhost:8080/api/v1/queues/my-setup/orders/messages/${messageData.messageId}`,
        { method: 'DELETE' }
      );
      
      console.log('Message acknowledged:', messageData.messageId);
      
    } catch (error) {
      console.error('Error consuming message:', error);
      await new Promise(resolve => setTimeout(resolve, 5000)); // Wait before retry
    }
  }
}
```

### **Python Consumer**
```python
import requests
import time
import json

def consume_messages():
    base_url = "http://localhost:8080/api/v1/queues/my-setup/orders"
    
    while True:
        try:
            # Poll for next message
            response = requests.get(f"{base_url}/messages/next", params={"timeout": 30000})
            
            if response.status_code == 204:
                print("No messages available, continuing...")
                continue
                
            message_data = response.json()
            print(f"Received message: {message_data['messageId']}")
            
            # Process the message
            process_message(message_data['payload'])
            
            # Acknowledge successful processing
            ack_response = requests.delete(f"{base_url}/messages/{message_data['messageId']}")
            
            if ack_response.status_code == 200:
                print(f"Message acknowledged: {message_data['messageId']}")
            else:
                print(f"Failed to acknowledge message: {ack_response.status_code}")
                
        except Exception as error:
            print(f"Error consuming message: {error}")
            time.sleep(5)  # Wait before retry

def process_message(payload):
    # Your message processing logic here
    print(f"Processing: {payload}")
```

### **Batch Processing Example**
```bash
#!/bin/bash

# Batch message consumption script
SETUP_ID="my-setup"
QUEUE_NAME="orders"
BASE_URL="http://localhost:8080/api/v1/queues/$SETUP_ID/$QUEUE_NAME"

while true; do
  echo "Polling for batch messages..."
  
  # Get up to 10 messages
  RESPONSE=$(curl -s -w "%{http_code}" -X GET "$BASE_URL/messages?limit=10&timeout=5000")
  HTTP_CODE="${RESPONSE: -3}"
  BODY="${RESPONSE%???}"
  
  if [ "$HTTP_CODE" = "200" ]; then
    echo "Received batch messages"
    
    # Extract message IDs and process each message
    MESSAGE_IDS=$(echo "$BODY" | jq -r '.messages[].messageId')
    
    for MESSAGE_ID in $MESSAGE_IDS; do
      echo "Processing message: $MESSAGE_ID"
      
      # Your processing logic here
      sleep 1
      
      # Acknowledge the message
      ACK_RESPONSE=$(curl -s -w "%{http_code}" -X DELETE "$BASE_URL/messages/$MESSAGE_ID")
      ACK_CODE="${ACK_RESPONSE: -3}"
      
      if [ "$ACK_CODE" = "200" ]; then
        echo "Acknowledged message: $MESSAGE_ID"
      else
        echo "Failed to acknowledge message: $MESSAGE_ID (HTTP $ACK_CODE)"
      fi
    done
  else
    echo "No messages available or error (HTTP $HTTP_CODE)"
    sleep 5
  fi
done
```

## 🔮 Future Enhancements (Phase 4+)

### **Planned Features**
- **WebSocket Support** - Real-time message streaming
- **Server-Sent Events** - HTTP streaming for real-time updates
- **Consumer Groups** - Advanced consumer group management
- **Message Filtering** - Server-side message filtering
- **Dead Letter Queue Integration** - Failed message handling
- **Metrics & Monitoring** - Enhanced consumption metrics

### **Consumer Group API (Future)**
```http
POST /api/v1/queues/{setupId}/{queueName}/consumer-groups
GET /api/v1/queues/{setupId}/{queueName}/consumer-groups
PUT /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupId}/members
```

---

## 🏆 Phase 3 Success Summary

**Phase 3 successfully transforms PeeGeeQ's REST API into a complete bidirectional messaging platform:**

✅ **Full Message Lifecycle** - Send, consume, and acknowledge messages  
✅ **Flexible Consumption** - Single message and batch polling  
✅ **Production Ready** - Comprehensive error handling and validation  
✅ **Developer Friendly** - Clear API design and extensive documentation  
✅ **Backward Compatible** - All existing functionality preserved  
✅ **Thoroughly Tested** - Complete test coverage with integration scenarios  

**PeeGeeQ now provides enterprise-grade REST API messaging capabilities that rival commercial message queue solutions!** 🎉
