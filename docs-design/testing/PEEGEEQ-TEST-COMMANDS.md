# PeeGeeQ Test Commands Quick Reference

---

## Profile Architecture (read this first)

Test-execution profiles are defined in **exactly one place**: the root `pom.xml`.
Module poms must NOT redeclare them. The previous per-module `activeByDefault`
profiles silently overrode root settings and caused tests to be skipped for
months — that architecture is gone.

The root pom provides these defaults (applied to every module automatically
when no `-P` is given):

| Property | Default value |
|---|---|
| `test.groups` | `core` |
| `test.excludedGroups` | `integration,performance,slow` |
| `test.parallel` | `methods` |
| `test.threadCount` | `4` |
| `peegeeq.performance.tests` | `false` |

So **`mvn test`** (no `-P`) = "run `@Tag("core")` tests, exclude
integration / performance / slow". There is no `core-tests` profile any
more — it would be redundant.

### Available profiles (root pom)

| Profile | `test.groups` | `test.excludedGroups` | Purpose |
|---|---|---|---|
| *(none)* | `core` | `integration,performance,slow` | Default. Fast dev loop. |
| `-Pintegration-tests` | `integration` | `performance,slow` | TestContainers / real infra |
| `-Pperformance-tests` | `performance` | *(empty)* | Throughput & load |
| `-Psmoke-tests` | `smoke` | `integration,performance,slow` | Ultra-fast E2E |
| **`-Pall-tests`** | *(empty)* | *(empty)* | **Single regression-safety profile — runs every test in every module** |
| `-Puntagged-tests` | *(empty)* | `core,integration,performance,slow,smoke` | Audit: finds tests missing `@Tag` |

---

## COPY-PASTE COMMANDS (update the date suffix before running)

```powershell
# Core tests — all modules (default, ~30s)
mvn test 2>&1 | Tee-Object -FilePath logs\core-tests-20260514.txt

# Core tests — single module
mvn test -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-core-20260514.txt

# Smoke tests — all modules (~20s)
mvn test -Psmoke-tests 2>&1 | Tee-Object -FilePath logs\smoke-tests-20260514.txt

# Integration tests — single module (~15m)
mvn test -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-integration-20260514.txt

# Integration tests — all modules (~60m)
mvn test -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20260514.txt

# Performance tests — single module (~30m)
mvn test -Pperformance-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-performance-20260514.txt

# Full suite — every tag, every module (~60m+) — THE regression-safety command
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260514.txt

# Audit — tests missing @Tag (should report Tests run: 0 if tagging is healthy)
mvn test -Puntagged-tests 2>&1 | Tee-Object -FilePath logs\untagged-audit-20260514.txt
```

**After the command finishes:**
```powershell
Get-Content logs\<name>.txt -Tail 30
```

---

**Platform**: Windows / PowerShell only. Always pipe with `Tee-Object`. Never use `Select-String` or `Select-Object -Last N` on the live Maven stream.
**Log naming**: `<description>-<YYYYMMDD>.txt`

> Run all Maven commands manually in the terminal. Do not ask Copilot to execute them — the agent tool has a ~60KB output cap and unreliable timeout behaviour.

---

## 1 Daily Development (run this constantly)

```powershell
mvn test 2>&1 | Tee-Object -FilePath logs\core-tests-20260514.txt
```

Single module (fastest feedback):
```powershell
mvn test -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-core-20260514.txt
```

---

## 2 Before Commit

```powershell
mvn test -Psmoke-tests 2>&1 | Tee-Object -FilePath logs\smoke-tests-20260514.txt
mvn test                2>&1 | Tee-Object -FilePath logs\core-tests-20260514.txt
```

---

## 3 Before Push / Integration Validation

Single module:
```powershell
mvn test -Pintegration-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-integration-20260514.txt
```

All modules (no `-pl` list required — the profile applies repo-wide):
```powershell
mvn test -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20260514.txt
```

---

## 4 Performance

```powershell
mvn test -Pperformance-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-performance-20260514.txt
```

The `peegeeq-performance-test-harness` module additionally provides workload-tuning
profiles (`-Pperformance`, `-Pload-test`, `-Pstress-test`) that adjust duration and
thread counts. These are orthogonal to the tag-filtering profiles above and can be
combined, e.g. `-Pperformance-tests,load-test`.

---

## 5 Full Suite (release / nightly / regression boundary)

```powershell
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260514.txt
```

`-Pall-tests` is the **single guarantee** that every test in every module
runs. If a test exists in the repo and a `mvn clean test -Pall-tests` invocation
does not execute it, that is a bug — file it. There is no longer any
per-module `activeByDefault` profile that can silently override the filters.

> **Use `clean`** for regression-safety runs. Maven's incremental compiler
> can leave stale synthetic inner classes (e.g. enum-switch `$1` SwitchMap
> classes) in `target/test-classes`, producing `NoClassDefFoundError` at
> runtime. `clean` removes that trap.

---

## 6 Tagging Audit

```powershell
mvn test -Puntagged-tests 2>&1 | Tee-Object -FilePath logs\untagged-audit-20260514.txt
```

Excludes all five known tag groups (`core`, `integration`, `performance`,
`slow`, `smoke`). Any test that runs under this profile is missing
`@Tag(...)` and is therefore invisible to the normal profiles. A healthy
repo reports `Tests run: 0` in every module.

---

## Module-Specific Notes

- **`peegeeq-runtime`**: surefire has no `<groups>` filter — runs every test on `mvn test`, regardless of tag. Intentional but inconsistent.
- **`peegeeq-rest-client`**: reads `${test.groups}` from root but has no module-local profile.
- **`peegeeq-management-ui`**: profiles in this module wire the frontend (`npm test`) scripts via `frontend-maven-plugin`. They intentionally share profile IDs with the root pom so they activate together. This is the **only** module besides root that declares `<id>core-tests</id>`, `<id>integration-tests</id>`, etc., and that is correct.
- **`peegeeq-migrations`**: has environment profiles (`local` / `test` / `production`), not tag-filter profiles. `mvn test` runs all tests here.
- **`peegeeq-pg-sidecar`**: provides a GraalVM `-Pnative` profile for native-image builds (unrelated to test filtering).
- **`peegeeq-openapi`**, **`peegeeq-coverage-report`**: no tests.

---

## How to Verify the Profile Architecture Is Healthy

```powershell
# 1. Confirm test.groups is empty under -Pall-tests for any module
mvn help:effective-pom -pl :peegeeq-db -Pall-tests 2>&1 |
    Select-String -Pattern "test\.groups|test\.excludedGroups"
# Expect: both properties present, both empty.

# 2. Confirm test.groups=core under default invocation
mvn help:effective-pom -pl :peegeeq-db 2>&1 |
    Select-String -Pattern "test\.groups|test\.excludedGroups"
# Expect: test.groups=core, test.excludedGroups=integration,performance,slow.

# 3. Confirm no Java module pom redeclares root profiles
Get-ChildItem -Recurse -Filter pom.xml |
    Select-String -Pattern "<id>(core-tests|integration-tests|performance-tests|smoke-tests|slow-tests|all-tests|untagged-tests)</id>"
# Expect: matches only in .\pom.xml (root) and .\peegeeq-management-ui\pom.xml (frontend wiring).
```

If any of these checks fail, the centralisation has been broken and tests
will silently be skipped under `mvn test -Pall-tests`.
