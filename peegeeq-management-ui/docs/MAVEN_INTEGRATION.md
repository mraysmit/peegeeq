# PeeGeeQ Management UI - Maven Integration

## Overview

The PeeGeeQ Management UI is a TypeScript/React/Vite project that has been integrated into the Maven build system using the `frontend-maven-plugin`. This allows running UI tests through Maven while maintaining the ability to use npm directly.

## Maven Integration Features

### Automated Node.js & npm Installation
- Maven automatically installs Node.js v20.11.0 and npm 10.2.4
- No manual Node.js installation required for CI/CD environments
- Installed locally in `peegeeq-management-ui/node/` directory

### Test Profile Mapping

The UI module integrates with PeeGeeQ's test categorization system:

| Maven Profile | npm Command | Test Type | Duration |
|--------------|-------------|-----------|----------|
| `smoke-tests` | `npm run test:run` | Vitest unit tests | ~10s |
| `core-tests` | `npm run test:run` | Vitest unit tests | ~10s |
| `integration-tests` | `npm run test:e2e` | Playwright E2E tests | ~5-10m |
| `performance-tests` | `npm run test:e2e` | Playwright E2E tests | ~5-10m |
| `slow-tests` | `npm run test:all` | All tests (unit + E2E) | ~10-15m |
| `all-tests` | `npm run test:all` | All tests (unit + E2E) | ~10-15m |

## Running Tests

### Using Maven (Recommended for CI/CD)

```bash
# Core/Smoke tests (Vitest unit tests)
mvn test -Pcore-tests -pl :peegeeq-management-ui
mvn test -Psmoke-tests -pl :peegeeq-management-ui

# Integration tests (Playwright E2E)
mvn test -Pintegration-tests -pl :peegeeq-management-ui

# All tests
mvn test -Pall-tests -pl :peegeeq-management-ui

# From root directory - all modules including UI
mvn test -Pcore-tests
```

### Using npm (Recommended for Development)

```bash
cd peegeeq-management-ui

# Unit tests
npm run test              # Watch mode
npm run test:run          # Run once
npm run test:ui           # UI mode
npm run test:coverage     # With coverage

# E2E tests
npm run test:e2e          # Run all E2E tests
npm run test:e2e:ui       # Playwright UI mode
npm run test:e2e:debug    # Debug mode
npm run test:e2e:headed   # Headed browser mode

# All tests
npm run test:all          # Unit + Integration + E2E
```

### Using Test Script

```bash
# Core tests for UI module only
./scripts/run-tests.sh core peegeeq-management-ui

# All modules including UI
./scripts/run-tests.sh core

# Integration tests for UI
./scripts/run-tests.sh integration peegeeq-management-ui
```

## Build Configuration

### Build Phase Behavior

- **Test Phase**: Runs tests without building the application
- **Package Phase**: Builds the application (currently skipped by default)
- **Compile Phase**: No compilation (skipped for test-only workflows)

This design allows fast test execution without the overhead of building the production bundle.

### Enabling Production Build

To enable production builds, modify `peegeeq-management-ui/pom.xml`:

```xml
<execution>
    <id>npm-build</id>
    <configuration>
        <arguments>run build</arguments>
        <skip>false</skip>  <!-- Change to false -->
    </configuration>
</execution>
```

## CI/CD Integration

### GitHub Actions Example

```yaml
- name: Run UI Tests
  run: mvn test -Pcore-tests -pl :peegeeq-management-ui

- name: Run UI E2E Tests
  run: mvn test -Pintegration-tests -pl :peegeeq-management-ui
```

### Jenkins Pipeline Example

```groovy
stage('UI Tests') {
    steps {
        sh 'mvn test -Pcore-tests -pl :peegeeq-management-ui'
    }
}
```

## Module Structure

```
peegeeq-management-ui/
├── pom.xml                    # Maven configuration
├── package.json               # npm configuration
├── node/                      # Maven-installed Node.js (auto-generated)
├── node_modules/              # npm dependencies
├── src/                       # Source code
│   ├── components/
│   ├── pages/
│   ├── tests/
│   └── ...
├── playwright.config.ts       # Playwright E2E configuration
├── vitest.config.ts          # Vitest unit test configuration
└── vite.config.ts            # Vite build configuration
```

## Benefits

### For Developers
- **Flexibility**: Use npm for fast development, Maven for consistency
- **No Setup**: Maven handles Node.js/npm installation automatically
- **Familiar Tools**: Continue using npm commands during development

### For CI/CD
- **Unified Interface**: Single Maven command for all modules
- **Consistent Execution**: Same test categorization as Java modules
- **Automated Setup**: No manual Node.js installation in CI environments
- **Profile-Based Control**: Easy integration with existing Maven pipelines

## Troubleshooting

### Tests Not Running
```bash
# Verify Maven recognizes the module
mvn clean -pl :peegeeq-management-ui

# Check npm installation
cd peegeeq-management-ui
npm install
npm run test:run
```

### Node.js Version Issues
The plugin installs Node.js v20.11.0 locally. To change versions, edit `pom.xml`:
```xml
<properties>
    <node.version>v20.11.0</node.version>
    <npm.version>10.2.4</npm.version>
</properties>
```

### Build Failures
If TypeScript compilation fails during Maven build, the build phase is skipped by default during tests. For production builds, fix TypeScript errors first.

## Notes

- The UI module is now included in the parent `pom.xml` modules list
- The test script (`scripts/run-tests.sh`) includes `peegeeq-management-ui` in the module list
- npm remains the primary tool for development; Maven is for CI/CD consistency
- E2E tests require a running backend (see `docs/TEST_DATA_SETUP.md`)

