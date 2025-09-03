# PeeGeeQ Examples

This directory contains example files, templates, and configurations for PeeGeeQ.

## Directory Structure

### 📁 `/messages/`
Sample message request files for testing the REST API endpoints:
- `payment-message-request.json` - Example payment message
- `order-message-request.json` - Example order message  
- `order-message-2.json` - Alternative order message format
- `notification-message-request.json` - Example notification message

### 📁 `/templates/`
Template files showing the expected message structure:
- `sample-payment-message.json` - Payment message template
- `sample-order-message.json` - Order message template
- `sample-notification-message.json` - Notification message template

### 📁 `/config/`
Configuration files for demos and setup:
- `demo-setup.json` - Demo environment configuration
- `queue-config.json` - Queue configuration settings

## Usage

### Testing REST API
Use the files in `/messages/` to test REST API endpoints:

```bash
# Test sending a payment message
curl -X POST http://localhost:8080/api/queues/payments/messages \
  -H "Content-Type: application/json" \
  -d @examples/messages/payment-message-request.json

# Test sending an order message  
curl -X POST http://localhost:8080/api/queues/orders/messages \
  -H "Content-Type: application/json" \
  -d @examples/messages/order-message-request.json
```

### Using Templates
The files in `/templates/` serve as documentation for the expected message format. Copy and modify these templates for your own messages.

### Demo Setup
Use the configuration files in `/config/` for setting up demo environments:

```bash
# Use demo-setup.json for automated demo setup
java -jar peegeeq-rest.jar --config=examples/config/demo-setup.json
```

## Message Format

All messages follow this general structure:

```json
{
  "payload": {
    // Your business data here
  },
  "headers": {
    "source": "service-name",
    "version": "1.0",
    // Additional metadata
  },
  "priority": 5,
  "messageType": "EventType"
}
```

## Note

These files were moved from the root directory to improve project organization. If you have scripts or tools that reference the old locations, please update them to use the new paths under `examples/`.
