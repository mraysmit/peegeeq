# peegeeq-management-ui Commands Reference

All commands run from `peegeeq-management-ui/` unless stated otherwise.
Logs go to `../logs/` (the workspace-root `logs/` folder).

---

## Tests

### Unit tests (Vitest, no backend needed)
```powershell
cd peegeeq-management-ui
npm run test:run 2>&1 | Tee-Object -FilePath ..\logs\mgmt-unit-tests-20260603.txt
```

### E2E tests (Playwright, managed — starts backend automatically)
```powershell
npm run test:e2e 2>&1 | Tee-Object -FilePath ..\logs\mgmt-e2e-tests-20260603.txt
```

### All tests (unit + integration + e2e)
```powershell
npm run test:all 2>&1 | Tee-Object -FilePath ..\logs\mgmt-all-tests-20260603.txt
```

### Single Playwright spec
```powershell
npx playwright test src/tests/e2e/specs/take-screenshots.spec.ts --headed --reporter=list 2>&1 | Tee-Object -FilePath ..\logs\mgmt-screenshots-20260603.txt
```

### E2E tests direct (no backend management)
```powershell
npx playwright test --reporter=list 2>&1 | Tee-Object -FilePath ..\logs\mgmt-e2e-direct-20260603.txt
```

---

## Build

```powershell
cd peegeeq-management-ui
npm run build
```

---

## Dev server

```powershell
cd peegeeq-management-ui
npm run dev
```

---

## All npm test scripts (from package.json)

| Script | What it does |
|---|---|
| `test` | vitest watch mode |
| `test:run` | vitest one-shot (unit tests) |
| `test:coverage` | vitest with coverage report |
| `test:integration` | vitest integration tests only |
| `test:e2e` | node scripts/run-e2e-tests.js (manages backend) |
| `test:e2e:direct` | playwright test (no backend management) |
| `test:e2e:headed` | playwright test --headed |
| `test:e2e:debug` | playwright test --debug |
| `test:e2e:ui` | playwright test --ui |
| `test:e2e:report` | playwright show-report |
| `test:all` | test:run + test:integration + test:e2e |
| `test:ci` | test:run + test:integration + test:e2e (junit reporter) |

---

## Java backend tests (from workspace root)

> ⚠️ **This run takes more than 60 minutes.** Do not run it for single-module changes — use the single-module command above instead.

```powershell
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260603.txt
```

