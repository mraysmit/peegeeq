# PeeGeeQ Testing Guide

**Version:** 1.0  
**Date:** January 2026  
**Author:** Mark Andrew Ray-Smith, Cityline Ltd

---

## 1. Testing Philosophy

PeeGeeQ adopts a "Production-Grade Testing" philosophy. Since PeeGeeQ is a database-centric message queue, mocking the database is often insufficient for verifying correctness. Therefore, our testing strategy relies heavily on **TestContainers** to run tests against real, ephemeral PostgreSQL instances.

### The Testing Pyramid

1.  **Unit Tests**: Fast, isolated tests for logic that doesn't depend on the database (e.g., configuration parsing, utility classes).
2.  **Integration Tests (Backend)**: The core of our testing strategy. Uses TestContainers to verify interactions between the application and PostgreSQL.
3.  **Contract Tests**: Ensures API responses match defined JSON Schemas.
4.  **E2E Tests (UI)**: Uses Playwright to verify the Management UI against a running backend.
5.  **Performance Tests**: Dedicated harness for load and throughput testing.

---

## 2. Backend Testing (Java)

### Prerequisites
-   Docker (must be running for TestContainers)
-   JDK 21+
-   Maven

### Integration Tests (`peegeeq-integration-tests`)
This module contains the bulk of the system verification.

**Key Technologies:**
-   **JUnit 5**: Test runner.
-   **Vert.x JUnit 5**: Async testing support.
-   **TestContainers**: Spawns a fresh PostgreSQL container for each test class (or shared across a suite).
-   **RestAssured / Vert.x Web Client**: For testing HTTP endpoints.

**How to Run:**
```bash
# Run all integration tests
mvn test -pl peegeeq-integration-tests

# Run a specific test
mvn test -pl peegeeq-integration-tests -Dtest=EventVisualizationApiTest
```

### Contract Testing
We enforce strict API contracts using JSON Schema.
-   **Schemas**: Located in `peegeeq-management-ui/src/tests/fixtures/`.
-   **Backend Verification**: Java tests load these schemas and validate API responses.
-   **Frontend Verification**: UI tests validate mock data against the same schemas.

---

## 3. Frontend Testing (TypeScript/Playwright)

The Management UI is tested using **Playwright**, ensuring it works correctly with the real backend.

### Prerequisites
-   Node.js 20+
-   Docker

### E2E Testing Strategy
Our E2E tests can run in two modes:
1.  **Isolated**: Mocks API responses to test UI components in isolation (fast).
2.  **Full System**: Starts the real Backend and Database (via TestContainers) and runs tests against the full stack (comprehensive).

**How to Run:**
```bash
cd peegeeq-management-ui

# Run all E2E tests (headless)
npm run test:e2e:direct

# Run in headed mode (visible browser)
npm run test:e2e:headed

# Run with slow motion (for debugging)
# Windows (PowerShell)
$env:SLOW_MO=1000; npm run test:e2e:headed
# Linux/Mac
SLOW_MO=1000 npm run test:e2e:headed
```

---

## 4. Performance Testing

Located in `peegeeq-performance-test-harness`.
This module is designed to stress-test the system and measure throughput/latency.

**How to Run:**
Refer to the `README.md` inside the `peegeeq-performance-test-harness` module.

---

## 5. Continuous Integration

Our CI pipeline runs these tests in the following order:
1.  Unit Tests (Fast fail)
2.  Integration Tests (Backend verification)
3.  E2E Tests (Frontend verification)
