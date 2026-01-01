import { test, expect } from '../page-objects'
import * as fs from 'fs'
import { SETUP_ID } from '../test-constants'

/**
 * Setup Prerequisite
 * 
 * Creates the required database setup for queue and event store tests.
 * This test runs FIRST to ensure the setup exists for dependent tests.
 */

test.describe('Setup Prerequisite', () => {

  test('should create default database setup', async ({ page, databaseSetupsPage }) => {
    // Read TestContainers connection details
    const dbConfig = JSON.parse(fs.readFileSync('testcontainers-db.json', 'utf8'))

    await page.goto('/')
    await databaseSetupsPage.goto()

    // Check if setup already exists
    const exists = await databaseSetupsPage.setupExists(SETUP_ID)
    
    if (!exists) {
      // Create setup through UI
      await databaseSetupsPage.createSetup({
        setupId: SETUP_ID,
        host: dbConfig.host,
        port: dbConfig.port,
        databaseName: `e2e_test_db_${Date.now()}`,
        username: dbConfig.username,
        password: dbConfig.password,
        schema: 'public'
      })

      // Verify setup was created
      const created = await databaseSetupsPage.setupExists(SETUP_ID)
      expect(created).toBeTruthy()
    }
  })
})
