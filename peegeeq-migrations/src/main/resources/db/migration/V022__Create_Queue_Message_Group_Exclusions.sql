-- V022: Create queue_message_group_exclusions
--
-- A native consumer group whose group predicate refuses a message records the refusal
-- here. The row is the outcome REJECTED for that group. The message stays AVAILABLE in
-- queue_messages for every other consumer, and the claim of the refusing group skips it.
-- The row has no status, reason, or time column: the reason is always the group filter.
-- The foreign key deletes the row with its message. No job cleans this table.
--
-- Design: docs-design/consumer-groups/PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md,
-- "New and changed objects" (phase 6) and "Delivery Outcome Contract".
--
-- Rollback: DROP TABLE queue_message_group_exclusions. The refusing groups then claim
-- the refused messages again.

DO $$
BEGIN
    RAISE NOTICE '[PEEGEEQ MIGRATION] script=V022__Create_Queue_Message_Group_Exclusions.sql db=% schema=%',
        current_database(), current_schema();
END $$;

CREATE TABLE IF NOT EXISTS queue_message_group_exclusions (
    message_id  BIGINT       NOT NULL REFERENCES queue_messages(id) ON DELETE CASCADE,
    group_name  VARCHAR(255) NOT NULL,
    PRIMARY KEY (message_id, group_name)
);

COMMENT ON TABLE queue_message_group_exclusions IS
    'Native queue messages that a consumer group predicate refused (outcome REJECTED). The refusing group does not claim the message again.';
