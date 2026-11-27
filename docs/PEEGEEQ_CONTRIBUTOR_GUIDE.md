# PeeGeeQ Contributor Guide
© Mark Andrew Ray-Smith Cityline Ltd 2025  
Version 2.0

This guide states the working sequence for a change to PeeGeeQ and names the documents that own the rules.
It does not restate those rules. Where this guide and a named standard differ, the standard is correct and
this guide is a defect.

## 1. Normative sources

| Subject | Document |
|---|---|
| Coding rules, Vert.x 5 Future patterns, banned patterns, transactions, schema isolation | Coding principles (`docs-design/dev/pgq-coding-principles.md`) |
| Test rules and antipatterns | Testing standards and antipatterns (`docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md`) |
| Approved test structure | Testing patterns (`docs-design/testing/PEEGEEQ_TESTING_STANDARDS_PATTERNS.md`) |
| Guard tests and their baselines | Test guard (`docs-design/testing/PEEGEEQ_TEST_GUARD.md`) |
| Maven commands, profiles, JDK toolchain, UI end-to-end tests, Jenkins suites | Test commands (`docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`) |
| Working rules for coding agents | `.claude/CLAUDE.md` |
| Task status and the defect register | `docs-design/tasks/tasks.md` |

Copy Maven commands from the test-commands document. Do not compose them from memory.

## 2. Working sequence

1. Read the coding principles and the testing standards in full.
2. Read every file you will change, and the existing tests for that code in the same module. Follow their
   pattern.
3. Search each file you will change for the banned patterns listed in the coding principles, section 4.
   Report any hit you find. Do not copy it.
4. Write a failing test first. Include one test for each failure mode of each dependency the code calls.
5. Make one bounded change.
6. Rebuild the changed module and its upstream modules:

   ```powershell
   mvn clean install -DskipTests -pl ":<module>" -am 2>&1 |
       Tee-Object -FilePath logs\rebuild-<module>-<YYYYMMDD>.log
   ```

   `-DskipTests` is permitted only in this step. It compiles the tests and does not run them. `logs\`
   is ignored by git; create it with `New-Item -ItemType Directory -Force logs` if it is absent.
7. Run the smallest relevant test scope with the profile that carries the test's tag:

   ```powershell
   mvn test "-Pintegration-tests" -pl ":<module>" "-Dtest=<TestClass>" 2>&1 |
       Tee-Object -FilePath logs\<module>-<TestClass>-<YYYYMMDD>.log
   ```

8. Read the per-class `Tests run:` lines in the saved log. `Tests run: 0` is a failure: the profile does not
   match the tag, or the class did not start. `BUILD SUCCESS` alone is not evidence.
9. Report the exact scope, the profile, and the per-class counts. A scoped run is evidence for that scope
   only.

The full `-Pall-tests` run is a release gate that the owner runs or requests explicitly. It is not part of
the edit-test loop.

## 3. Toolchain

- JDK 27, registered in `~/.m2/toolchains.xml`. The root `pom.xml` sets `maven.compiler.release` to 27 and
  binds `maven-toolchains-plugin` for every module. Without a matching toolchain entry the build fails at the
  `toolchain` goal. The entry and a check command are in section 6 of the test-commands document.
- Maven 3.8.0 or later, enforced by `maven-enforcer-plugin` in the root `pom.xml`.
- Docker. Database tests use Testcontainers; there is no in-memory substitute.
- Node v22.12.0 and npm 10.2.4, pinned in `peegeeq-management-ui/pom.xml` and `peegeeq-utilities-ui/pom.xml`
  and installed by `frontend-maven-plugin` during the Maven build.
- Windows 11 and PowerShell. Pipe every Maven run through `Tee-Object` and read the saved file, not the live
  console.

`peegeeq-examples-spring` is a standalone Maven project with its own parent. It is not a module of the root
reactor and does not inherit the root test profiles. Its `pom.xml` sets `java.version` to 27.

## 4. Test categories and profiles

Every test class carries a tag from `TestCategories` in `peegeeq-test-support`.

- `CORE`: no database operation. Runs under plain `mvn test`.
- `INTEGRATION`: any test that touches the database. It uses a real PostgreSQL container from the
  test-support factories and runs under `-Pintegration-tests`.
- `PERFORMANCE` and `SMOKE`: selected by their own profiles.
- `SLOW`: no root profile selects it alone; it runs under `-Pall-tests`.

Mockito, other mocking libraries, and in-memory databases are banned. The root `pom.xml` is the only place
that defines the Java test profiles. With no `-P` argument, `test.groups` is `core` and
`test.excludedGroups` is `integration,performance,slow`.

| Profile | Selects |
|---|---|
| *(none)* | `core` |
| `integration-tests` | `integration` |
| `performance-tests` | `performance` |
| `smoke-tests` | `smoke` |
| `all-tests` | every test, tagged or untagged, in every module |
| `untagged-tests` | tests with none of the five tags; a healthy tree reports `Tests run: 0` |
| `coverage` | turns JaCoCo on; combine it with a test profile |

The module-specific exceptions are in the "Module-Specific Notes" of the test-commands document. For
example, `peegeeq-runtime` runs every test on `mvn test`, and `peegeeq-migrations` has environment profiles
instead of tag profiles.

The six guard tests in `peegeeq-test-support/src/test/java/dev/mars/peegeeq/test/quality/` are `CORE` and run
on every `mvn test`. Fix the code that a guard rejects. Do not relax the guard.

Management UI tests: plain `mvn test` runs the Vitest suites of both UI modules. The Playwright suite runs
with `npm run test:e2e` in `peegeeq-management-ui`; it starts its own PostgreSQL container and REST server.
`PEEGEEQ_GETTING_STARTED.md` and section 7 of the test-commands document describe
it.

## 5. Continuous integration

Jenkins is the only active CI. The pipeline is the `Jenkinsfile` at the repository root. The GitHub Actions
workflow is disabled: its file is `.github/workflows/build.yml.disabled`.

The pipeline runs on an agent labelled `peegeeq-linux`. Its `Environment` stage fails unless `java` and
`javac` resolve to the Temurin 27 installation and `~/.m2/toolchains.xml` names it. Every suite first runs
`mvn clean install -DskipTests`. The `TEST_SUITE` parameter selects one of seven suites: `core`, `smoke`,
`integration`, `untagged`, `performance`, `partitioned-release`, `all`. Section 8 of the test-commands
document maps each suite to its Maven command.

Host setup and operation:

- Jenkins CI on VMware ESXi (`docs-design/dev/PEEGEEQ_JENKINS_ESXI_CI_SETUP.md`)
- Password-free SSH from WSL to the Linux VM (`docs-design/dev/PEEGEEQ_WSL_PASSWORDLESS_SSH_SETUP.md`)

These documents describe one environment. They do not require a PeeGeeQ deployment to use Jenkins or ESXi.

## 6. Documentation

The rules for documents in `docs/` are in `README.md`. One document owns each fact, and other
documents refer to it by name. Status lives in the task register (`docs-design/tasks/tasks.md`). A code defect
found while writing a document goes into the defect table of the task register; the document states what
the code does and names the defect.
