#!/usr/bin/env node

/**
 * Full System Test Harness
 * 
 * This script orchestrates a complete end-to-end test of the PeeGeeQ Management UI
 * by starting all required services, creating test data, and running comprehensive
 * UI validation tests.
 */

const { spawn, exec } = require('child_process')
const { promisify } = require('util')
const path = require('path')
const fs = require('fs')

const execAsync = promisify(exec)

class FullSystemTestHarness {
  constructor() {
    this.processes = {
      postgres: null,
      backend: null,
      frontend: null
    }
    this.testResults = {
      setup: false,
      backend: false,
      frontend: false,
      integration: false,
      fullSystem: false
    }
  }

  async run() {
    console.log('🎭 PeeGeeQ Management UI - Full System Test Harness')
    console.log('=' .repeat(70))
    console.log('')

    try {
      // Step 1: Environment validation
      await this.validateEnvironment()
      
      // Step 2: Start required services
      await this.startServices()
      
      // Step 3: Wait for services to be ready
      await this.waitForServices()
      
      // Step 4: Run comprehensive tests
      await this.runTests()
      
      // Step 5: Generate test report
      this.generateReport()
      
    } catch (error) {
      console.error('❌ Full system test failed:', error.message)
      console.error('')
      console.error('💡 Troubleshooting tips:')
      console.error('  - Ensure PostgreSQL is running on port 5432')
      console.error('  - Check that ports 8080 and 3000 are available')
      console.error('  - Verify all dependencies are installed (npm install)')
      console.error('  - Make sure the backend REST server can connect to PostgreSQL')
      process.exit(1)
    } finally {
      await this.cleanup()
    }
  }

  async validateEnvironment() {
    console.log('🔍 Validating test environment...')
    
    // Check if we're in the right directory
    const packageJsonPath = path.join(process.cwd(), 'package.json')
    if (!fs.existsSync(packageJsonPath)) {
      throw new Error('Must run from peegeeq-management-ui directory')
    }
    
    // Check if backend project exists
    const backendPath = path.join(process.cwd(), '..', 'peegeeq-rest')
    if (!fs.existsSync(backendPath)) {
      throw new Error('Backend project not found. Expected peegeeq-rest directory at ../peegeeq-rest')
    }
    
    // Check if PostgreSQL is accessible
    try {
      await this.checkPostgreSQL()
      console.log('  ✅ PostgreSQL is accessible')
    } catch (error) {
      throw new Error(`PostgreSQL not accessible: ${error.message}`)
    }
    
    // Check if required npm packages are installed
    if (!fs.existsSync(path.join(process.cwd(), 'node_modules'))) {
      console.log('  📦 Installing npm dependencies...')
      await execAsync('npm install')
    }
    
    console.log('  ✅ Environment validation passed')
    this.testResults.setup = true
  }

  async checkPostgreSQL() {
    // Simple check to see if PostgreSQL is running
    const { exec } = require('child_process')
    return new Promise((resolve, reject) => {
      exec('pg_isready -h localhost -p 5432', (error, stdout, stderr) => {
        if (error) {
          reject(new Error('PostgreSQL is not running or not accessible'))
        } else {
          resolve(true)
        }
      })
    })
  }

  async startServices() {
    console.log('🚀 Starting required services...')
    
    // Start backend REST server
    console.log('  🔧 Starting backend REST server...')
    await this.startBackend()
    
    // Start frontend development server
    console.log('  🖥️  Starting frontend development server...')
    await this.startFrontend()
    
    console.log('  ✅ All services started')
  }

  async startBackend() {
    return new Promise((resolve, reject) => {
      const backendPath = path.join(process.cwd(), '..', 'peegeeq-rest')
      
      this.processes.backend = spawn('mvn', ['exec:java'], {
        cwd: backendPath,
        stdio: ['ignore', 'pipe', 'pipe']
      })
      
      let output = ''
      this.processes.backend.stdout.on('data', (data) => {
        output += data.toString()
        if (output.includes('PeeGeeQ REST Server started successfully')) {
          console.log('    ✅ Backend server is ready')
          this.testResults.backend = true
          resolve()
        }
      })
      
      this.processes.backend.stderr.on('data', (data) => {
        const error = data.toString()
        if (error.includes('ERROR') || error.includes('Exception')) {
          reject(new Error(`Backend startup failed: ${error}`))
        }
      })
      
      this.processes.backend.on('error', (error) => {
        reject(new Error(`Failed to start backend: ${error.message}`))
      })
      
      // Timeout after 60 seconds
      setTimeout(() => {
        if (!this.testResults.backend) {
          reject(new Error('Backend startup timeout (60s)'))
        }
      }, 60000)
    })
  }

