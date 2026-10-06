# PeeGeeQ Test Commands Quick Reference

**Status:** CURRENT COMMAND REFERENCE

**Last reconciled:** 2026-10-06 against commit f1c5d25d

---

## Profile Architecture (read this first)

Java test-execution profiles are defined in **exactly one place**: the root
`pom.xml`. Java module poms must NOT redeclare them. The previous per-Java-module
`activeByDefault` profiles silently overrode root settings and caused tests to be
skipped for months. That architecture is gone.

The two UI packaging modules are the deliberate exception: their matching profile
IDs wire npm commands into Maven rather than filtering JUnit tags. Each UI's
`core-tests` wiring is `activeByDefault`, so plain `mvn test` executes its Vitest
suite. Selecting another shared profile ID deactivates that default and executes
only the requested frontend test command.

The root pom provides these defaults (applied to every module automatically
when no `-P` is given):

| Property | Default value |
|---|---|
| `test.groups` | `core` |
| `test.excludedGroups` | `integration,performance,slow` |
| `test.parallel` | `methods` |
| `test.threadCount` | `4` |
| `peegeeq.performance.tests` | `false` |

So **`mvn test`** (no `-P`) = "run `@Tag("core")` Java tests, exclude
integration / performance / slow, and run both UI Vitest suites". There is no
root Java `core-tests` profile. It would be redundant. The UI-only profiles of
that name provide frontend lifecycle wiring, not Java tag selection.

### Available profiles (root pom)

| Profile | `test.groups` | `test.excludedGroups` | Purpose |
|---|---|---|---|
| *(none)* | `core` | `integration,performance,slow` | Default. Fast dev loop. |
| `-Pintegration-tests` | `integration` | `performance,slow` | TestContainers / real infra |
| `-Pperformance-tests` | `performance` | *(empty)* | Throughput & load |
| `-Psmoke-tests` | `smoke` | `integration,performance,slow` | Ultra-fast E2E |
| **`-Pall-tests`** | *(empty)* | *(empty)* | **Single regression-safety profile. Runs every test in every module. Owner-run release gate.** |
| `-Puntagged-tests` | *(empty)* | `core,integration,performance,slow,smoke` | Audit: finds tests missing `@Tag` |

---

## COPY-PASTE COMMANDS (update the date suffix before running)

```powershell

# Full suite resume from a module (~90m). Explicit release GATE, owner-run.
mvn test -Pall-tests -rf :peegeeq-examples 2>&1 | Tee-Object -FilePath logs\all-tests-20261006.log

# Full suite. Every tag, every module (~90m). Explicit release GATE, owner-run.
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20261006.log

# Core tests. All modules, including both UI Vitest suites (default, ~4m)
mvn test 2>&1 | Tee-Object -FilePath logs\core-tests-20261006.log

# Core tests. Single module
mvn test -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-core-20261006.log

# Smoke tests. All modules (~20s)
mvn test -Psmoke-tests 2>&1 | Tee-Object -FilePath logs\smoke-tests-20261006.log

# Integration tests. Single module (~15m)
mvn test -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-integration-20261006.log

# Integration tests. All modules (~60m)
mvn test -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20261006.log

# Performance tests. Consolidated benchmark module (~30m)
mvn test -Pperformance-tests -pl :peegeeq-benchmarking -am 2>&1 | Tee-Object -FilePath logs\peegeeq-benchmarking-performance-20261006.log

# Audit. Tests missing @Tag (should report Tests run: 0 if tagging is healthy)
mvn test -Puntagged-tests 2>&1 | Tee-Object -FilePath logs\untagged-audit-20261006.log
```

**After the command finishes:**
```powershell
Get-Content logs\<name>.log -Tail 30
```

---

**Platform**: Windows / PowerShell only. Always pipe with `Tee-Object`. Never use
`Select-String` or `Select-Object -Last N` on a live Maven stream. Save the stream to a
file first, then search the file.
**Log naming**: `<description>-<YYYYMMDD>.log`

