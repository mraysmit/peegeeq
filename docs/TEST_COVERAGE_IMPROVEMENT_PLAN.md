# Test Coverage Improvement Plan for PeeGeeQ DB

## Executive Summary

**Current Coverage**: 21% instruction coverage (3,843 of 17,928 instructions)

**Root Cause**: Tests exist but are tagged as `INTEGRATION` tests, not `CORE` tests. When running `mvn test -Pcore-tests`, only CORE tests execute.

**Goal**: Improve coverage by:
1. Running integration tests to get baseline coverage
2. Adding CORE (unit) tests for packages with 0% coverage
3. Achieving 70%+ coverage for critical packages

---

## Current State Analysis

### Packages with 0% Coverage (Tests Exist but Tagged INTEGRATION)

| Package | Classes | Current Coverage | Test Files | Tag |
|---------|---------|------------------|------------|-----|
| `dev.mars.peegeeq.db.subscription` | 10 | 0% | 5 tests | INTEGRATION |
| `dev.mars.peegeeq.db.setup` | 5 | 0% | 2 tests | INTEGRATION |
| `dev.mars.peegeeq.db.health` | 10 | 0% | 1 test | INTEGRATION |
| `dev.mars.peegeeq.db.deadletter` | 4 | 0% | 2 tests | INTEGRATION |

### Packages with Good Coverage (CORE Tests)

| Package | Coverage | Status |
|---------|----------|--------|
| `dev.mars.peegeeq.db.resilience` | 93% | ✅ Excellent |
| `dev.mars.peegeeq.db.performance` | 76% | ✅ Good |
| `dev.mars.peegeeq.db.config` | 74% | ✅ Good |

---

## Phase 1: Baseline Coverage Assessment (Week 1)

### Objective
Run integration tests to understand actual coverage and identify gaps.

### Tasks

#### 1.1 Run Integration Tests with JaCoCo
```bash
# Run integration tests and generate coverage
mvn clean test -Pintegration-tests jacoco:report -pl :peegeeq-db

# View coverage report
start peegeeq-db/target/site/jacoco/index.html
```

**Expected Outcome**: Understand which code paths are covered by integration tests.

#### 1.2 Analyze Coverage Gaps
- Identify uncovered methods in each package
- Categorize gaps:
  - **Critical**: Core business logic, data integrity
  - **High**: Error handling, edge cases
  - **Medium**: Configuration, validation
  - **Low**: Getters, setters, simple utilities

#### 1.3 Document Findings
Create coverage gap analysis document with:
- Methods/classes with 0% coverage
- Complexity of adding tests (simple/medium/complex)
- Priority (critical/high/medium/low)

---

## Phase 2: Add CORE Unit Tests (Weeks 2-4)

### Objective
Add fast, isolated unit tests for packages with 0% coverage.

### 2.1 Subscription Package (Week 2)

**Target Coverage**: 70%+

**Classes to Test**:
1. `Subscription.java` - Data model
2. `SubscriptionStatus.java` - Enum/state management
3. `TopicConfig.java` - Configuration model
4. `TopicSemantics.java` - Enum/semantics

**Test Strategy**:
- **CORE Tests** (no database):
  - `SubscriptionTest.java` - Test object creation, validation, state transitions
  - `TopicConfigTest.java` - Test configuration validation, defaults
  - `TopicSemanticsTest.java` - Test semantic rules, validation

**Estimated Effort**: 2-3 days

### 2.2 Setup Package (Week 2)

**Target Coverage**: 60%+

**Classes to Test**:
1. `SqlTemplateProcessor.java` - Template processing logic
2. `DatabaseTemplateManager.java` - Template management

**Test Strategy**:
- **CORE Tests** (mock database):
  - `SqlTemplateProcessorTest.java` - Test template parsing, variable substitution
  - `DatabaseTemplateManagerTest.java` - Test template loading, caching

**Estimated Effort**: 2 days

### 2.3 Health Package (Week 3)

**Target Coverage**: 70%+

**Classes to Test**:
1. `HealthCheck.java` - Health check interface/model
2. `HealthStatus.java` - Status enum
3. `OverallHealthStatus.java` - Aggregation logic

