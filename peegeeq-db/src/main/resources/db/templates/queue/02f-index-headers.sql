-- GIN index for JSONB headers queries
CREATE INDEX IF NOT EXISTS "idx_{queueName}_headers_gin"
    ON {schema}."{queueName}" USING GIN(headers);

