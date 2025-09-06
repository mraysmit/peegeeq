Based on the mistakes I made during this refactoring work, here are the key coding principles I would suggest:

## **Investigation Before Implementation**

### **Principle: "Understand Before You Change"**
- **My Mistake**: I initially added graceful error handling to `SystemPropertiesIntegrationTest` without investigating why it was failing
- **Better Approach**: Always investigate the root cause first
- **Code Practice**:
  ```java
  // BAD: Catching and hiding errors without understanding
  try {
      // database operation
  } catch (Exception e) {
      logger.warn("Expected failure, skipping...");
      return; // Hide the real problem
  }
  
  // GOOD: Investigate and fix the root cause
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
      .withDatabaseName("test_db");
  ```

## **Learn From Existing Patterns**

### **Principle: "Follow Established Conventions"**
- **My Mistake**: I didn't check how other integration tests in the project were structured
- **Better Approach**: Always examine existing patterns before creating new ones
- **Code Practice**:
  ```java
  // Research existing patterns first
  @Testcontainers  // ← Found this pattern in other tests
  class MyIntegrationTest {
      @Container
      static PostgreSQLContainer<?> postgres = // ← Consistent setup
  }
  ```

## **Verify Assumptions**

### **Principle: "Test Your Understanding"**
- **My Mistake**: I assumed the test was "working as intended" without carefully reading the logs
- **Better Approach**: Always verify that tests are actually doing what you think they're doing
- **Code Practice**:
  ```java
  // Don't assume - verify with explicit logging
  @Test
  void testDatabaseConnection() {
      logger.info("Testing with database: {}:{}", 
          postgres.getHost(), postgres.getFirstMappedPort());
      // Explicit verification of what's happening
  }
  ```

## **Precise Problem Identification**

### **Principle: "Fix the Cause, Not the Symptom"**
- **My Mistake**: I treated database connection failures as "expected behavior" instead of missing TestContainers setup
- **Better Approach**: Distinguish between legitimate failures and configuration issues
- **Code Practice**:
  ```java
  // BAD: Masking configuration problems
  if (databaseConnectionFailed) {
      logger.warn("Expected failure in test environment");
      return; // Wrong - this hides real issues
  }
  
  // GOOD: Proper test infrastructure
  @BeforeEach
  void configureDatabase() {
      System.setProperty("db.host", postgres.getHost());
      System.setProperty("db.port", String.valueOf(postgres.getFirstMappedPort()));
  }
  ```

## **Clear Documentation Standards**

### **Principle: "Document Intent, Not Just Implementation"**
- **My Mistake**: I wrote misleading comments about "expected behavior" when the real issue was missing setup
- **Better Approach**: Document the actual purpose and requirements
- **Code Practice**:
  ```java
  /**
   * Integration test that validates system properties with a real database.
   * Uses TestContainers to provide PostgreSQL for testing.
   * 
   * Requirements:
   * - Docker must be available for TestContainers
   * - Test validates actual database connectivity
   */
  @Testcontainers
  class SystemPropertiesIntegrationTest {
  ```

## **Iterative Validation**

### **Principle: "Validate Each Step"**
- **My Mistake**: I made multiple changes without validating each one individually
- **Better Approach**: Make small changes and verify each step works
- **Code Practice**:
  ```java
  // Step 1: Add TestContainers - verify it starts
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
  
  // Step 2: Configure connection - verify it connects
  System.setProperty("db.host", postgres.getHost());
  
  // Step 3: Test actual functionality - verify it works
  ```

## **Test Classification**

### **Principle: "Clearly Distinguish Test Types"**
- **My Mistake**: I confused integration tests (which should have real infrastructure) with unit tests (which can mock)
- **Better Approach**: Be explicit about what each test requires
- **Code Practice**:
  ```java
  // Unit Test - No external dependencies
  @ExtendWith(MockitoExtension.class)
  class ConfigurationUnitTest {
      @Mock private DatabaseService mockDb;
  }
  
  // Integration Test - Real infrastructure required
  @Testcontainers
  class ConfigurationIntegrationTest {
      @Container static PostgreSQLContainer<?> postgres;
  }
  
  Do not use use mockito without asking for permission.
  ```

## **Honest Error Handling**

### **Principle: "Fail Fast, Fail Clearly"**
- **My Mistake**: I tried to make tests "pass gracefully" when they should have been fixed to work properly
- **Better Approach**: Let tests fail when there are real problems, fix the problems
- **Code Practice**:
  ```java
  // BAD: Hiding real failures
  try {
      realOperation();
  } catch (Exception e) {
      logger.warn("Skipping due to environment");
      return; // Test "passes" but doesn't test anything
  }
  
  // GOOD: Proper setup so tests can succeed
  @Container
  static PostgreSQLContainer<?> postgres = // Provide real infrastructure
  ```

## **Log Analysis Skills**

### **Principle: "Read Logs Carefully"**
- **My Mistake**: I didn't carefully analyze what the error logs were actually telling me
- **Better Approach**: Parse error messages to understand the real problem
- **Code Practice**:
  ```java
  // When you see: "UnknownHostException: test-host"
  // Don't think: "Expected failure in test environment"
  // Think: "This test needs a real database host"
  
  // Solution: Provide the real host via TestContainers
  System.setProperty("db.host", postgres.getHost());
  ```

## **Summary: Core Principles**

1. **Investigate First**: Understand the problem before implementing solutions
2. **Follow Patterns**: Learn from existing code in the same project
3. **Verify Assumptions**: Don't assume tests are working - check the logs
4. **Fix Root Causes**: Address configuration issues, don't mask them
5. **Document Honestly**: Write comments that reflect actual behavior
6. **Validate Incrementally**: Test each change before moving to the next
7. **Classify Tests Clearly**: Know whether you're writing unit or integration tests
8. **Fail Honestly**: Let tests fail when there are real problems to fix
9. **Read Logs Carefully**: Error messages usually tell you exactly what's wrong

Do not reinvent the wheel as you are to do. Work incrementally and test after each small incremental change. When testing make sure you scan the test logs properly for test errors, do not rely on the exit code as that is largely meaningless. 