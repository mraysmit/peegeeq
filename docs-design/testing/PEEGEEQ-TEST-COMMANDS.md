# PeeGeeQ Test Commands — Quick Reference

**Platform**: Windows / PowerShell only. Always pipe with `Tee-Object`. Never use `Select-String` or `Select-Object`.  
**Log naming**: `<description>-<YYYYMMDD>.txt`

---

## 1 — Daily Development (run this constantly)

```powershell
mvn test -Pcore-tests 2>&1 | Tee-Object -FilePath logs\core-tests-20260501.txt
```

Single module (fastest feedback):
```powershell
mvn test -Pcore-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-core-20260501.txt
```

---

## 2 — Before Commit

```powershell
mvn test -Psmoke-tests 2>&1 | Tee-Object -FilePath logs\smoke-tests-20260501.txt
mvn test -Pcore-tests  2>&1 | Tee-Object -FilePath logs\core-tests-20260501.txt
```

---

## 3 — Before Push / Integration Validation

Single module:
```powershell
mvn test -Pintegration-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-integration-20260501.txt
```

All 16 modules:
```powershell
mvn test -Pintegration-tests -pl :peegeeq-api,:peegeeq-db,:peegeeq-native,:peegeeq-bitemporal,:peegeeq-outbox,:peegeeq-runtime,:peegeeq-rest,:peegeeq-rest-client,:peegeeq-test-support,:peegeeq-service-manager,:peegeeq-performance-test-harness,:peegeeq-migrations,:peegeeq-examples,:peegeeq-examples-spring,:peegeeq-openapi,:peegeeq-integration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20260501.txt
```

---

## 4 — Performance

```powershell
mvn test -Pperformance-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-performance-20260501.txt
```

---

## 5 — Full Suite (release / nightly)

```powershell
mvn test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260501.txt
```

---

## Profile Reference

| Profile | What runs | Available in |
|---|---|---|
| `core-tests` | `@Tag("core")` — ~30s | All modules (activeByDefault in most) |
| `smoke-tests` | `@Tag("smoke")` — ~20s | All modules |
| `integration-tests` | `@Tag("integration")` — 10-15m | All modules |
| `performance-tests` | `@Tag("performance")` — 20-30m | All modules |
| `slow-tests` | `@Tag("slow")` — 15+m | All **except** `peegeeq-native`, `peegeeq-db`, `peegeeq-bitemporal` |
| `all-tests` | All tags | All modules |

### Exceptions

- **`peegeeq-integration-tests`**: default profile is `smoke-tests`, not `core-tests`
- **`peegeeq-migrations`**: no profile filtering — `mvn test -pl :peegeeq-migrations` runs all tests
- **`peegeeq-runtime`**: no profile filtering — all tests run on `mvn test`
- **`peegeeq-openapi`**: no tests
