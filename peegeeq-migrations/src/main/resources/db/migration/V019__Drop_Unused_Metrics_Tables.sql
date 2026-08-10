-- V019: Drop the write-only metrics persistence tables (metrics-stack remediation, 2026-08-09).
--
-- queue_metrics was INSERTed by PeeGeeQMetrics.persistMetrics on a timer and read by NOTHING in
-- the repository; setup-service-provisioned schemas never contained it, so in that deployment
-- shape the persistence timer failed every interval, forever. The persistence code was deleted
-- on 2026-08-09; these tables and their retention function are its schema remains.
--
-- connection_pool_metrics was never written OR read by any code, ever.
--
-- Metrics live in the manager's in-process registry and are read through typed accessors
-- (QueueStats, SetupSaturationSnapshot). Historical persistence returns only with a real reader
-- - and with it, a new migration.

DROP FUNCTION IF EXISTS cleanup_old_metrics(INT);
DROP TABLE IF EXISTS queue_metrics CASCADE;
DROP TABLE IF EXISTS connection_pool_metrics CASCADE;
