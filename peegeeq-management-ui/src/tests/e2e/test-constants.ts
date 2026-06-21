/**
 * Shared test constants
 * 
 * These constants are used across multiple test files.
 */

/**
 * Fixed setup ID for E2E tests.
 * Using 'default' as a known setup name that is created in database-setup.spec.ts.
 */
export const SETUP_ID = 'default'

/**
 * Database schema for E2E test setups.
 *
 * Mirrors the backend standard (`PostgreSQLTestConstants.TEST_SCHEMA = "peegeeq_test"`).
 * PeeGeeQ has no default schema; every test must set an explicit, non-default schema.
 */
export const TEST_SCHEMA = 'peegeeq_test'

