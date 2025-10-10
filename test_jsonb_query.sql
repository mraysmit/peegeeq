-- Test JSONB querying capabilities after conversion
-- This script demonstrates the enhanced querying capabilities

-- Connect to the test database
\c native_queue_test;

-- Show current data structure
SELECT id, topic, payload, headers, created_at 
FROM queue_messages 
ORDER BY id DESC 
LIMIT 5;

-- Test payload querying (for simple string values wrapped in {"value": "..."})
SELECT id, topic, payload->>'value' as payload_value, headers
FROM queue_messages 
WHERE payload->>'value' LIKE '%Native queue%'
ORDER BY id DESC;

-- Test headers querying
SELECT id, topic, payload, headers->>'source' as source_header
FROM queue_messages 
WHERE headers->>'source' IS NOT NULL
ORDER BY id DESC;

-- Test complex JSON operations
SELECT id, topic, 
       payload,
       headers,
       jsonb_typeof(payload) as payload_type,
       jsonb_typeof(headers) as headers_type
FROM queue_messages 
ORDER BY id DESC 
LIMIT 3;

-- Test JSON containment queries
SELECT id, topic, payload
FROM queue_messages 
WHERE payload @> '{"value": "Hello, Native Queue!"}'
ORDER BY id DESC;

-- Show the difference: before conversion (string) vs after conversion (object)
-- This would show escaped JSON strings vs proper JSON objects
SELECT 
    id,
    topic,
    payload::text as payload_as_text,
    jsonb_pretty(payload) as payload_pretty,
    headers::text as headers_as_text,
    jsonb_pretty(headers) as headers_pretty
FROM queue_messages 
ORDER BY id DESC 
LIMIT 2;
