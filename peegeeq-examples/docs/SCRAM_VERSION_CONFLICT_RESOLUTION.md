# SCRAM Version Conflict Resolution

## Problem

When integrating Spring Boot WebFlux with R2DBC and PeeGeeQ (Vert.x) in the same application, we encountered a SCRAM authentication library version conflict:

### Root Cause

Two different PostgreSQL drivers required incompatible versions of the SCRAM authentication library:

1. **R2DBC PostgreSQL 1.0.7.RELEASE** (managed by Spring Boot 3.3.5)
   - Requires: `com.ongres.scram:client:2.1` and `com.ongres.scram:common:2.1`
   - Used for reactive database access via R2DBC

2. **Vert.x pg-client 5.0.4** (used by PeeGeeQ)
   - Requires: `com.ongres.scram:scram-client:3.1` and `com.ongres.scram:scram-common:3.1`
   - Used for PeeGeeQ's reactive PostgreSQL operations

### Error Symptoms

```
java.lang.NoSuchMethodError: 'com.ongres.scram.client.ScramClient$PreBuilder1 
com.ongres.scram.client.ScramClient.channelBinding(com.ongres.scram.client.ScramClient$ChannelBinding)'
```

Even though the two SCRAM versions use different Maven artifact IDs (`client` vs `scram-client`), they share the same Java package names (`com.ongres.scram.*`) with incompatible method signatures, causing binary incompatibility at runtime.

## Solution

Configure PostgreSQL to use **MD5 authentication** instead of SCRAM-SHA-256, eliminating the need for SCRAM libraries at runtime.

### Implementation

In `SpringBootReactiveOutboxApplicationTest.java`:

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("peegeeq_reactive_test")
        .withUsername("test_user")
        .withPassword("test_password")
        .withSharedMemorySize(256 * 1024 * 1024L)
        // Use MD5 authentication to avoid SCRAM version conflicts
        .withEnv("POSTGRES_HOST_AUTH_METHOD", "md5")
        .withEnv("POSTGRES_INITDB_ARGS", "--auth-host=md5 --auth-local=md5")
        .withCommand("postgres", "-c", "password_encryption=md5");
```

### Key Configuration Parameters

1. **`POSTGRES_HOST_AUTH_METHOD=md5`** - Sets the default authentication method for host connections
2. **`POSTGRES_INITDB_ARGS=--auth-host=md5 --auth-local=md5`** - Configures `pg_hba.conf` during database initialization
3. **`password_encryption=md5`** - Ensures passwords are stored using MD5 hashing

## Dependency Configuration

Both SCRAM versions remain in the classpath but are not used at runtime:

```xml
<!-- R2DBC PostgreSQL Driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
    <!-- Brings in com.ongres.scram:client:2.1 -->
</dependency>

<!-- PeeGeeQ (includes Vert.x pg-client) -->
<dependency>
    <groupId>dev.mars</groupId>
    <artifactId>peegeeq-db</artifactId>
    <!-- Brings in com.ongres.scram:scram-client:3.1 -->
</dependency>
```

**No exclusions needed** - both versions coexist peacefully when MD5 authentication is used.

## Verification

Dependency tree shows both SCRAM versions present:

```
[INFO] dev.mars:peegeeq-examples:jar:1.0-SNAPSHOT
[INFO] +- dev.mars:peegeeq-db:jar:1.0-SNAPSHOT:compile
[INFO] |  \- io.vertx:vertx-pg-client:jar:5.0.4:compile
[INFO] |     \- com.ongres.scram:scram-client:jar:3.1:compile
[INFO] |        \- com.ongres.scram:scram-common:jar:3.1:compile
[INFO] \- org.postgresql:r2dbc-postgresql:jar:1.0.7.RELEASE:compile
[INFO]    \- com.ongres.scram:client:jar:2.1:compile
[INFO]       \- com.ongres.scram:common:jar:2.1:compile
```

## Test Results

All tests pass successfully:

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Security Considerations

**Note**: MD5 authentication is less secure than SCRAM-SHA-256. This configuration is appropriate for:
- Local development environments
- Integration testing with TestContainers
- Isolated test databases

**For production environments**, consider:
- Using a single PostgreSQL driver (either R2DBC or Vert.x, not both)
- Upgrading to compatible SCRAM versions when available
- Using connection pooling with separate authentication mechanisms
- Implementing network-level security (SSL/TLS, VPNs, firewalls)

## Alternative Solutions Considered

1. **Exclude SCRAM dependencies** - Failed due to missing classes at runtime
2. **Force single SCRAM version** - Failed due to binary incompatibility
3. **Upgrade R2DBC driver** - No newer version available with SCRAM 3.1 support
4. **Downgrade Vert.x** - Would lose features and bug fixes
5. **Classloader isolation/shading** - Too complex for test scenarios

## References

- [R2DBC PostgreSQL 1.0.7.RELEASE](https://mvnrepository.com/artifact/org.postgresql/r2dbc-postgresql/1.0.7.RELEASE)
- [Vert.x PostgreSQL Client 5.0.4](https://vertx.io/docs/vertx-pg-client/java/)
- [PostgreSQL Authentication Methods](https://www.postgresql.org/docs/current/auth-methods.html)
- [TestContainers PostgreSQL Module](https://www.testcontainers.org/modules/databases/postgres/)

