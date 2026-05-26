# Task: Fix peegeeq-management-ui Tests Not Running

## Status
Open

## Summary
The `peegeeq-management-ui` module tests are never executed during `mvn clean test -Pall-tests` runs.

---

## Root Cause Analysis

### Issue 1 — Module SKIPPED due to upstream failure (primary blocker)

In the May 22 build log (`logs/all-tests-20260522.txt`), `PeeGeeQ Native` **FAILED** after ~13m 44s. Maven's default fail-fast behaviour caused every subsequent module in the reactor to be marked **SKIPPED**, including `peegeeq-management-ui`.

The reactor order places management-ui after:
PeeGeeQ → Test Support → API → DB → Vert.x Outbox → Native → **[FAILED here]** → Bi-Temporal → Runtime → REST API → Client → Service Manager → PG Sidecar → Performance Test Harness → Examples → Examples Spring → Migrations → **Management UI** → OpenAPI → Integration Tests → Coverage Report

Fix the Native module failures first so the build reaches management-ui.

### Issue 2 — `all-tests` profile runs e2e tests that may not be viable in all environments

The `all-tests` profile in `peegeeq-management-ui/pom.xml` runs `npm run test:all`, which expands to:

```
npm run test:run          # vitest unit tests (2 tests — should always pass)
npm run test:integration  # vitest integration tests (--passWithNoTests, 0 tests)
npm run test:e2e          # Playwright e2e tests (16 specs)
```

The e2e step (`scripts/run-e2e-tests.js`):
1. Kills any process on port 3000
2. Starts a Vite dev server
3. Runs Playwright against it
4. Shuts the server down

This requires Playwright browser binaries to be installed. If they are not, the e2e step fails and Maven reports the whole module as a failure.

---

## Test inventory

| Type | Count | Tool | Glob |
|------|-------|------|------|
| Unit | 2 | vitest | `src/services/*.test.ts` |
| Integration | 0 | vitest | `src/tests/integration/` (empty) |
| E2E | 16 | Playwright | `src/tests/e2e/specs/*.spec.ts` |

Unit test files:
- `src/services/configService.test.ts`
- `src/services/websocketService.test.ts`

---

## Recommended Fix

### Option A — Change `all-tests` profile to run only vitest (safe, no Playwright dependency)

In `peegeeq-management-ui/pom.xml`, change the `all-tests` profile execution argument from:

```xml
<arguments>run test:all</arguments>
```

to:

```xml
<arguments>run test:run -- --run --reporter=verbose</arguments>
```

This runs only the vitest unit tests, consistent with what `smoke-tests` and `core-tests` already do. E2E tests would remain available via `npm run test:e2e` locally or via a dedicated e2e profile.

### Option B — Install Playwright browsers and keep `test:all`

Run once in the management-ui directory:

```powershell
cd peegeeq-management-ui
npx playwright install --with-deps
```

Then the full `test:all` (including e2e) will work.

---

## Immediate Verification Command

To test the management-ui in isolation (bypassing the Native failure):

```powershell
mvn clean test -Pall-tests -pl peegeeq-management-ui 2>&1 | Tee-Object -FilePath logs\all-tests-management-ui-20260521.txt
```

---

## Related Files

- `peegeeq-management-ui/pom.xml` — Maven profiles for test execution
- `peegeeq-management-ui/package.json` — npm test scripts
- `peegeeq-management-ui/vitest.config.ts` — vitest config (e2e excluded via `exclude` glob)
- `peegeeq-management-ui/playwright.config.ts` — Playwright config
- `peegeeq-management-ui/scripts/run-e2e-tests.js` — e2e orchestration script
- `logs/all-tests-20260522.txt` — build log showing SKIPPED at line 328067
