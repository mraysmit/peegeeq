-- Primary temporal index: transaction_time
CREATE INDEX IF NOT EXISTS "idx_{tableName}_tx_time"
    ON {schema}."{tableName}"(transaction_time);
