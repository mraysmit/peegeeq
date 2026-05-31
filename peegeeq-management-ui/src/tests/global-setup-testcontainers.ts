import { PostgreSqlContainer } from '@testcontainers/postgresql'
import { spawn, execSync } from 'child_process'
import * as fs from 'fs'
import * as http from 'http'
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
const BACKEND_PID_FILE = path.join(process.cwd(), '.testcontainers-backend-pid')
const BACKEND_DB_PORT_FILE = path.join(process.cwd(), '.testcontainers-backend-db-port')

/**
 * Checks whether the backend correctly adds an Access-Control-Allow-Origin header
 * for the given origin.  Uses the raw `http` module so that the browser's
 * "forbidden request-header" restriction on `Origin` does not apply.
 */
function verifyCors(url: string, origin: string): Promise<boolean> {
  return new Promise((resolve) => {
    try {
      const parsed = new URL(url)
      const req = http.request(
        {
          hostname: parsed.hostname,
          port: parsed.port ? parseInt(parsed.port) : 80,
          path: parsed.pathname || '/',
          method: 'GET',
          headers: { Origin: origin },
        },
        (res) => {
          const header = res.headers['access-control-allow-origin']
          res.resume() // drain so the socket is released
          resolve(header === origin || header === '*')
        },
      )
      req.setTimeout(3000, () => {
        req.destroy()
        resolve(false)
      })
      req.on('error', () => resolve(false))
      req.end()
    } catch {
      resolve(false)
    }
  })
}

/**
 * Kills any process currently listening on `port` and waits briefly for the
 * port to be released.  Windows-compatible.
 */
function killBackendOnPort(port: number): void {
  console.log(`Killing stale backend process on port ${port}...`)
  try {
    if (process.platform === 'win32') {
      const output = execSync('netstat -aon', { shell: true, timeout: 5000 }).toString()
      const seenPids = new Set<string>()
      for (const line of output.split('\n')) {
        if (line.includes(`:${port} `) && line.includes('LISTENING')) {
          const parts = line.trim().split(/\s+/)
          const pid = parts[parts.length - 1]
          if (!pid || !/^\d+$/.test(pid) || pid === '0' || seenPids.has(pid)) continue
          seenPids.add(pid)

          // Safety check: only kill java.exe (the Maven/Vert.x backend).
          // Never kill Docker, Node, or other infrastructure processes.
          let processName = ''
          try {
            processName = execSync(
              `tasklist /FI "PID eq ${pid}" /FO CSV /NH`,
              { shell: true, timeout: 3000 },
            ).toString().toLowerCase()
          } catch {
            // If we can't identify the process, skip it
            console.log(`   Skipping PID ${pid} - could not identify process`)
            continue
          }

          if (!processName.includes('java.exe')) {
            console.log(`   Skipping PID ${pid} - not a java.exe process (${processName.split(',')[0]?.trim()})`)
            continue
          }

          console.log(`   Killing java.exe PID ${pid}...`)
          try {
            execSync(`taskkill /PID ${pid} /F /T`, { shell: true, timeout: 5000 })
          } catch {
            // process may have already exited
          }
        }
      }
      // Brief wait for the port to be released (~3 s on Windows)
      try { execSync('ping -n 4 127.0.0.1 > nul', { shell: true, timeout: 10000 }) } catch { /* ignore */ }
    } else {
      // On Linux/macOS, only kill java processes on the port
      try {
        const pids = execSync(`lsof -ti:${port} -sTCP:LISTEN 2>/dev/null || true`, { shell: true, timeout: 5000 }).toString().trim()
        for (const pid of pids.split('\n').filter(Boolean)) {
          const name = execSync(`ps -p ${pid} -o comm=`, { shell: true, timeout: 3000 }).toString().trim()
          if (name.includes('java')) {
            execSync(`kill -9 ${pid}`, { shell: true, timeout: 5000 })
          }
        }
      } catch { /* ignore */ }
      try { execSync('sleep 3', { shell: true, timeout: 5000 }) } catch { /* ignore */ }
    }
    console.log(`OK: Port ${port} released`)
  } catch (err) {
    console.warn('WARNING: Could not kill backend:', err instanceof Error ? err.message : String(err))
  }
}

async function waitForBackend(url: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(3000) })
      if (response.ok) return
    } catch {
      // not ready yet
    }
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  throw new Error(`Backend did not become healthy at ${url} within ${timeoutMs}ms`)
}

