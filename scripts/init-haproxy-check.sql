-- Creates the PostgreSQL user required for HAProxy protocol-level health checks.
--
-- HAProxy's "option pgsql-check user haproxy_check" sends a PostgreSQL startup
-- packet to each backend.  The server responds with an authentication challenge
-- (or an error); either response proves PostgreSQL is alive and accepting the
-- wire protocol.  A TCP-only check (option tcp-check) cannot distinguish between
-- a port that is open but not yet serving the protocol and a fully running server.
--
-- This user requires no password, no schema access, and no database privileges.
-- Its sole purpose is to trigger an authentication response from PostgreSQL.

CREATE USER haproxy_check;
