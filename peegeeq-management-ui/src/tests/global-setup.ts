/**
 * Global setup to check if backend API is available before running tests.
 * This prevents running 178 tests that will all fail if the backend isn't running.
 */
async function globalSetup() {
  const API_BASE_URL = 'http://localhost:8080'
  
  console.log('\n🔍 Checking if PeeGeeQ backend is running...')
  
  try {
    const response = await fetch(`${API_BASE_URL}/health`, {
      method: 'GET',
      signal: AbortSignal.timeout(5000), // 5 second timeout
    })
    
    if (!response.ok) {
      console.error(`\n❌ Backend health check failed with status: ${response.status}`)
      console.error('   Please start the PeeGeeQ REST server on port 8080 before running e2e tests.')
      console.error('   Command: cd peegeeq-rest && mvn exec:java')
      process.exit(1)
    }
    
    console.log('✅ Backend is running and healthy\n')
  } catch (error) {
    console.error('\n❌ Cannot connect to PeeGeeQ backend at http://localhost:8080')
    console.error('   Error:', error instanceof Error ? error.message : String(error))
    console.error('\n   Please start the PeeGeeQ REST server before running e2e tests:')
    console.error('   1. cd peegeeq-rest')
    console.error('   2. mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.rest.PeeGeeQRestServer" -Dexec.args="8080"')
    console.error('\n   Or use the provided test scripts in the peegeeq-rest module.\n')
    process.exit(1)
  }
}

export default globalSetup
