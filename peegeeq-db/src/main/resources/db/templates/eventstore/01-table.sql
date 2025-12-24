-- Template for creating bi-temporal event store tables
-- Parameters: {tableName}, {schema}, {notificationPrefix}

CREATE TABLE IF NOT EXISTS {schema}.{tableName} (
    LIKE {schema}.event_store_template INCLUDING ALL
);