**Test Strategy**:
- **CORE Tests** (mock dependencies):
  - `HealthCheckTest.java` - Test health check execution, timeout handling
  - `OverallHealthStatusTest.java` - Test status aggregation, degraded states

**Estimated Effort**: 2 days

### 2.4 Dead Letter Package (Week 3-4)

**Target Coverage**: 70%+

**Classes to Test**:
1. `DeadLetterMessage.java` - Data model
2. `DeadLetterQueueStats.java` - Statistics model

**Test Strategy**:
- **CORE Tests** (no database):
  - `DeadLetterMessageTest.java` - Test message creation, serialization
  - `DeadLetterQueueStatsTest.java` - Test statistics calculation, aggregation

**Estimated Effort**: 1-2 days

---

## Phase 3: Enhance Integration Tests (Week 5)

### Objective
Improve integration test coverage for complex scenarios.

### 3.1 Subscription Manager Integration Tests
- Add tests for concurrent subscription operations
- Test subscription cleanup on consumer failure
- Test zero-subscription validation edge cases

### 3.2 Setup Service Integration Tests
- Test schema migration scenarios
- Test rollback on setup failure
- Test idempotent setup operations

### 3.3 Health Check Integration Tests
- Test health checks under database failure
- Test health check timeout scenarios
- Test health check recovery

### 3.4 Dead Letter Queue Integration Tests
- Test DLQ overflow scenarios
- Test message replay from DLQ
- Test DLQ cleanup policies

**Estimated Effort**: 1 week

---

## Phase 4: Coverage Validation (Week 6)

### Objective
Validate coverage targets are met and tests are maintainable.

### 4.1 Run Full Test Suite with Coverage
```bash
# Run all tests (CORE + INTEGRATION)
mvn clean test -Pall-tests jacoco:report -pl :peegeeq-db

# Generate coverage report
mvn jacoco:report -pl :peegeeq-db
```

### 4.2 Validate Coverage Targets
- Overall coverage: 60%+
- Critical packages: 70%+
- All packages: >0%

### 4.3 Test Quality Review
- Ensure tests follow existing patterns
- Verify tests are deterministic (not flaky)
- Check test execution time (<30s for CORE, <15min for INTEGRATION)

---

## Implementation Guidelines

### Test Categorization Rules

```java
// CORE - Fast unit tests (<1s each, no external dependencies)
@Tag(TestCategories.CORE)
public class SubscriptionTest {
    // Mock all dependencies
    // Test business logic in isolation
}

// INTEGRATION - Real database tests (can be slow)
@Tag(TestCategories.INTEGRATION)
public class SubscriptionManagerIntegrationTest extends BaseIntegrationTest {
    // Use TestContainers
    // Test real database interactions
}
```

### Coverage Targets by Package Type

| Package Type | Target Coverage | Rationale |
|--------------|----------------|-----------|
| Core Business Logic | 80%+ | Critical for correctness |
| Data Access | 70%+ | Important for data integrity |
| Configuration | 70%+ | Prevents runtime errors |
| Utilities | 60%+ | Lower risk |
| Models/DTOs | 50%+ | Simple getters/setters |

---

## Success Metrics

### Quantitative
- ✅ Overall coverage: 60%+ (from 21%)
- ✅ Zero packages with 0% coverage (from 4 packages)
- ✅ CORE test execution time: <30 seconds
- ✅ INTEGRATION test execution time: <15 minutes

### Qualitative
- ✅ All critical business logic covered
- ✅ All error paths tested
- ✅ Tests are maintainable and follow patterns
- ✅ No flaky tests in CORE suite

---

## Next Steps

1. **Immediate**: Run integration tests to get baseline coverage
   ```bash
   mvn clean test -Pintegration-tests jacoco:report -pl :peegeeq-db
   ```

2. **Week 1**: Analyze coverage gaps and prioritize

3. **Weeks 2-4**: Implement CORE unit tests per package

4. **Week 5**: Enhance integration tests

5. **Week 6**: Validate and document final coverage

---

## Notes

- **Existing Tests**: Tests already exist but are INTEGRATION tests requiring TestContainers
- **Quick Win**: Running integration tests will immediately show higher coverage
- **Long-term**: Add CORE unit tests for fast feedback during development
- **Balance**: Maintain mix of fast CORE tests and comprehensive INTEGRATION tests

