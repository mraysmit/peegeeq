# BiTemporal Module - Coverage Quick Start

## Current Status: 0% Coverage ❌

### Why Zero Coverage?

1. **Core Tests (2 tests)** - Run by default, but only check if classes exist
2. **Integration Tests (17 tests)** - Require Docker, not run by default
3. **No Unit Tests** - No tests that exercise actual business logic without infrastructure

---

## Quick Fix: Get Baseline Coverage

### Step 1: Start Docker Desktop ⚠️

**All integration tests require Docker to be running**

```powershell
# Check if Docker is running
docker ps

# If error, start Docker Desktop and wait for it to fully start
```

### Step 2: Run Integration Tests

```bash
cd peegeeq-bitemporal
mvn clean test -Pintegration-tests jacoco:report
```

**Expected Result**: 15-25% coverage from existing integration tests

### Step 3: View Coverage Report

```bash
# Open the report
start target/site/jacoco/index.html
```

---

## Project Structure

```
peegeeq-bitemporal/
├── src/main/java/dev/mars/peegeeq/bitemporal/
│   ├── PgBiTemporalEventStore.java          (1,760 lines) ⭐ Main implementation
│   ├── ReactiveNotificationHandler.java     (389 lines)
│   ├── VertxPoolAdapter.java                (242 lines)
│   ├── ReactiveUtils.java                   (156 lines)
│   └── BiTemporalEventStoreFactory.java     (155 lines)
│
└── src/test/java/dev/mars/peegeeq/bitemporal/
    ├── BiTemporalFactoryTest.java           (CORE - placeholder only)
    ├── TransactionParticipationTest.java    (CORE - placeholder only)
    ├── PgBiTemporalEventStoreIntegrationTest.java    (INTEGRATION - needs Docker)
    ├── PgBiTemporalEventStoreTest.java               (INTEGRATION - needs Docker)
    ├── ReactiveNotificationHandlerIntegrationTest.java (INTEGRATION - needs Docker)
    └── ... (14 more integration tests)
```

---

## Test Execution Profiles

### Core Tests (Default) - 0% Coverage
```bash
mvn clean test jacoco:report
# Runs: 2 tests (BiTemporalFactoryTest, TransactionParticipationTest)
# Time: ~5 seconds
# Coverage: 0% (placeholder tests don't exercise code)
```

### Integration Tests - 15-25% Coverage (requires Docker)
```bash
mvn clean test -Pintegration-tests jacoco:report
# Runs: 19 tests (all integration tests)
# Time: ~30-60 seconds
# Coverage: 15-25% (actual code execution)
# Requires: Docker Desktop running
```

### All Tests
```bash
mvn clean test -Pall-tests jacoco:report
# Runs: 21 tests (core + integration)
# Requires: Docker Desktop running
```

---

## Common Issues

### "Could not find a valid Docker environment" (Docker Desktop 4.52+ Bug)

**Problem**: Testcontainers getting HTTP 400 with empty JSON from Docker Desktop named pipe

**Root Cause**: Docker Desktop 4.52.0+ has a bug where it returns Status 400 with empty response when Testcontainers connects via `npipe://\\.\\pipe\\docker_cli`

**Solution (Enable TCP Daemon)**:
1. Open Docker Desktop
2. Go to Settings → General
3. Check "Expose daemon on tcp://localhost:2375 without TLS"
4. Click "Apply & Restart"
5. After restart, set environment variable before running tests:
   ```powershell
   $env:DOCKER_HOST="tcp://localhost:2375"
   mvn clean test -Pintegration-tests jacoco:report
   ```

**Alternative**: Downgrade to Docker Desktop 4.48 or earlier (doesn't have this bug)

### "Tests run: 19, Errors: 19"

**Problem**: Docker not available

**Solution**: See above

### Coverage Report Shows 0%

**Problem**: Running core tests only (default profile)

**Solution**: Use `-Pintegration-tests` profile instead

---

## Priority Actions

### Immediate (This Week)

1. ✅ Start Docker Desktop
2. ⏳ Run integration tests to establish baseline: `mvn clean test -Pintegration-tests jacoco:report`
3. ⏳ Review coverage report: `start target/site/jacoco/index.html`
4. ⏳ Convert placeholder tests to real unit tests (see improvement plan)

### Short Term (Next 2 Weeks)

1. Add 10-15 unit tests that don't require Docker
2. Focus on validation logic, factory methods, utility classes
3. Target: 30-40% coverage

### Medium Term (Next Month)

1. Add integration tests for uncovered paths
2. Test error scenarios and edge cases
3. Target: 70-80% coverage

---

## Key Files

- **Improvement Plan**: `docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md` (comprehensive strategy)
- **Coverage Report**: `target/site/jacoco/index.html` (after running tests)
- **Test Execution**: Use profiles (`-Pintegration-tests`, `-Pall-tests`)

---

## Quick Commands Reference

```bash
# Run core tests only (fast, 0% coverage)
mvn clean test

# Run integration tests (requires Docker, 15-25% coverage)
mvn clean test -Pintegration-tests jacoco:report

# View coverage report
start target/site/jacoco/index.html

# Check Docker status
docker ps

# Build entire module
mvn clean install -DskipTests
```

---

**Last Updated**: November 28, 2025  
**Status**: ⚠️ Docker Required for Meaningful Coverage
