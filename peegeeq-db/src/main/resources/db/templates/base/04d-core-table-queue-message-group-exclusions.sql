-- Native queue group exclusions: the outcome REJECTED of a consumer group (Flyway V022)
CREATE TABLE IF NOT EXISTS {schema}.queue_message_group_exclusions (
    message_id  BIGINT       NOT NULL REFERENCES {schema}.queue_messages(id) ON DELETE CASCADE,
    group_name  VARCHAR(255) NOT NULL,
    PRIMARY KEY (message_id, group_name)
);