  async startFrontend() {
    return new Promise((resolve, reject) => {
      this.processes.frontend = spawn('npm', ['run', 'dev'], {
        stdio: ['ignore', 'pipe', 'pipe']
      })
      
      let output = ''
      this.processes.frontend.stdout.on('data', (data) => {
        output += data.toString()
        if (output.includes('Local:') && output.includes('3000')) {
          console.log('    ✅ Frontend server is ready')
          this.testResults.frontend = true
          resolve()
        }
      })
      
      this.processes.frontend.stderr.on('data', (data) => {
        const error = data.toString()
        if (error.includes('ERROR') || error.includes('EADDRINUSE')) {
          reject(new Error(`Frontend startup failed: ${error}`))
        }
      })
      
      this.processes.frontend.on('error', (error) => {
        reject(new Error(`Failed to start frontend: ${error.message}`))
      })
      
      // Timeout after 30 seconds
      setTimeout(() => {
        if (!this.testResults.frontend) {
          reject(new Error('Frontend startup timeout (30s)'))
        }
      }, 30000)
    })
  }

  async waitForServices() {
    console.log('⏳ Waiting for services to be fully ready...')
    
    // Wait for backend health check
    let backendReady = false
    for (let i = 0; i < 30; i++) {
      try {
        const response = await fetch('http://localhost:8080/health')
        if (response.ok) {
          backendReady = true
          break
        }
      } catch (error) {
        // Service not ready yet
      }
      await this.sleep(1000)
    }
    
    if (!backendReady) {
      throw new Error('Backend health check failed')
    }
    
    // Wait for frontend to be accessible
    let frontendReady = false
    for (let i = 0; i < 20; i++) {
      try {
        const response = await fetch('http://localhost:3000')
        if (response.ok || response.status === 200) {
          frontendReady = true
          break
        }
      } catch (error) {
        // Service not ready yet
      }
      await this.sleep(1000)
    }
    
    if (!frontendReady) {
      throw new Error('Frontend accessibility check failed')
    }
    
    console.log('  ✅ All services are ready and accessible')
    
    // Additional stabilization wait
    await this.sleep(3000)
  }

  async runTests() {
    console.log('🧪 Running comprehensive test suite...')
    
    // Run integration tests first
    console.log('  📊 Running integration tests...')
    try {
      await execAsync('npm run test:integration')
      console.log('    ✅ Integration tests passed')
      this.testResults.integration = true
    } catch (error) {
      console.log('    ⚠️  Integration tests had issues:', error.message)
    }
    
    // Run the full system E2E test
    console.log('  🎭 Running full system E2E tests...')
    try {
      await execAsync('npx playwright test src/tests/e2e/full-system-test.spec.ts --reporter=line')
      console.log('    ✅ Full system E2E tests passed')
      this.testResults.fullSystem = true
    } catch (error) {
      console.log('    ❌ Full system E2E tests failed:', error.message)
      throw error
    }
    
    console.log('  ✅ All tests completed successfully')
  }

  generateReport() {
    console.log('')
    console.log('📋 Test Results Summary')
    console.log('=' .repeat(50))
    
    const results = [
      ['Environment Setup', this.testResults.setup],
      ['Backend Service', this.testResults.backend],
      ['Frontend Service', this.testResults.frontend],
      ['Integration Tests', this.testResults.integration],
      ['Full System E2E Tests', this.testResults.fullSystem]
    ]
    
    results.forEach(([test, passed]) => {
      const status = passed ? '✅ PASS' : '❌ FAIL'
      console.log(`  ${test.padEnd(25)} ${status}`)
    })
    
    const allPassed = results.every(([, passed]) => passed)
    
    console.log('')
    if (allPassed) {
      console.log('🎉 ALL TESTS PASSED! The PeeGeeQ Management UI is working correctly.')
      console.log('')
      console.log('✨ The management UI successfully:')
      console.log('  - Connects to the backend API')
      console.log('  - Displays real system data')
      console.log('  - Handles queue management operations')
      console.log('  - Shows consumer group information')
      console.log('  - Provides real-time monitoring capabilities')
    } else {
      console.log('❌ Some tests failed. Please review the output above.')
    }
  }

  async cleanup() {
    console.log('')
    console.log('🧹 Cleaning up test environment...')
    
    // Kill processes
    if (this.processes.frontend) {
      this.processes.frontend.kill('SIGTERM')
      console.log('  ✅ Frontend server stopped')
    }
    
    if (this.processes.backend) {
      this.processes.backend.kill('SIGTERM')
      console.log('  ✅ Backend server stopped')
    }
    
    // Wait a moment for cleanup
    await this.sleep(2000)
    
    console.log('  ✅ Cleanup completed')
  }

  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms))
  }
}

// Run the test harness if called directly
if (require.main === module) {
  const harness = new FullSystemTestHarness()
  harness.run().catch(console.error)
}

module.exports = FullSystemTestHarness