> **Who runs what.** The agent runs scoped verification itself (`-Dtest=<Class>` or a single
> module) after rebuilding the affected reactor slice. It reports the exact scope and
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
    Tee-Object -FilePath logs\rebuild-peegeeq-db-20261006.log

# Multiple changed modules
mvn clean install -DskipTests -pl :peegeeq-db,:peegeeq-outbox -am 2>&1 |
    Tee-Object -FilePath logs\rebuild-db-outbox-20261006.log
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
| **Explicit commit / push / release gate** | **`mvn clean test -Pall-tests`, owner-run or explicitly requested** |
| A failure `-Pall-tests` already identified | That specific test, scoped, until it is green |

**What a scoped run is NOT.** It is evidence about the code you scoped it to, and nothing
else. The original failure this rule was written against was not "people ran fast tests". It
was **partial results being reported as whole-repo validation**, so silently skipped tests went
unnoticed for months. That remains banned:

- Never describe a scoped run as "the suite passes" or "the build is green". Say what ran,
  with the profile, the module, and the per-class `Tests run:` counts from the saved log.
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
# Example: a change touches peegeeq-rest and peegeeq-db

# peegeeq-rest core
mvn test -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-core-20261006.log

# peegeeq-rest integration
mvn test -Pintegration-tests -pl :peegeeq-rest 2>&1 | Tee-Object -FilePath logs\peegeeq-rest-integration-20261006.log

# peegeeq-db integration (peegeeq-db has no meaningful core count)
mvn test -Pintegration-tests -pl :peegeeq-db 2>&1 | Tee-Object -FilePath logs\peegeeq-db-integration-20261006.log
```

> **Always include `-Pintegration-tests` for integration baselines.** `mvn test -pl :module` (no profile) runs `@Tag("core")` only. It will silently skip all integration tests.

---

## 1 Targeted Core Debug (the iteration loop, and known-failure fixes)

Single module. Fast feedback while writing core-tagged tests or fixing a known failure:
```powershell
mvn test -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-core-20261006.log
```

---

## 2 Targeted Integration Debug (the iteration loop, and known-failure fixes)

Single module. Narrow to one class or method with `-Dtest=` while iterating:
`-Dtest=MyIntegrationTest` or `-Dtest=MyIntegrationTest#oneMethod`:
```powershell
mvn test -Pintegration-tests -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\peegeeq-outbox-integration-20261006.log
```

All modules is rarely needed. When a scoped integration run is too broad, narrow it further
with `-Dtest=`. Do not escalate to `-Pall-tests`; that is the owner-run release gate.
```powershell
mvn test -Pintegration-tests 2>&1 | Tee-Object -FilePath logs\integration-all-modules-20261006.log
```

---

## 3 Performance

```powershell
mvn test -Pperformance-tests -pl :peegeeq-benchmarking -am 2>&1 | Tee-Object -FilePath logs\peegeeq-benchmarking-performance-20261006.log
```

The former `peegeeq-performance-test-harness` module was deleted on 2026-08-09. Every
figure it reported was a hardcoded constant returned after a fixed thread delay. Real load
tests are consolidated in `peegeeq-benchmarking`; package names are retained so each
workload still identifies the product surface it exercises.

---

## 4 Full Suite (release / nightly / regression boundary)

```powershell
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-20261006.log
```

`-Pall-tests` is the **single guarantee** that every test in every module
runs. If a test exists in the repo and a `mvn clean test -Pall-tests` invocation
does not execute it, that is a bug. File it. There is no longer any Java-module
`activeByDefault` profile that can silently override the filters. The UI-only
defaults merely bind Vitest to plain `mvn test`; explicit `-Pall-tests` activation
replaces them with each UI's `npm-test-all` execution.

> **Use `clean`** for regression-safety runs. Maven's incremental compiler
> can leave stale synthetic inner classes (e.g. enum-switch `$1` SwitchMap
> classes) in `target/test-classes`, producing `NoClassDefFoundError` at
> runtime. `clean` removes that trap.

