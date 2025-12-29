-- Template for creating individual queue tables
-- Parameters: {queueName}, {schema}

CREATE TABLE IF NOT EXISTS {schema}."{queueName}" (
    LIKE {schema}.queue_template INCLUDING ALL
);