async function globalSetup() {
  console.log('\nStarting TestContainers PostgreSQL for UI tests...')

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

    console.log('OK: PostgreSQL container started:')
    console.log(`   Host: ${host}`)
    console.log(`   Port: ${port}`)
    console.log(`   Database: ${database}`)
    console.log(`   Container ID: ${containerId}`)

    // Create peegeeq user with SUPERUSER privilege (required for CREATE EXTENSION)
    console.log('\nCreating peegeeq user with SUPERUSER privilege...')
    const createUserResult = await postgresContainer.exec([
      'psql',
      '-U', 'postgres',
      '-d', 'postgres',
      '-c', "CREATE USER peegeeq WITH SUPERUSER PASSWORD 'peegeeq';"
    ])

    if (createUserResult.exitCode === 0) {
      console.log('OK: peegeeq user created successfully')
    } else if (createUserResult.output.includes('already exists')) {
      console.log('OK: peegeeq user already exists')
    } else {
      console.error('ERROR: Failed to create peegeeq user:', createUserResult.output)
      throw new Error('Failed to create peegeeq user')
    }

    // Verify the peegeeq user has SUPERUSER privilege
    // Note: SUPERUSER automatically grants all privileges including CREATEDB
    console.log('\nVerifying peegeeq user privileges...')
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
        console.log('OK: peegeeq user has SUPERUSER privilege (includes all privileges)')
      } else {
        console.error('ERROR: peegeeq user does not have SUPERUSER privilege')
        console.error('   Query result:', checkResult.output)
        throw new Error('peegeeq user does not have SUPERUSER privilege')
      }
    } else {
      console.error('ERROR: Failed to verify peegeeq user privileges:', checkResult.output)
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
    console.log(`Connection details written to: ${outputPath}`)

    // Read the port the backend was actually started with (not the state file, which may have been
    // overwritten by a later run).  Used below to detect when the container port changed.
    let backendStartedWithPort: number | undefined
    if (fs.existsSync(BACKEND_DB_PORT_FILE)) {
      try {
        backendStartedWithPort = parseInt(fs.readFileSync(BACKEND_DB_PORT_FILE, 'utf8').trim(), 10)
      } catch { /* ignore */ }
    }

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
    
    // Check if backend is running; auto-start it if not (enables CI/CD with no manual setup)
    console.log('\nChecking if PeeGeeQ backend is running...')
    const API_BASE_URL = 'http://127.0.0.1:8088'
    let backendAlreadyRunning = false

    try {
      const response = await fetch(`${API_BASE_URL}/health`, {
        method: 'GET',
        signal: AbortSignal.timeout(3000),
      })
      if (response.ok) {
        // Verify the running backend has the correct CORS config for the Vite
        // dev server origin.  If config is stale (http://localhost:3000 not
        // in allowedOrigins) the browser will see 403 / missing CORS headers.
        console.log('   Verifying backend CORS configuration for http://localhost:3000...')
        const corsOk = await verifyCors(`${API_BASE_URL}/health`, 'http://localhost:3000')
        if (corsOk) {
          console.log('OK: Backend is already running and healthy (CORS OK)')
          backendAlreadyRunning = true
          // If the testcontainers port changed since the backend was last started, the
          // backend's internal DB pool still points at the old port -> writes return 503.
          // Compare against BACKEND_DB_PORT_FILE which records the port used at startup.
          // If the file doesn't exist (backend was started outside this setup), restart to be safe.
          if (backendStartedWithPort === undefined || backendStartedWithPort !== port) {
            if (backendStartedWithPort === undefined) {
              console.log(`WARNING: No backend DB port record found - restarting backend to ensure correct DB config...`)
            } else {
              console.log(`WARNING: TestContainers port changed (${backendStartedWithPort} -> ${port}) - restarting backend with new DB port...`)
            }
            killBackendOnPort(8088)
            backendAlreadyRunning = false
          }
        } else {
          console.log('WARNING: Backend is running but CORS config is stale - restarting with current config...')
          killBackendOnPort(8088)
          // backendAlreadyRunning stays false -> auto-start below
        }
      }
    } catch {
      // Backend not running - will auto-start it below
    }

    if (backendAlreadyRunning) {
      // Backend was already running - clear any stale PID file so teardown doesn't try to kill it
      if (fs.existsSync(BACKEND_PID_FILE)) {
        fs.unlinkSync(BACKEND_PID_FILE)
      }
    } else {
      console.log('Backend not running - auto-starting PeeGeeQ REST server...')
      // Project root is one level above peegeeq-management-ui (process.cwd())
      const projectRoot = path.resolve(process.cwd(), '..')
      const mvnCmd = process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
      const backendLogPath = path.join(process.cwd(), 'e2e-backend.log')
      // Use 'pipe' for stdio then pipe manually to the log file.
      // On Windows, neither a raw integer fd nor a freshly-created WriteStream
      // (whose fd is null until the open callback fires) can be passed directly
      // to spawn's stdio option - both cause EINVAL / "invalid stdio" errors.
      const backendLogStream = fs.createWriteStream(backendLogPath, { flags: 'w' })

      const backendProc = spawn(
        mvnCmd,
        [
          'exec:java', '-pl', 'peegeeq-rest',
          `-DPEEGEEQ_DATABASE_HOST=${connectionInfo.host}`,
          `-DPEEGEEQ_DATABASE_PORT=${connectionInfo.port}`,
          `-DPEEGEEQ_DATABASE_NAME=${connectionInfo.database}`,
          `-DPEEGEEQ_DATABASE_USERNAME=${connectionInfo.username}`,
          `-DPEEGEEQ_DATABASE_PASSWORD=${connectionInfo.password}`,
          `-DPEEGEEQ_DATABASE_SCHEMA=public`,
        ],
        {
          cwd: projectRoot,
          env: {
            ...process.env,
            PEEGEEQ_DATABASE_HOST: connectionInfo.host,
            PEEGEEQ_DATABASE_PORT: String(connectionInfo.port),
            PEEGEEQ_DATABASE_NAME: connectionInfo.database,
            PEEGEEQ_DATABASE_USERNAME: connectionInfo.username,
            PEEGEEQ_DATABASE_PASSWORD: connectionInfo.password,
            PEEGEEQ_DATABASE_SCHEMA: 'public',
          },
          // shell: true is required on Windows so that .cmd files (mvn.cmd) are
          // invoked via cmd.exe; without it, CreateProcess fails with EINVAL.
          shell: process.platform === 'win32',
          stdio: ['ignore', 'pipe', 'pipe'],
        }
      )

      // Pipe stdout and stderr to the log file via the stream (works on all platforms)
      backendProc.stdout?.pipe(backendLogStream)
      backendProc.stderr?.pipe(backendLogStream)

      backendProc.on('error', (err: Error) =>
        console.error('ERROR: Backend process error:', err.message)
      )

      if (backendProc.pid) {
        fs.writeFileSync(BACKEND_PID_FILE, String(backendProc.pid))
        fs.writeFileSync(BACKEND_DB_PORT_FILE, String(connectionInfo.port))
        console.log(`Backend PID ${backendProc.pid} saved for teardown (DB port: ${connectionInfo.port})`)
        console.log(`Backend startup log: ${backendLogPath}`)
      }

      console.log('Waiting for backend to become healthy (up to 120s)...')
      try {
        await waitForBackend(`${API_BASE_URL}/health`, 120000)
        console.log('OK: Backend started and healthy')
      } catch (err) {
        console.error('ERROR: Backend did not start in time:', err instanceof Error ? err.message : String(err))
        console.error(`   Check backend startup log: ${backendLogPath}`)
        backendProc.kill()
        process.exit(1)
      }
    }

    // Clean up any existing database setups from previous test runs
    console.log('\nCleaning up existing database setups...')
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
              console.log(`   - Deleted setup: ${setupId}`)
            } catch (error) {
              console.warn(`   WARNING: Failed to delete setup ${setupId}:`, error instanceof Error ? error.message : String(error))
            }
          }
        }
        console.log('OK: Database cleanup complete')
      }
    } catch (error) {
      console.warn('WARNING: Error during database cleanup:', error instanceof Error ? error.message : String(error))
    }

    console.log('\nOK: TestContainers setup complete')
    console.log('   Database setup will be created through UI in database-setup.spec.ts\n')
  } catch (error) {
    console.error('\nERROR: Failed to start PostgreSQL container')
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
  console.log('\nCleaning up TestContainers state files...')

  try {
    // Stop the backend process if we auto-started it
    if (fs.existsSync(BACKEND_PID_FILE)) {
      const pidStr = fs.readFileSync(BACKEND_PID_FILE, 'utf8').trim()
      const pid = parseInt(pidStr, 10)
      if (!isNaN(pid)) {
        console.log(`\nStopping auto-started backend process (PID ${pid})...`)
        try {
          if (process.platform === 'win32') {
            execSync(`taskkill /PID ${pid} /T /F`, { stdio: 'ignore' })
          } else {
            process.kill(pid, 'SIGTERM')
          }
          console.log('OK: Backend process terminated')
        } catch {
          console.warn(`WARNING: Could not kill backend process ${pid} (may have already exited)`)
        }
      }
      fs.unlinkSync(BACKEND_PID_FILE)
    }

    // Clean up state file
    if (fs.existsSync(CONTAINER_INFO_FILE)) {
      fs.unlinkSync(CONTAINER_INFO_FILE)
      console.log('OK: Container state file removed')
    }

    // Keep testcontainers-db.json for backend to use
    console.log('Connection details preserved in testcontainers-db.json')
    console.log('   (Container will be reused in next test run)')
    console.log('   To stop manually: docker ps | grep postgres && docker stop <container-id>\n')
  } catch (error) {
    console.warn('WARNING: Error during cleanup:', error instanceof Error ? error.message : String(error))
  }
}

export default globalSetup
export { globalTeardown }

