# PeeGeeQ Test Commands Quick Reference

---

## COPY-PASTE COMMANDS (update the date suffix before running)

```powershell
# Core tests — all modules (~30s)
mvn test -Pcore-tests 2>&1 | Tee-Object -FilePath logs\core-tests-20260511.txt

# Core tests — single module (~30s)
mvn test -Pcore-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-core-20260511.txt

# Smoke tests — all modules (~20s)
mvn test -Psmoke-tests 2>&1 | Tee-Object -FilePath logs\smoke-tests-20260511.txt

# Integration tests — single module (~15m)
mvn test -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-integration-20260511.txt

# Integration tests — all modules (~60m)
mvn test -Pintegration-tests -pl :peegeeq-api,:peegeeq-db,:peegeeq-native,:peegeeq-bitemporal,:peegeeq-outbox,:peegeeq-runtime,:peegeeq-rest,:peegeeq-rest-client,:peegeeq-test-support,:peegeeq-service-manager,:peegeeq-performance-test-harness,:peegeeq-migrations,:peegeeq-examples,:peegeeq-examples-spring,:peegeeq-openapi,:peegeeq-integration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20260511.txt

# Performance tests — single module (~30m)
mvn test -Pperformance-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-performance-20260511.txt

# Full suite — all tags, all modules (~60m+)
mvn test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260511.txt
```

**After the command finishes (or after "Large tool result written to file" appears):**
```powershell
# Check if Maven is still running
Get-Process java

# Read the result when done
Get-Content logs\<name>.txt -Tail 30
```

---

**Platform**: Windows / PowerShell only. Always pipe with `Tee-Object`. Never use `Select-String` or `Select-Object`.  
**Log naming**: `<description>-<YYYYMMDD>.txt`

> **MANDATORY NO BACKGROUND/ASYNC MODE**: All Maven test commands MUST be run in foreground (`mode=sync`) using the `run_in_terminal` tool. Never use `mode=async`, background, or fire-and-forget execution for test runs. The `Tee-Object` pipe handles logging. Running in background hides output from the user and is forbidden regardless of how long the test run takes.

> **MANDATORY TIMEOUT VALUES for `run_in_terminal`**: Always set `timeout` large enough to cover the full expected run. Using no timeout is also acceptable for foreground sync runs. **Never set a timeout shorter than the expected duration** — when `run_in_terminal` times out it moves Maven to the background, which is forbidden. Required minimums:
> - `core-tests` / `smoke-tests`: `timeout=120000` (2 min)
> - `integration-tests` (single module): `timeout=900000` (15 min)
> - `integration-tests` (all modules): `timeout=3600000` (60 min)
> - `performance-tests`: `timeout=1800000` (30 min)
> - `all-tests` (full suite): `timeout=3600000` (60 min)

> **DO NOT RE-RUN AFTER "Large tool result written to file"**: The `run_in_terminal` tool stops returning inline output to the agent after ~60KB. This is a hard limit of the **tool display buffer only** — it is NOT a failure. Tee-Object has no limit. Maven and Tee-Object keep running to full completion in the terminal regardless. After the tool returns this message:
> 1. **Do NOT re-run the command.**
> 2. Use `Get-Process java` to check if Maven is still running.
> 3. Wait until `Get-Process java` returns nothing (Maven has finished).
> 4. Read the result: `Get-Content logs\<name>.txt -Tail 30`
> 5. The complete Surefire summary and `BUILD SUCCESS`/`FAILURE` will always be in the log file.

---

## 1 Daily Development (run this constantly)

```powershell
mvn test -Pcore-tests 2>&1 | Tee-Object -FilePath logs\core-tests-20260501.txt
```

Single module (fastest feedback):
```powershell
mvn test -Pcore-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-core-20260501.txt
```

---

## 2 Before Commit

```powershell
mvn test -Psmoke-tests 2>&1 | Tee-Object -FilePath logs\smoke-tests-20260501.txt
mvn test -Pcore-tests  2>&1 | Tee-Object -FilePath logs\core-tests-20260501.txt
```

---

## 3 Before Push / Integration Validation

Single module:
```powershell
mvn test -Pintegration-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-integration-20260501.txt
```

All 16 modules:
```powershell
mvn test -Pintegration-tests -pl :peegeeq-api,:peegeeq-db,:peegeeq-native,:peegeeq-bitemporal,:peegeeq-outbox,:peegeeq-runtime,:peegeeq-rest,:peegeeq-rest-client,:peegeeq-test-support,:peegeeq-service-manager,:peegeeq-performance-test-harness,:peegeeq-migrations,:peegeeq-examples,:peegeeq-examples-spring,:peegeeq-openapi,:peegeeq-integration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20260501.txt
```

---

## 4 Performance

```powershell
mvn test -Pperformance-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-performance-20260501.txt
```

---

## 5 Full Suite (release / nightly)

```powershell
mvn test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260501.txt
```

---

## Profile Reference

| Profile | What runs | Available in |
|---|---|---|
| `core-tests` | `@Tag("core")` ~30s | All modules (activeByDefault in most) |
| `smoke-tests` | `@Tag("smoke")` ~20s | All modules |
| `integration-tests` | `@Tag("integration")` 10-15m | All modules |
| `performance-tests` | `@Tag("performance")` 20-30m | All modules |
| `slow-tests` | `@Tag("slow")` 15+m | All **except** `peegeeq-native`, `peegeeq-db`, `peegeeq-bitemporal` |
| `all-tests` | All tags | All modules |

### Exceptions

- **`peegeeq-integration-tests`**: default profile is `smoke-tests`, not `core-tests`
- **`peegeeq-migrations`**: no profile filtering `mvn test -pl :peegeeq-migrations` runs all tests
- **`peegeeq-runtime`**: no profile filtering all tests run on `mvn test`
- **`peegeeq-openapi`**: no tests
