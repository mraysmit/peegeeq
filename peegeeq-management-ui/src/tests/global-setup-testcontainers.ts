import * as fs from 'fs'
import * as path from 'path'

/**
 * Global setup for Playwright tests.
 *
 * This setup:
 * 1. Reads connection details from testcontainers-db.json (created by start-backend-with-testcontainers.ps1)
 * 2. Checks if the backend is running and healthy
 * 3. Cleans up any existing database setups via the API (Test Data Lifecycle)
 *
 * Note: The database container is now managed by the backend startup script, not Playwright.
 */

async function globalSetup() {
  console.log('\n🔍 Checking test environment configuration...')

  const dbConfigPath = path.join(process.cwd(), 'testcontainers-db.json')
  
  if (!fs.existsSync(dbConfigPath)) {
    console.error('\n❌ Database configuration file not found:', dbConfigPath)
    console.error('   Please run the backend startup script first:')
    console.error('   ./scripts/start-backend-with-testcontainers.ps1 (Windows)')
    console.error('   ./scripts/start-backend-with-testcontainers.sh (Linux/Mac)')
    process.exit(1)
  }

  try {
    const dbConfig = JSON.parse(fs.readFileSync(dbConfigPath, 'utf8'))
    console.log('✅ Found database configuration')
    console.log(`   Host: ${dbConfig.host}`)
    console.log(`   Port: ${dbConfig.port}`)
    console.log(`   Database: ${dbConfig.database}`)

    // Check if backend is running
    console.log('\n🔍 Checking if PeeGeeQ backend is running...')
    const API_BASE_URL = 'http://127.0.0.1:8080'

    try {
      const response = await fetch(`${API_BASE_URL}/health`, {
        method: 'GET',
        signal: AbortSignal.timeout(5000),
      })

      if (!response.ok) {
        console.error(`\n❌ Backend health check failed with status: ${response.status}`)
        console.error('   Please start the PeeGeeQ REST server on port 8080 before running e2e tests.')
        process.exit(1)
      }

      console.log('✅ Backend is running and healthy')
    } catch (error) {
      console.error('\n❌ Cannot connect to PeeGeeQ backend at http://127.0.0.1:8080')
      console.error('   Error:', error instanceof Error ? error.message : String(error))
      console.error('\n   Please start the PeeGeeQ REST server before running e2e tests.')
      process.exit(1)
    }

    // --- TEST DATA LIFECYCLE MANAGEMENT ---
    // Clean up any existing database setups from previous test runs
    console.log('\n🧹 Cleaning up existing database setups...')
    try {
      const setupsResponse = await fetch(`${API_BASE_URL}/api/v1/setups`, {
        method: 'GET',
        signal: AbortSignal.timeout(5000),
      })

      if (setupsResponse.ok) {
        const setupsData = await setupsResponse.json()
        if (setupsData.setupIds && Array.isArray(setupsData.setupIds)) {
          for (const setupId of setupsData.setupIds) {
            try {
              await fetch(`${API_BASE_URL}/api/v1/setups/${setupId}`, {
                method: 'DELETE',
                signal: AbortSignal.timeout(5000),
              })
              console.log(`   ✓ Deleted setup: ${setupId}`)
            } catch (error) {
              console.warn(`   ⚠️  Failed to delete setup ${setupId}:`, error instanceof Error ? error.message : String(error))
            }
          }
        }
        console.log('✅ Database cleanup complete')
      }
    } catch (error) {
      console.warn('⚠️  Error during database cleanup:', error instanceof Error ? error.message : String(error))
    }

    console.log('\n✅ Global setup complete')
  } catch (error) {
    console.error('\n❌ Failed to read database configuration')
    console.error('   Error:', error instanceof Error ? error.message : String(error))
    process.exit(1)
  }
}

/**
 * Global teardown
 */
async function globalTeardown() {
  // No cleanup needed as we don't manage the container anymore
  console.log('\n✅ Global teardown complete')
}

export default globalSetup
export { globalTeardown }

