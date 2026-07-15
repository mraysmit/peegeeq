# PeeGeeQ Scripts

Utility scripts for the PeeGeeQ project, organized by purpose. Unless noted
otherwise, run them from the repository root; scripts that need the repo root
resolve it from their own location, so the working directory rarely matters.

## headers/

License and Javadoc header management for Java sources.

| Script | Purpose |
|---|---|
| `add-license-headers.ps1` | Insert the Apache 2.0 license header after each file's package declaration. `-Force` normalizes existing headers (placement, duplicates). Supports `-Path`, `-DryRun`, `-CopyrightYear`. |
| `update-java-headers.ps1` | Ensure license header plus a type-level Javadoc with `@author`. Supports `-Path`, `-DryRun`, `-CopyrightYear`. |
| `add-license-headers.sh`, `update-java-headers.sh` | Older Unix ports; not yet updated to match the PowerShell versions' behavior. |

## testing/

Test execution and one-off test analysis.

| Script | Purpose |
|---|---|
| `run-tests.sh` | Category-based test runner (`smoke`, `core`, `integration`, `performance`, `slow`, `all`), optionally scoped to a module. |
| `test-hardware-profiling.sh` | Hardware profiling test run. |
| `test-w3c-trace-context.ps1` / `.sh` | W3C trace-context integration tests against `peegeeq-rest`. |
| `find-tier1-db.ps1` | Find a specific `.onSuccess(...verify...)` test pattern in `peegeeq-db` tests. |

## test-tagging/

Tools for analyzing and applying JUnit `@Tag` annotations. Generated results
live in `test-tagging/output/`.

| Script | Purpose |
|---|---|
| `analyze-test-tags.ps1` | Report current `@Tag` usage across all test files. |
| `suggest-test-tags.ps1` | Suggest tags per test class, written to `output/test-tag-suggestions.csv`. |
| `apply-high-confidence-tags.ps1` | Apply HIGH-confidence suggestions from the CSV to test files. |

## logging-fixes/

One-off Python scripts used to add or fix lifecycle logging in test files.
Kept for reference; run from the repository root.

## local-infra/

Local development infrastructure definitions.

| File | Purpose |
|---|---|
| `docker-compose-local-dev.yml` | Single-node local PostgreSQL for development. |
| `docker-compose-failover-local.yml` | Primary/secondary PostgreSQL + HAProxy + PgBouncer failover stack. |
| `haproxy-failover-local.cfg` | HAProxy configuration for the failover stack. |
| `init-haproxy-check.sql` | Creates the `haproxy_check` user needed by HAProxy health checks. |

Start the dev database: `docker compose -f scripts/local-infra/docker-compose-local-dev.yml up -d`

## postgres-migration/

Scripts that migrate hardcoded PostgreSQL container versions to
`PostgreSQLTestConstants` (`migrate-postgresql-simple.ps1`,
`migrate-postgresql-versions.ps1` / `.sh` / `.bat`).

## git-hooks/

| Script | Purpose |
|---|---|
| `setup-git-hooks.sh` | Install pre-commit / pre-push / commit-msg hooks into `.git/hooks`. |
| `pre-commit-postgresql-check` | Standalone pre-commit hook blocking hardcoded PostgreSQL versions; copy to `.git/hooks/pre-commit`. |

## demo/

`run-self-contained-demo.sh` / `.bat` — build and run the self-contained
PeeGeeQ demo.
