#!/usr/bin/env node

/**
 * Comprehensive UI Test Runner for PeeGeeQ Management UI
 * 
 * This script orchestrates the complete UI testing pipeline:
 * - Starts backend and frontend servers
 * - Runs all test suites in sequence
 * - Generates comprehensive reports
 * - Handles cleanup and error reporting
 */

const { spawn, exec } = require('child_process')
const fs = require('fs')
const path = require('path')

class UITestRunner {
  constructor() {
    this.backendProcess = null
    this.frontendProcess = null
    this.testResults = {
      integration: null,
      interactions: null,
      dataValidation: null,
      visualRegression: null
    }
  }

  async run() {
    console.log('🎭 Starting PeeGeeQ Management UI Test Suite')
    console.log('=' .repeat(60))

    try {
      // Step 1: Start servers
      await this.startServers()
      
      // Step 2: Wait for servers to be ready
      await this.waitForServers()
      
      // Step 3: Run test suites
      await this.runTestSuites()
      
      // Step 4: Generate reports
      await this.generateReports()
      
      // Step 5: Display results
      this.displayResults()
      
    } catch (error) {
      console.error('❌ Test suite failed:', error.message)
      process.exit(1)
    } finally {
      // Cleanup
      await this.cleanup()
    }
  }

  async startServers() {
    console.log('🚀 Starting backend and frontend servers...')
    
    // Start backend server
    console.log('  📡 Starting backend REST API server...')
    this.backendProcess = spawn('mvn', [
      'exec:java',
      '-Dexec.mainClass=dev.mars.peegeeq.rest.PeeGeeQRestServer',
      '-Dexec.args=8080',
      '-pl', 'peegeeq-rest'
    ], {
      cwd: path.resolve(__dirname, '../../..'),
      stdio: ['ignore', 'pipe', 'pipe']
    })

    // Start frontend server
    console.log('  🌐 Starting frontend development server...')
    this.frontendProcess = spawn('npm', ['run', 'dev'], {
      stdio: ['ignore', 'pipe', 'pipe']
    })

    // Handle process errors
    this.backendProcess.on('error', (error) => {
      console.error('Backend server error:', error)
    })

    this.frontendProcess.on('error', (error) => {
      console.error('Frontend server error:', error)
    })
  }

  async waitForServers() {
    console.log('⏳ Waiting for servers to be ready...')
    
    // Wait for backend
    await this.waitForUrl('http://localhost:8080/health', 'Backend API')
    
    // Wait for frontend
    await this.waitForUrl('http://localhost:3000', 'Frontend UI')
    
    console.log('✅ All servers are ready!')
  }

  async waitForUrl(url, name, maxAttempts = 30) {
    for (let i = 0; i < maxAttempts; i++) {
      try {
        const response = await fetch(url)
        if (response.ok) {
          console.log(`  ✅ ${name} is ready`)
          return
        }
      } catch (error) {
        // Server not ready yet
      }
      
      console.log(`  ⏳ Waiting for ${name}... (${i + 1}/${maxAttempts})`)
      await this.sleep(2000)
    }
    
    throw new Error(`${name} failed to start after ${maxAttempts} attempts`)
  }

  async runTestSuites() {
    console.log('🧪 Running comprehensive test suites...')
    
    // Run integration tests
    console.log('  📊 Running integration tests...')
    this.testResults.integration = await this.runCommand('npm', ['run', 'test:integration'])
    
    // Run UI interaction tests
    console.log('  🖱️  Running UI interaction tests...')
    this.testResults.interactions = await this.runCommand('npm', ['run', 'test:e2e:interactions'])
    
    // Run data validation tests
    console.log('  📋 Running data validation tests...')
    this.testResults.dataValidation = await this.runCommand('npm', ['run', 'test:e2e:data'])
    
    // Run visual regression tests
    console.log('  🎨 Running visual regression tests...')
    this.testResults.visualRegression = await this.runCommand('npm', ['run', 'test:e2e:visual'])
  }

  async runCommand(command, args) {
    return new Promise((resolve) => {
      const process = spawn(command, args, {
        stdio: ['ignore', 'pipe', 'pipe']
      })

      let stdout = ''
      let stderr = ''

      process.stdout.on('data', (data) => {
        stdout += data.toString()
      })

      process.stderr.on('data', (data) => {
        stderr += data.toString()
      })

      process.on('close', (code) => {
        resolve({
          success: code === 0,
          code,
          stdout,
          stderr
        })
      })
    })
  }

  async generateReports() {
    console.log('📊 Generating test reports...')
    
    // Generate Playwright HTML report
    await this.runCommand('npx', ['playwright', 'show-report', '--host', '0.0.0.0'])
    
    // Create summary report
    const summaryReport = this.createSummaryReport()
    fs.writeFileSync('test-results/summary.json', JSON.stringify(summaryReport, null, 2))
    
    console.log('✅ Reports generated in test-results/ directory')
  }

  createSummaryReport() {
    const summary = {
      timestamp: new Date().toISOString(),
      overall: {
        passed: 0,
        failed: 0,
        total: 0
      },
      suites: {}
    }

    Object.entries(this.testResults).forEach(([suite, result]) => {
      if (result) {
        summary.suites[suite] = {
          success: result.success,
          code: result.code
        }
        
        if (result.success) {
          summary.overall.passed++
        } else {
          summary.overall.failed++
        }
        summary.overall.total++
      }
    })

    return summary
  }

  displayResults() {
    console.log('\n🎯 TEST RESULTS SUMMARY')
    console.log('=' .repeat(60))
    
    Object.entries(this.testResults).forEach(([suite, result]) => {
      if (result) {
        const status = result.success ? '✅ PASSED' : '❌ FAILED'
        const suiteName = suite.replace(/([A-Z])/g, ' $1').toUpperCase()
        console.log(`${status} ${suiteName}`)
      }
    })
    
    const totalPassed = Object.values(this.testResults).filter(r => r && r.success).length
    const totalFailed = Object.values(this.testResults).filter(r => r && !r.success).length
    const totalRun = Object.values(this.testResults).filter(r => r).length
    
    console.log('\n📈 OVERALL RESULTS:')
    console.log(`  Total Suites: ${totalRun}`)
    console.log(`  Passed: ${totalPassed}`)
    console.log(`  Failed: ${totalFailed}`)
    console.log(`  Success Rate: ${totalRun > 0 ? Math.round((totalPassed / totalRun) * 100) : 0}%`)
    
    if (totalFailed === 0) {
      console.log('\n🎉 ALL TESTS PASSED! UI is ready for production!')
    } else {
      console.log('\n⚠️  Some tests failed. Check the detailed reports for more information.')
    }
  }

  async cleanup() {
    console.log('\n🧹 Cleaning up...')
    
    if (this.backendProcess) {
      this.backendProcess.kill('SIGTERM')
      console.log('  🛑 Backend server stopped')
    }
    
    if (this.frontendProcess) {
      this.frontendProcess.kill('SIGTERM')
      console.log('  🛑 Frontend server stopped')
    }
  }

  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms))
  }
}

// Run the test suite if this script is executed directly
if (require.main === module) {
  const runner = new UITestRunner()
  runner.run().catch(error => {
    console.error('Fatal error:', error)
    process.exit(1)
  })
}

module.exports = UITestRunner
