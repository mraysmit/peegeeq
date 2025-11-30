-- Template for creating individual queue tables
-- Parameters: {queueName}, {schema}

CREATE TABLE IF NOT EXISTS {schema}.{queueName} (
    LIKE peegeeq.queue_template INCLUDING ALL
);
