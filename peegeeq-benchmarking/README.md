# PeeGeeQ Benchmarking

This module is the single owner of PeeGeeQ performance workloads, benchmark support code,
hardware profiles, historical measurements, and retained benchmark evidence.

It follows the same separation used by the sister `peegee-cache-benchmarks` module: product
modules contain deployable behaviour, while a dedicated reactor module owns benchmark
orchestration, workload tests, measurement support, environment capture, and evidence.

## Contents

- `src/main/java/.../test/base` and `.../consumer` — reusable workload bases and scenarios
- `src/main/java/.../test/hardware` — in-memory host profiles and resource sampling
- `src/main/java/.../test/metrics` — snapshots, comparisons, and metric collection
- `src/main/java/.../test/persistence` — retained H2 history and analysis
- `src/main/java/.../db/performance` — standardized Markdown result generation
- `src/test/java` — the DB, native queue, outbox, bi-temporal, REST, and example workloads

Run the suite from the repository root:

```bash
mvn test -Pperformance-tests -pl :peegeeq-benchmarking -am
```

The normal build compiles these tests but excludes them. The `performance-tests` profile runs
them serially because concurrent benchmark classes would contend for the same host resources and
invalidate comparisons.

In Jenkins, select `performance`. Jenkins retains the console log, host baseline, periodic VM
statistics, Surefire reports, and every file written below `target/performance-results`, including
failed and aborted runs. Select `partitioned-release` for the dedicated one-hour partitioned
consumption release gate.

Hardware profiles and resource samples are benchmark evidence, not application data. They are
retained as files under `target/performance-results` and Jenkins artifacts. The benchmarking module
must not install tables or Flyway migrations in a PeeGeeQ PostgreSQL application schema. Historical
benchmark comparisons use the module-local embedded H2 store only; no database schema resources
are packaged with the module.

Runtime tuning code and deployable configuration remain in the product modules they configure.
Only measurement infrastructure and performance workloads belong here. Spring Boot benchmarks
remain in the intentionally standalone `peegeeq-examples-spring` build so Spring dependency
management cannot enter the core reactor.