---

## 5 Tagging Audit

```powershell
mvn test -Puntagged-tests 2>&1 | Tee-Object -FilePath logs\untagged-audit-20261006.log
```

Excludes all five known tag groups (`core`, `integration`, `performance`,
`slow`, `smoke`). Any test that runs under this profile is missing
`@Tag(...)` and is therefore invisible to the normal profiles. A healthy
repo reports `Tests run: 0` in every module.

---

## 6 JDK toolchain

The root `pom.xml` sets `maven.compiler.release` to 25 (L77) and requests JDK 25 through
`maven-toolchains-plugin` 3.1.0 in `pluginManagement` (L418-436, `<jdk><version>25</version></jdk>`),
bound in `<build><plugins>` for every module (L556-560). Maven itself may run on any JDK;
compilation and test forks use the JDK that `~/.m2/toolchains.xml` registers for version 25.

A `~/.m2/toolchains.xml` entry of this shape is required. Replace `jdkHome` with the local
JDK 25 installation directory:

```xml
<toolchains>
    <toolchain>
        <type>jdk</type>
        <provides>
            <version>25</version>
        </provides>
        <configuration>
            <jdkHome>/path/to/jdk-25</jdkHome>
        </configuration>
    </toolchain>
</toolchains>
```

Without a matching entry the build fails at the `toolchain` goal with
`Cannot find matching toolchain definitions for the following toolchain types: jdk [ version='25' ]`.

The Jenkins pipeline enforces the same contract. `Jenkinsfile` L39-41 sets `JAVA_HOME` and
`PATH` to the Temurin 25 installation, and the `Environment` stage (L70-77) fails the build
unless `java` and `javac` resolve to that installation and `$HOME/.m2/toolchains.xml`
contains `<version>25</version>` and the matching `<jdkHome>`.

To verify locally, run one module with the toolchain goal and read the saved log:

```powershell
mvn -pl :peegeeq-api toolchains:toolchain 2>&1 | Tee-Object -FilePath logs\toolchain-20261006.log
# Expect: "Found matching toolchain for type jdk" naming the JDK 25 directory

mvn -pl :peegeeq-api help:effective-pom 2>&1 | Tee-Object -FilePath logs\effective-pom-api-20261006.log
# Then search the saved file for <release>25</release> and <version>25</version>
```

---

## 7 Management UI E2E tests

Playwright E2E tests for `peegeeq-management-ui` run against a real PostgreSQL container and
the real REST backend. No manual backend start is needed.

```powershell
cd peegeeq-management-ui
npm run test:e2e
```

What happens (file references are in `peegeeq-management-ui/`):

1. `npm run test:e2e` runs `scripts/run-e2e-tests.js` (`package.json` L17). The script kills
   any process listening on port 3000 (`run-e2e-tests.js` L18-59), then spawns
   `npx playwright test --headed --workers=1` (L68-72). Playwright starts the Vite dev server
   itself through the `webServer` block (`playwright.config.ts` L485-494).
2. Playwright global setup `src/tests/global-setup-testcontainers.ts`
   (`playwright.config.ts` L14) starts a fresh `postgres:15.13-alpine3.20` container
   (`global-setup-testcontainers.ts` L156-161), creates the `peegeeq` superuser (L176-181),
   and writes `testcontainers-db.json` to the module root (L228-229).
3. Global setup polls `http://127.0.0.1:8088/api/v1/health` (L254-262). If the backend is
   not running, or is pointed at a previous container port, or has stale CORS config, it
   spawns `mvn process-resources exec:java -pl peegeeq-rest` from the repository root with
   the container's connection details as `-DPEEGEEQ_DATABASE_*` properties (L311-345) and
   waits up to 30 s for health (L362-364). The backend log is `e2e-backend.log`. The REST
   port 8088 comes from `peegeeq-rest/src/main/resources/conf/rest-server.json` L2.
