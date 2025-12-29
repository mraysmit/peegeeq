import { test as base } from '@playwright/test'
import { Client } from 'pg'
import * as fs from 'fs'
import * as path from 'path'

/**
 * Database cleanup fixture for E2E tests.
 * 
 * This fixture provides:
 * 1. Database connection using TestContainers credentials
 * 2. Automatic cleanup before each test
 * 3. Proper connection management
 * 
 * Usage:
 *   import { test } from './fixtures/database-cleanup'
 *   
 *   test('my test', async ({ page, cleanDatabase }) => {
 *     // Database is clean at start of test
 *     // ... test code ...
 *   })
 */

interface DatabaseCleanupFixture {
  cleanDatabase: void
}

export const test = base.extend<DatabaseCleanupFixture>({
  cleanDatabase: async ({}, use) => {
    // Read database connection info from TestContainers
    const dbConfigPath = path.join(process.cwd(), 'testcontainers-db.json')
    
    if (!fs.existsSync(dbConfigPath)) {
      throw new Error(
        'TestContainers database config not found. ' +
        'Make sure global-setup-testcontainers.ts has run.'
      )
    }

    const dbConfig = JSON.parse(fs.readFileSync(dbConfigPath, 'utf-8'))
    
    // Create database client
    const client = new Client({
      host: dbConfig.host,
      port: dbConfig.port,
      database: dbConfig.database,
      user: dbConfig.username,
      password: dbConfig.password,
    })

    try {
      await client.connect()

      // Clean up all PeeGeeQ data before test
      console.log('🧹 Cleaning database before test...')

      // Get list of all schemas (setups) - exclude system schemas
      const schemasResult = await client.query(`
        SELECT schema_name
        FROM information_schema.schemata
        WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'public')
          AND schema_name NOT LIKE 'pg_%'  -- Exclude all PostgreSQL system schemas
      `)

      // Drop each setup schema
      for (const row of schemasResult.rows) {
        const schemaName = row.schema_name
        console.log(`   Dropping schema: ${schemaName}`)
        await client.query(`DROP SCHEMA IF EXISTS "${schemaName}" CASCADE`)
      }

      console.log('✅ Database cleaned')

    } catch (error) {
      console.error('❌ Database cleanup failed:', error)
      throw error
    } finally {
      await client.end()
    }

    // Provide the fixture (void - cleanup is done)
    await use()
  },
})

export { expect } from '@playwright/test'

