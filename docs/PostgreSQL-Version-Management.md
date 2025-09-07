# PostgreSQL Version Management for PeeGeeQ

This document explains how PeeGeeQ ensures only ONE PostgreSQL version is used across the entire project to prevent Docker Desktop pollution and maintain consistency.

## 🎯 Problem Statement

Before standardization, the PeeGeeQ project had multiple PostgreSQL versions scattered across different files:
- `postgres:15`
- `postgres:15-alpine3.20`
- `postgres:15.13-alpine3.20`
- `postgres:14`

This created:
- **Docker Desktop pollution** with multiple PostgreSQL images
- **Inconsistent test environments** across different modules
- **Maintenance overhead** when updating PostgreSQL versions
- **Potential compatibility issues** between different PostgreSQL versions

## ✅ Solution: Centralized Version Management

### **1. Single Source of Truth**

All PostgreSQL versions are now managed through `PostgreSQLTestConstants`:

```java
// ✅ CORRECT - Use the centralized constant
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
    .withDatabaseName("test_db")
    .withUsername("test_user")
    .withPassword("test_password");
```

```java
// ❌ WRONG - Don't hardcode versions
PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
    .withDatabaseName("test_db")
    .withUsername("test_user")
    .withPassword("test_password");
```

### **2. Helper Methods**

Use the provided helper methods for common scenarios:

```java
// Standard container with default settings
PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

// Custom container with specific database settings
PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createContainer(
    "my_database", 
    "my_user", 
    "my_password"
);

// High-performance container for performance tests
PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createHighPerformanceContainer(
    "perf_db", 
    "perf_user", 
    "perf_password"
);
```

## 🔧 Implementation Details

### **Current Standardized Version**
```
postgres:15.13-alpine3.20
```

**Why this version?**
- **PostgreSQL 15.13**: Stable LTS release with excellent performance
- **Alpine Linux**: Minimal image size (~80MB vs ~300MB for full images)
- **Specific version pinning**: Prevents unexpected updates and ensures reproducibility
- **Security**: Regular security updates in Alpine Linux ecosystem

### **Constants Available**

| Constant | Value | Purpose |
|----------|-------|---------|
| `POSTGRES_IMAGE` | `"postgres:15.13-alpine3.20"` | The ONE PostgreSQL version |
| `DEFAULT_DATABASE_NAME` | `"peegeeq_test"` | Standard database name |
| `DEFAULT_USERNAME` | `"peegeeq_test"` | Standard username |
| `DEFAULT_PASSWORD` | `"peegeeq_test"` | Standard password |
| `DEFAULT_SHARED_MEMORY_SIZE` | `256MB` | Standard memory allocation |
| `HIGH_PERFORMANCE_SHARED_MEMORY_SIZE` | `512MB` | For performance tests |

## 🛡️ Enforcement Mechanisms

### **1. Maven Enforcer Plugin**

The build will fail if hardcoded PostgreSQL versions are detected:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>3.4.1</version>
    <executions>
        <execution>
            <id>enforce-postgresql-version-consistency</id>
            <goals>
                <goal>enforce</goal>
            </goals>
            <configuration>
                <rules>
                    <bannedPatterns>
                        <bannedPattern>
                            <pattern>new PostgreSQLContainer&lt;&gt;\("postgres:(?!15\.13-alpine3\.20")</pattern>
                            <message>❌ Use PostgreSQLTestConstants.POSTGRES_IMAGE instead!</message>
                        </bannedPattern>
                    </bannedPatterns>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### **2. Pre-commit Hook**

Install the pre-commit hook to prevent hardcoded versions from being committed:

```bash
# Copy the pre-commit hook
cp scripts/pre-commit-postgresql-check .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

### **3. Migration Script**

Use the migration script to convert existing hardcoded versions:

```bash
# Linux/Mac
./scripts/migrate-postgresql-versions.sh

# Windows
scripts\migrate-postgresql-versions.bat
```

## 📋 Migration Guide

### **Step 1: Add Dependency**

Add the test-support module to your `pom.xml`:

```xml
<dependency>
    <groupId>dev.mars</groupId>
    <artifactId>peegeeq-test-support</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### **Step 2: Update Imports**

Add the import statement:

```java
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
```

### **Step 3: Replace Hardcoded Versions**

Replace hardcoded versions with the constant:

```java
// Before
PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

// After
PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE);
```

### **Step 4: Use Helper Methods (Optional)**

Consider using helper methods for cleaner code:

```java
// Instead of manual configuration
PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
    .withDatabaseName("peegeeq_test")
    .withUsername("peegeeq_test")
    .withPassword("peegeeq_test")
    .withSharedMemorySize(256 * 1024 * 1024L)
    .withReuse(false);

// Use helper method
PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();
```

## 🔄 Version Update Process

When updating the PostgreSQL version:

1. **Update the constant** in `PostgreSQLTestConstants.POSTGRES_IMAGE`
2. **Test thoroughly** across all modules
3. **Update documentation** if needed
4. **Commit the single change** - all usages update automatically

## 📊 Benefits Achieved

### **Before Standardization**
- ❌ Multiple PostgreSQL images in Docker Desktop
- ❌ Inconsistent test environments
- ❌ Manual version updates across multiple files
- ❌ Potential compatibility issues

### **After Standardization**
- ✅ Single PostgreSQL image (`postgres:15.13-alpine3.20`)
- ✅ Consistent test environments across all modules
- ✅ Single point of version management
- ✅ Automated enforcement and migration tools
- ✅ Cleaner Docker Desktop
- ✅ Faster CI/CD builds (image reuse)

## 🚀 Best Practices

1. **Always use the constant** - Never hardcode PostgreSQL versions
2. **Use helper methods** when possible for cleaner code
3. **Run the enforcer** regularly to catch violations
4. **Install pre-commit hooks** to prevent violations
5. **Update centrally** when PostgreSQL version changes are needed

## 🔍 Troubleshooting

### **Build Fails with Enforcer Error**
```
❌ FORBIDDEN: Hardcoded PostgreSQL versions detected!
```

**Solution**: Replace hardcoded versions with `PostgreSQLTestConstants.POSTGRES_IMAGE`

### **Import Not Found**
```
cannot find symbol: PostgreSQLTestConstants
```

**Solution**: Add the `peegeeq-test-support` dependency to your module's `pom.xml`

### **Multiple PostgreSQL Images in Docker**
**Solution**: Run `docker image prune` to clean up old images after migration

## 📞 Support

For questions or issues with PostgreSQL version management:
1. Check this documentation
2. Run the migration script
3. Use the pre-commit hook to prevent future issues
4. Consult the team for version update decisions
