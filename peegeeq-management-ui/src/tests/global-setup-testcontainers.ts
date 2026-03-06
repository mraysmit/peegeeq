import { PostgreSqlContainer } from '@testcontainers/postgresql'
import * as fs from 'fs'
import * as path from 'path'

/**
 * Global setup for Playwright tests with TestContainers.
 *
 * This setup:
 * 1. Starts a PostgreSQL container with the correct credentials
 * 2. Exports connection details to a file for the backend to use
 * 3. Waits for the backend to be healthy before running tests
 *
 * The container uses reuse mode, so it persists across test runs.
 * Manual cleanup is required (docker stop) when you're done testing.
 */

// Store container info in a file so teardown can access it
// Using process.cwd() which points to peegeeq-management-ui when running tests
const CONTAINER_INFO_FILE = path.join(process.cwd(), '.testcontainers-state.json')

async function globalSetup() {
  console.log('\n🐳 Starting TestContainers PostgreSQL for UI tests...')

  try {
    // Start PostgreSQL container with postgres superuser to create peegeeq user
    // We'll create a dedicated peegeeq user with proper permissions
    const postgresContainer = await new PostgreSqlContainer('postgres:15.13-alpine3.20')
      .withDatabase('postgres')
      .withUsername('postgres')
      .withPassword('postgres')
      .withExposedPorts(5432)
      .withReuse() // Reuse container so backend stays connected
      .start()

    const host = postgresContainer.getHost()
    const port = postgresContainer.getPort()
    const database = postgresContainer.getDatabase()
    const containerId = postgresContainer.getId()

    console.log('✅ PostgreSQL container started:')
    console.log(`   Host: ${host}`)
    console.log(`   Port: ${port}`)
    console.log(`   Database: ${database}`)
    console.log(`   Container ID: ${containerId}`)

    // Create peegeeq user with SUPERUSER privilege (required for CREATE EXTENSION)
    console.log('\n🔧 Creating peegeeq user with SUPERUSER privilege...')
    const createUserResult = await postgresContainer.exec([
      'psql',
      '-U', 'postgres',
      '-d', 'postgres',
      '-c', "CREATE USER peegeeq WITH SUPERUSER PASSWORD 'peegeeq';"
    ])

    if (createUserResult.exitCode === 0) {
      console.log('✅ peegeeq user created successfully')
    } else if (createUserResult.output.includes('already exists')) {
      console.log('✅ peegeeq user already exists')
    } else {
      console.error('❌ Failed to create peegeeq user:', createUserResult.output)
      throw new Error('Failed to create peegeeq user')
    }

    // Verify the peegeeq user has SUPERUSER privilege
    // Note: SUPERUSER automatically grants all privileges including CREATEDB
    console.log('\n🔍 Verifying peegeeq user privileges...')
    const checkResult = await postgresContainer.exec([
      'psql',
      '-U', 'postgres',
      '-d', 'postgres',
      '-t',  // Tuples only (no headers)
      '-c', "SELECT rolsuper FROM pg_roles WHERE rolname = 'peegeeq';"
    ])

    if (checkResult.exitCode === 0) {
      const isSuperuser = checkResult.output.trim() === 't'

      if (isSuperuser) {
        console.log('✅ peegeeq user has SUPERUSER privilege (includes all privileges)')
      } else {
        console.error('❌ peegeeq user does not have SUPERUSER privilege')
        console.error('   Query result:', checkResult.output)
        throw new Error('peegeeq user does not have SUPERUSER privilege')
      }
    } else {
      console.error('❌ Failed to verify peegeeq user privileges:', checkResult.output)
      throw new Error('Failed to verify peegeeq user privileges')
    }

    // Export connection details with peegeeq user for the backend to use
    const connectionInfo = {
      host,
      port,
      database,
      username: 'peegeeq',
      password: 'peegeeq',
      jdbcUrl: `postgres://peegeeq:peegeeq@${host}:${port}/${database}`,
    }

    const outputPath = path.join(process.cwd(), 'testcontainers-db.json')
    fs.writeFileSync(outputPath, JSON.stringify(connectionInfo, null, 2))
    console.log(`📝 Connection details written to: ${outputPath}`)

    // Store container state for teardown (if needed)
    const containerState = {
      containerId,
      host,
      port,
      database,
      username: 'peegeeq',
      password: 'peegeeq',
    }
    fs.writeFileSync(CONTAINER_INFO_FILE, JSON.stringify(containerState, null, 2))
    
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
        console.error('   The backend should connect to the TestContainers database.')
        console.error(`   Connection details are in: ${outputPath}`)
        process.exit(1)
      }

      console.log('✅ Backend is running and healthy')
    } catch (error) {
      console.error('\n❌ Cannot connect to PeeGeeQ backend at http://127.0.0.1:8080')
      console.error('   Error:', error instanceof Error ? error.message : String(error))
      console.error('\n   Please start the PeeGeeQ REST server before running e2e tests.')
      console.error('   The backend should connect to the TestContainers database:')
      console.error(`   - Host: ${host}`)
      console.error(`   - Port: ${port}`)
      console.error(`   - Database: ${database}`)
      console.error(`   - Username: ${connectionInfo.username}`)
      console.error(`   - Password: ${connectionInfo.password}`)
      console.error(`\n   Connection details are in: ${outputPath}\n`)
      process.exit(1)
    }

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

    console.log('\n✅ TestContainers setup complete')
    console.log('   Database setup will be created through UI in database-setup.spec.ts\n')
  } catch (error) {
    console.error('\n❌ Failed to start PostgreSQL container')
    console.error('   Error:', error instanceof Error ? error.message : String(error))
    console.error('\n   Make sure Docker is running and you have the postgres:15.13-alpine3.20 image.\n')
    process.exit(1)
  }
}

/**
 * Global teardown - cleanup state files
 *
 * Container is reused across test runs, so we don't stop it.
 * Database state is cleaned up at the start of each test run.
 */
async function globalTeardown() {
  console.log('\n🧹 Cleaning up TestContainers state files...')

  try {
    // Clean up state file
    if (fs.existsSync(CONTAINER_INFO_FILE)) {
      fs.unlinkSync(CONTAINER_INFO_FILE)
      console.log('✅ Container state file removed')
    }

    // Keep testcontainers-db.json for backend to use
    console.log('📝 Connection details preserved in testcontainers-db.json')
    console.log('   (Container will be reused in next test run)')
    console.log('   To stop manually: docker ps | grep postgres && docker stop <container-id>\n')
  } catch (error) {
    console.warn('⚠️  Error during cleanup:', error instanceof Error ? error.message : String(error))
  }
}

export default globalSetup
export { globalTeardown }

