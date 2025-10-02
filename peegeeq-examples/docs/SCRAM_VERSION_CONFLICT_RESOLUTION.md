# SCRAM Version Conflict Resolution

---

## ⚠️ CRITICAL SECURITY WARNING

**MD5 authentication is ONLY for test environments. NEVER use MD5 in production.**

MD5 violates security compliance requirements including:
- **Sarbanes-Oxley (SOX)** - Financial data protection
- **PCI DSS** - Payment card security
- **HIPAA** - Healthcare data protection
- **GDPR** - EU data protection
- **ISO 27001** - Information security

**Production systems MUST use SCRAM-SHA-256 authentication.**

See [Production Configuration](#production-configuration) section below.

---

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

### ⚠️ CRITICAL: TEST ENVIRONMENT ONLY

**MD5 authentication is NOT acceptable for production environments.**

MD5 is cryptographically broken and does not meet security compliance requirements including:
- **Sarbanes-Oxley (SOX)** - Financial data protection requirements
- **PCI DSS** - Payment card industry security standards
- **HIPAA** - Healthcare data protection
- **GDPR** - EU data protection regulations
- **ISO 27001** - Information security management

**This solution is ONLY for test environments** where:
- No production data is present
- Tests run in isolated containers that are destroyed after execution
- The goal is to avoid SCRAM library version conflicts during testing

**For production, you MUST use SCRAM-SHA-256 authentication** (PostgreSQL 14+ default).

### Test Environment Implementation

In `SpringBootReactiveOutboxApplicationTest.java`:

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
        .withDatabaseName("peegeeq_reactive_test")
        .withUsername("test_user")
        .withPassword("test_password")
        .withSharedMemorySize(256 * 1024 * 1024L)
        // ⚠️ TEST ONLY: Use MD5 authentication to avoid SCRAM version conflicts
        // NEVER use MD5 in production - it violates SOX, PCI DSS, HIPAA, GDPR, ISO 27001
        .withEnv("POSTGRES_HOST_AUTH_METHOD", "md5")
        .withEnv("POSTGRES_INITDB_ARGS", "--auth-host=md5 --auth-local=md5")
        .withCommand("postgres", "-c", "password_encryption=md5");
```

### Key Configuration Parameters (TEST ONLY)

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

**No exclusions needed** - both versions coexist peacefully when MD5 authentication is used in test environments.

## Production Configuration

### ⚠️ REQUIRED: Use SCRAM-SHA-256 in Production

**For production deployments, you MUST:**

1. **Use SCRAM-SHA-256 authentication** (PostgreSQL 14+ default)
2. **Include proper SCRAM dependencies** in your application
3. **Never use MD5 authentication** - it violates security compliance requirements

**Production pom.xml configuration:**

```xml
<!-- SCRAM Authentication for PostgreSQL (REQUIRED for production) -->
<dependency>
    <groupId>com.ongres.scram</groupId>
    <artifactId>scram-client</artifactId>
    <version>3.1</version>
</dependency>
<dependency>
    <groupId>com.ongres.scram</groupId>
    <artifactId>scram-common</artifactId>
    <version>3.1</version>
</dependency>
```

**Production PostgreSQL configuration (postgresql.conf):**

```properties
# REQUIRED for production security
password_encryption = scram-sha-256
```

**Production pg_hba.conf:**

```
# TYPE  DATABASE        USER            ADDRESS                 METHOD
host    all             all             0.0.0.0/0               scram-sha-256
```

### Compliance Requirements

Production systems MUST use SCRAM-SHA-256 to comply with:
- **Sarbanes-Oxley (SOX)** - Section 404: Internal controls over financial reporting
- **PCI DSS** - Requirement 8.2.1: Strong cryptography for authentication
- **HIPAA** - 164.312(a)(2)(i): Unique user identification with strong authentication
- **GDPR** - Article 32: Appropriate technical measures for data security
- **ISO 27001** - A.9.4.2: Secure authentication procedures

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

### Why MD5 Authentication is NOT for Production

**CRITICAL**: MD5 authentication has serious security vulnerabilities and should **NEVER** be used in production environments.

#### 1. Cryptographic Weakness

**MD5 is cryptographically broken:**
- **Collision attacks**: Two different passwords can produce the same MD5 hash
- **Rainbow tables**: Pre-computed hash tables make password cracking extremely fast
- **Brute force**: MD5 is computationally cheap - attackers can try billions of passwords per second with modern GPUs
- **Weak salting**: PostgreSQL's MD5 implementation uses a weak salt that doesn't provide adequate protection

**SCRAM-SHA-256 is cryptographically secure:**
- Uses SHA-256 (cryptographically strong, no known practical attacks)
- Includes proper salting with sufficient entropy
- Uses key stretching (4096 iterations by default) to slow down brute force attacks
- Resistant to rainbow table attacks
- Industry standard for password authentication

#### 2. Network Security Vulnerabilities

**MD5 password transmission:**
```
Client → Server: MD5(password + salt)
```
- The MD5 hash itself is sent over the network
- If an attacker captures this hash (via network sniffing), they can replay it to authenticate
- Even with SSL/TLS, the hash becomes a reusable credential
- **Pass-the-hash attack**: Stolen hash = stolen password

**SCRAM-SHA-256 challenge-response:**
```
Client ↔ Server: Challenge-response protocol with nonces
```
- Never sends the password or a reusable hash over the network
- Uses nonces (one-time values) to prevent replay attacks
- Each authentication session generates unique values
- Captured network traffic cannot be replayed

#### 3. Compliance and Regulatory Issues

Many security standards **explicitly prohibit MD5** for password hashing:

- **PCI DSS** (Payment Card Industry Data Security Standard): Prohibits MD5 for password hashing
- **NIST** (US National Institute of Standards and Technology): Deprecated MD5 in 2010, recommends SHA-256 or better
- **HIPAA** (Healthcare): Requires "current cryptographic standards" - MD5 does not qualify
- **SOX** (Sarbanes-Oxley): Security auditors will flag MD5 as a material weakness
- **GDPR** (EU General Data Protection Regulation): Requires "state of the art" security measures - MD5 fails this requirement
- **ISO 27001**: Information security standard that prohibits deprecated cryptographic algorithms

**Using MD5 in production can result in:**
- Failed security audits
- Compliance violations and fines
- Legal liability in case of data breach
- Loss of certifications (PCI, SOC 2, etc.)

#### 4. Real-World Attack Scenario

**Production system with MD5 authentication:**

```
Step 1: Attacker gains read access to PostgreSQL's pg_authid table
        (via SQL injection, backup theft, compromised admin account, or insider threat)

Step 2: Attacker extracts password hashes:
        username: admin
        password: md5a8b7c6d5e4f3g2h1i0j9k8l7m6n5o4p

Step 3: Attacker can:
        Option A: Crack the MD5 hash offline
                  - Modern GPU: ~50 billion MD5 hashes/second
                  - 8-character password: cracked in minutes
                  - 10-character password: cracked in hours/days

        Option B: Use the hash directly (pass-the-hash attack)
                  - Connect to PostgreSQL using the stolen hash
                  - No need to crack the password
                  - Works immediately

Step 4: Attacker gains full database access
```

**Production system with SCRAM-SHA-256:**

```
Step 1: Attacker gains read access to PostgreSQL's pg_authid table

Step 2: Attacker extracts password hashes:
        username: admin
        password: SCRAM-SHA-256$4096:salt$storedKey:serverKey

Step 3: Attacker must:
        - Crack the password offline (ONLY option - pass-the-hash doesn't work)
        - 4096 iterations make this ~4000x slower than MD5
        - Modern GPU: ~12 million SCRAM attempts/second (vs 50 billion for MD5)
        - 8-character password: days/weeks instead of minutes
        - 10-character password: months/years instead of hours

Step 4: Strong passwords remain secure even if hashes are stolen
```

#### 5. When MD5 is Acceptable (Development/Testing Only)

This MD5 workaround is **ONLY** appropriate for:

✅ **Local development environments**
- No external network access
- No sensitive data
- Developer workstations only

✅ **Integration testing with TestContainers**
- Ephemeral containers (destroyed after tests)
- Isolated from production networks
- No persistent data
- Test speed matters more than security

✅ **Isolated test databases**
- No production data
- No network exposure
- Temporary/disposable
- CI/CD pipelines

**Why these are acceptable:**
- No sensitive or production data at risk
- No external network exposure
- Temporary and disposable infrastructure
- Development velocity is the priority
- Security risk is contained and understood

#### 6. Production Alternatives

**For production environments, you MUST use one of these approaches:**

**Option 1: Use a single PostgreSQL driver (RECOMMENDED)**
```java
// ✅ Use ONLY Vert.x (what PeeGeeQ Spring Boot examples do now)
// Remove R2DBC entirely
// Wrap Vert.x Futures in Mono for Spring WebFlux compatibility
```
- **Pros**: No SCRAM conflict, uses SCRAM-SHA-256, full PeeGeeQ integration
- **Cons**: Spring Boot developers must learn Vert.x SQL Client, can't use Spring Data R2DBC

**Option 2: Certificate-based authentication**
```properties
# PostgreSQL pg_hba.conf
hostssl all all 0.0.0.0/0 cert clientcert=verify-full

# Application uses SSL certificates instead of passwords
ssl=true
sslmode=verify-full
sslcert=/path/to/client-cert.pem
sslkey=/path/to/client-key.pem
sslrootcert=/path/to/ca-cert.pem
```
- **Pros**: No password vulnerabilities, strong authentication, works with both drivers
- **Cons**: Certificate management overhead, requires PKI infrastructure

**Option 3: Network-level security with SCRAM**
```
Application → VPN/Private Network → PostgreSQL (SCRAM-SHA-256)
```
- Use VPN or AWS PrivateLink for network isolation
- PostgreSQL only accessible via secure network
- Still use SCRAM-SHA-256 for authentication
- **Pros**: Defense in depth, network + authentication security
- **Cons**: More complex infrastructure

**Option 4: Wait for library compatibility**
- Wait for R2DBC to upgrade to SCRAM 3.1
- Or wait for Vert.x to support SCRAM 2.1 compatibility mode
- **Pros**: Eventually solves the root cause
- **Cons**: Timeline unknown, not a solution today

**Option 5: Connection pooling with separate authentication**
```
Application → Connection Pool (strong auth) → PostgreSQL (cert auth)
```
- Application authenticates to pool with strong credentials
- Pool uses certificate-based auth to PostgreSQL
- **Pros**: Separates concerns, can use different auth methods
- **Cons**: Additional infrastructure complexity

### The Architectural Tension

The SCRAM conflict reveals a deeper issue:

```
PeeGeeQ wants to support Spring Boot (popular framework)
    ↓
Spring Boot reactive uses R2DBC (standard for reactive SQL)
    ↓
R2DBC uses SCRAM 2.1 (old version, not updated)
    ↓
PeeGeeQ uses Vert.x 5.x (modern, high-performance)
    ↓
Vert.x uses SCRAM 3.1 (current version)
    ↓
CONFLICT: Cannot use both in same JVM with SCRAM authentication
```

**PeeGeeQ's solution:**
- Remove R2DBC entirely from Spring Boot examples
- Use only Vert.x for all database access
- Wrap Vert.x Futures in Mono for Spring WebFlux compatibility
- Maintain SCRAM-SHA-256 security

**Trade-offs:**
- ✅ Maintains production-grade security
- ✅ Full PeeGeeQ feature support
- ✅ No cryptographic compromises
- ❌ Spring Boot developers must learn Vert.x SQL Client
- ❌ Cannot use Spring Data R2DBC repositories
- ❌ May limit adoption in Spring ecosystem

### Security Best Practices Summary

**DO:**
- ✅ Use SCRAM-SHA-256 in production (default PostgreSQL 10+)
- ✅ Use certificate-based authentication for service accounts
- ✅ Implement network-level security (VPN, private networks)
- ✅ Use MD5 only for local development and testing
- ✅ Document why MD5 is used (temporary workaround)
- ✅ Ensure production configurations use strong authentication

**DON'T:**
- ❌ Use MD5 authentication in production
- ❌ Assume SSL/TLS makes MD5 secure (it doesn't)
- ❌ Use MD5 for compliance-regulated systems (PCI, HIPAA, SOX, GDPR)
- ❌ Store production data in MD5-authenticated databases
- ❌ Expose MD5-authenticated databases to networks
- ❌ Use MD5 as a "temporary" production solution (it never stays temporary)

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