4. Global setup deletes every existing database setup through the API (L374-401) so the
   first project asserts a genuine empty state.
5. Projects run serially (`workers: 1`, `fullyParallel: false`; `playwright.config.ts`
   L18, L26). `0-setup-empty-state` is listed first with no dependencies (L82-86).
   `3c-setup-prerequisite` (L155-160) creates the default setup that most later projects
   declare as a dependency. Do not skip or reorder these two.
6. Global teardown (`global-teardown.ts`, `playwright.config.ts` L16) stops the auto-started
   backend and the container and removes `testcontainers-db.json`
   (`global-setup-testcontainers.ts` L416-465).

Optional manual backend start. `scripts/start-backend-with-testcontainers.ps1` reads
`testcontainers-db.json` (L7-14) and exits with an error if the file is absent. Use it ONLY
after a Playwright run has written that file, when you want to keep a backend running between
runs. Global setup detects a healthy backend on 8088 and leaves it running only if its
recorded DB port matches the current container (L271-283).

Variants (`package.json` L17-25): `npm run test:e2e:direct` runs Playwright without the port
cleanup; `npm run test:e2e:headed` adds `--headed` to a direct run; `npm run test:e2e:list`
lists projects; `npm run test:e2e:report` opens the HTML report.

---

## Module-Specific Notes

- **`peegeeq-runtime`**: surefire has no `<groups>` filter. It runs every test on `mvn test`, regardless of tag. Intentional but inconsistent.
- **`peegeeq-rest-client`**: reads `${test.groups}` from root but has no module-local profile.
- **`peegeeq-management-ui`** and **`peegeeq-utilities-ui`**: profiles in these packaging modules wire frontend npm scripts via `frontend-maven-plugin`. They share the root profile IDs `smoke-tests`, `integration-tests`, `performance-tests`, and `all-tests` so explicit profiles activate together. They also declare `core-tests` (`activeByDefault`, so plain `mvn test` cannot silently skip Vitest), `slow-tests`, and `e2e-tests` (Playwright, requires a running backend). The last three exist only in the UI poms.
- **`peegeeq-migrations`**: has environment profiles (`local` / `test` / `production`), not tag-filter profiles. `mvn test` runs all tests here.
- **`peegeeq-pg-sidecar`**: provides a GraalVM `-Pnative` profile for native-image builds (unrelated to test filtering).
- **`peegeeq-openapi`**, **`peegeeq-coverage-report`**: no tests.

---

## How to Verify the Profile Architecture Is Healthy

```powershell
# 1. Confirm test.groups is empty under -Pall-tests for any module
mvn help:effective-pom -pl :peegeeq-db -Pall-tests 2>&1 |
    Tee-Object -FilePath logs\effective-pom-db-all-tests-20261006.log
Select-String -Path logs\effective-pom-db-all-tests-20261006.log -Pattern "test\.groups|test\.excludedGroups"
# Expect: both properties present, both empty.

# 2. Confirm test.groups=core under default invocation
mvn help:effective-pom -pl :peegeeq-db 2>&1 |
    Tee-Object -FilePath logs\effective-pom-db-default-20261006.log
Select-String -Path logs\effective-pom-db-default-20261006.log -Pattern "test\.groups|test\.excludedGroups"
# Expect: test.groups=core, test.excludedGroups=integration,performance,slow.

# 3. Confirm no Java module pom redeclares root profiles; only the two UI
#    packaging modules may provide matching frontend-wiring profiles
Get-ChildItem -Recurse -Filter pom.xml |
    Select-String -Pattern "<id>(core-tests|integration-tests|performance-tests|smoke-tests|slow-tests|e2e-tests|all-tests|untagged-tests)</id>"
# Expect: matches only in .\pom.xml (root), .\peegeeq-management-ui\pom.xml,
# and .\peegeeq-utilities-ui\pom.xml (the latter two are frontend wiring).
```

If any of these checks fail, the centralisation has been broken and tests
will silently be skipped under `mvn test -Pall-tests`.
