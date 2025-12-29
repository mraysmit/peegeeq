#!/usr/bin/env node

/**
 * E2E Test Runner Script
 * 
 * This script ensures a clean test environment by:
 * 1. Killing any existing dev server on port 3000
 * 2. Starting a fresh dev server
 * 3. Running Playwright tests
 * 4. Cleaning up the dev server after tests complete
 */

import { spawn, exec } from 'child_process'
import { promisify } from 'util'

const execAsync = promisify(exec)

const DEV_SERVER_PORT = 3000

/**
 * Kill any process using the dev server port
 */
async function killExistingServer() {
  console.log(`\n🔍 Checking for existing dev server on port ${DEV_SERVER_PORT}...`)
  
  try {
    if (process.platform === 'win32') {
      // Windows: Find and kill process using the port
      const { stdout } = await execAsync(`netstat -ano | findstr :${DEV_SERVER_PORT}`)
      const lines = stdout.trim().split('\n')
      
      for (const line of lines) {
        const match = line.match(/LISTENING\s+(\d+)/)
        if (match) {
          const pid = match[1]
          console.log(`   Found process ${pid} on port ${DEV_SERVER_PORT}, killing...`)
          await execAsync(`taskkill /F /PID ${pid}`)
          console.log(`   ✅ Killed process ${pid}`)
        }
      }
    } else {
      // Unix: Use lsof to find and kill
      const { stdout } = await execAsync(`lsof -ti:${DEV_SERVER_PORT}`)
      const pids = stdout.trim().split('\n').filter(Boolean)
      
      for (const pid of pids) {
        console.log(`   Found process ${pid} on port ${DEV_SERVER_PORT}, killing...`)
        await execAsync(`kill -9 ${pid}`)
        console.log(`   ✅ Killed process ${pid}`)
      }
    }
  } catch (error) {
    // No process found on port - this is fine
    console.log(`   ✅ No existing dev server found`)
  }
  
  // Wait a bit for port to be released
  await new Promise(resolve => setTimeout(resolve, 1000))
}

/**
 * Run Playwright tests
 */
async function runTests() {
  console.log('\n🧪 Running Playwright tests in headed mode...\n')

  return new Promise((resolve, reject) => {
    const playwright = spawn('npx', [
      'playwright',
      'test',
      '--headed',
      '--workers=1',
      '--project=chromium'
    ], {
      stdio: 'inherit',
      shell: true
    })

    playwright.on('close', (code) => {
      if (code === 0) {
        resolve()
      } else {
        reject(new Error(`Playwright tests failed with exit code ${code}`))
      }
    })

    playwright.on('error', (error) => {
      reject(error)
    })
  })
}

/**
 * Main execution
 */
async function main() {
  try {
    // Kill existing dev server
    await killExistingServer()
    
    // Run tests (Playwright will start the dev server via webServer config)
    await runTests()
    
    console.log('\n✅ Tests completed successfully\n')
    process.exit(0)
  } catch (error) {
    console.error('\n❌ Test run failed:', error.message)
    process.exit(1)
  }
}

main()

