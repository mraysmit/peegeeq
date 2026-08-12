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

# Full suite resume from — every tag, every module (~90m) — explicit release GATE
mvn test -Pall-tests -rf :peegeeq-examples 2>&1 | Tee-Object -FilePath logs\all-tests-20260526.txt

# Full suite — every tag, every module (~90m) — explicit release GATE
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260526.txt

# Core tests — all modules (default, ~30s)
mvn test 2>&1 | Tee-Object -FilePath logs\core-tests-20260526.txt

# Core tests — single module
mvn test -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-core-20260526.txt

# Smoke tests — all modules (~20s)
mvn test -Psmoke-tests 2>&1 | Tee-Object -FilePath logs\smoke-tests-20260526.txt

# Integration tests — single module (~15m)
mvn test -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-integration-20260526.txt

# Integration tests — all modules (~60m)
mvn test -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20260526.txt

# Performance tests — single module (~30m)
mvn test -Pperformance-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-performance-20260526.txt

# Audit — tests missing @Tag (should report Tests run: 0 if tagging is healthy)
mvn test -Puntagged-tests 2>&1 | Tee-Object -FilePath logs\untagged-audit-20260526.txt
```

**After the command finishes:**
```powershell
Get-Content logs\<name>.txt -Tail 30
```

---

**Platform**: Windows / PowerShell only. Always pipe with `Tee-Object`. Never use `Select-String` or `Select-Object -Last N` on the live Maven stream.
**Log naming**: `<description>-<YYYYMMDD>.txt`

> **Who runs what.** The agent runs scoped verification itself — `-Dtest=<Class>` or a single
> module — after rebuilding the affected reactor slice. It reports the exact scope and
> per-class `Tests run:` lines. It must pipe through `Tee-Object` and read the saved log, not
> the live console. The approximately 90-minute `-Pall-tests` run stays with the owner or runs
> only when explicitly requested as a release gate.

---

## REQUIRED: rebuild before targeted verification

Every Java or Maven implementation change must be rebuilt and installed before targeted
tests run. Scope the rebuild to the changed module and its upstream reactor dependencies:

```powershell
# One changed module
mvn clean install -DskipTests -pl :peegeeq-db -am 2>&1 |
    Tee-Object -FilePath logs\rebuild-peegeeq-db-20260526.txt

# Multiple changed modules
mvn clean install -DskipTests -pl :peegeeq-db,:peegeeq-outbox -am 2>&1 |
    Tee-Object -FilePath logs\rebuild-db-outbox-20260526.txt
```

`-DskipTests` is allowed only for this rebuild/install prerequisite. It compiles test
sources but does not execute them. Run the targeted verification immediately afterward.
Never use `-Dmaven.test.skip=true` because it skips test compilation and can leave stale
test artifacts undiscovered.

---

## RULE: scoped runs to iterate, `-Pall-tests` to gate

**`-Pall-tests` takes approximately 90 minutes.** It is an explicit owner-run
commit / push / release gate, not a step in the edit-test loop. Normal verification uses
the smallest relevant method, class, or module after the required rebuild.

| Situation | Command |
|---|---|
| Writing a test, watching it fail, making it pass | The single test or class, scoped with `-pl` and `-Dtest=` |
| Iterating on a module you are changing | That module, with the profile carrying its test mass |
| Pre-change baseline | The smallest relevant classes or modules, with the profiles carrying their test mass |
| **Explicit commit / push / release gate** | **`mvn clean test -Pall-tests` — owner-run or explicitly requested** |
| A failure `-Pall-tests` already identified | That specific test, scoped, until it is green |

**What a scoped run is NOT.** It is evidence about the code you scoped it to, and nothing
else. The original failure this rule was written against was not "people ran fast tests" — it
was **partial results being reported as whole-repo validation**, so silently skipped tests went
unnoticed for months. That remains banned:

- Never describe a scoped run as "the suite passes" or "the build is green". Say what ran:
  *"`peegeeq-rest` integration: 504 passed"*.
- `mvn test -pl :module` (no profile) runs `@Tag("core")` ONLY. It will silently skip every
  integration test in that module. Always name the profile you used when reporting.
- A scoped green establishes only the named scope. It does not establish whole-repository
  health or replace an explicitly requested release gate.

---

## 0 Pre-change baseline

For a known class or method change, run that same targeted scope before and after the
change. For a broad module change, run the module profiles carrying the affected test
mass. This establishes the baseline without defaulting to the whole repository.

Most modules have both core and integration tests; some, including `peegeeq-db`, carry
almost no core-tagged tests. Always select the profile that contains the target test.

```powershell
# Example: D2.3 touches peegeeq-rest and peegeeq-db

# peegeeq-rest core (146 tests)
mvn test -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-core-20260613.txt

# peegeeq-rest integration
mvn test -Pintegration-tests -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-integration-20260613.txt

# peegeeq-db integration (727 tests — peegeeq-db has no meaningful core count)
mvn test -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-integration-20260613.txt
```

> **Always include `-Pintegration-tests` for integration baselines.** `mvn test -pl :module` (no profile) runs `@Tag("core")` only — it will silently skip all integration tests.

---

## 1 Targeted Core Debug (the iteration loop, and known-failure fixes)

Single module — fast feedback while writing core-tagged tests or fixing a known failure:
```powershell
mvn test -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-core-20260526.txt
```

---

## 2 Targeted Integration Debug (the iteration loop, and known-failure fixes)

Single module. Narrow to one class or method with `-Dtest=` while iterating —
`-Dtest=MyIntegrationTest` or `-Dtest=MyIntegrationTest#oneMethod`:
```powershell
mvn test -Pintegration-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-integration-20260526.txt
```

All modules (rarely needed — prefer `-Pall-tests`):
```powershell
mvn test -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20260526.txt
```

---

## 4 Performance

```powershell
mvn test -Pperformance-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-performance-20260526.txt
```

~~The `peegeeq-performance-test-harness` module additionally provides workload-tuning
profiles (`-Pperformance`, `-Pload-test`, `-Pstress-test`).~~
*(Deleted 2026-08-09: every figure that module reported was a hardcoded constant returned
after `Thread.sleep` — fabricated performance results, found by the metrics-stack review.
Real load tests are the `-Pperformance-tests`-tagged tests in the actual modules, e.g. the
peegeeq-db fanout suites.)*

---

## 5 Full Suite (release / nightly / regression boundary)

```powershell
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20260526.txt
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
mvn test -Puntagged-tests 2>&1 | Tee-Object -FilePath logs\untagged-audit-20260526.txt
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
