# TestContainers Standards for PeeGeeQ Project

## Overview

This document defines the **mandatory standards** for using TestContainers across the PeeGeeQ project to ensure consistency, maintainability, and prevent Docker image pollution.

## 🚨 Critical Rules

### 1. **ALWAYS Use PostgreSQLTestConstants**

```java
// ✅ CORRECT - Use the centralized constant
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

@Container
@SuppressWarnings("resource")
static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

// OR with custom settings
@Container
@SuppressWarnings("resource") 
static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createContainer(
    "custom_db_name", 
    "custom_user", 
    "custom_password");
```

```java
// ❌ WRONG - Never hardcode PostgreSQL versions
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
```

### 2. **Standard Container Patterns**

#### **Pattern A: Static Container (Recommended for Most Tests)**
```java
@Testcontainers
class MyTest {
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();
    
    private final Map<String, String> originalProperties = new HashMap<>();
    
    @BeforeEach
    void setUp() {
        saveOriginalProperties();
        configureSystemPropertiesForContainer();
    }
    
    @AfterEach
    void tearDown() {
        restoreOriginalProperties();
    }
}
```

#### **Pattern B: Spring Boot Tests with @DynamicPropertySource**
```java
@SpringBootTest
@Testcontainers
class SpringBootTest {
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("peegeeq.database.host", postgres::getHost);
        registry.add("peegeeq.database.port", () -> postgres.getFirstMappedPort().toString());
        registry.add("peegeeq.database.name", postgres::getDatabaseName);
        registry.add("peegeeq.database.username", postgres::getUsername);
        registry.add("peegeeq.database.password", postgres::getPassword);
        registry.add("peegeeq.database.schema", () -> "public");
        registry.add("peegeeq.migration.enabled", () -> "true");
        registry.add("peegeeq.migration.auto-migrate", () -> "true");
    }
}
```

#### **Pattern C: Manual Container Management (Use Sparingly)**
```java
@Test
void testWithManualContainer() {
    try (@SuppressWarnings("resource") 
         PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer()) {
        
        postgres.start();
        configureSystemPropertiesForContainer(postgres);
        
        // Test logic here
        
    } // Container automatically stopped by try-with-resources
}
```

### 3. **System Property Configuration**

#### **Standard Property Names**
```java
private void configureSystemPropertiesForContainer() {
    // Standard PeeGeeQ properties
    System.setProperty("peegeeq.database.host", postgres.getHost());
    System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
    System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
    System.setProperty("peegeeq.database.username", postgres.getUsername());
    System.setProperty("peegeeq.database.password", postgres.getPassword());
    System.setProperty("peegeeq.database.schema", "public");
    System.setProperty("peegeeq.database.ssl.enabled", "false");
    System.setProperty("peegeeq.migration.enabled", "true");
    System.setProperty("peegeeq.migration.auto-migrate", "true");
}
```

#### **Property Cleanup Pattern**
```java
private final Map<String, String> originalProperties = new HashMap<>();

private void saveOriginalProperties() {
    String[] propertiesToSave = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.schema"
    };
    
    for (String property : propertiesToSave) {
        String value = System.getProperty(property);
        if (value != null) {
            originalProperties.put(property, value);
        }
    }
}

private void restoreOriginalProperties() {
    // Clear test properties
    System.clearProperty("peegeeq.database.host");
    System.clearProperty("peegeeq.database.port");
    System.clearProperty("peegeeq.database.name");
    System.clearProperty("peegeeq.database.username");
    System.clearProperty("peegeeq.database.password");
    System.clearProperty("peegeeq.database.schema");
    
    // Restore original properties
    originalProperties.forEach(System::setProperty);
}
```

### 4. **Container Configuration Options**

#### **Standard Container**
```java
PostgreSQLTestConstants.createStandardContainer()
// Uses: postgres:15.13-alpine3.20, 256MB shared memory, no reuse
```

#### **Custom Container**
```java
PostgreSQLTestConstants.createContainer("db_name", "username", "password")
// Uses: postgres:15.13-alpine3.20, 256MB shared memory, no reuse
```

#### **High Performance Container**
```java
PostgreSQLTestConstants.createHighPerformanceContainer("db_name", "username", "password")
// Uses: postgres:15.13-alpine3.20, 512MB shared memory, performance tuning
```

### 5. **Common Mistakes to Avoid**

#### ❌ **Don't Do This:**
```java
// Hardcoded version
new PostgreSQLContainer<>("postgres:15")

// Missing @SuppressWarnings("resource")
@Container
static PostgreSQLContainer<?> postgres = ...

// No property cleanup
@BeforeEach
void setUp() {
    System.setProperty("db.host", postgres.getHost());
    // No cleanup in @AfterEach
}

// Inconsistent property names
System.setProperty("db.host", postgres.getHost());        // Wrong
System.setProperty("database.host", postgres.getHost());  // Wrong
```

#### ✅ **Do This Instead:**
```java
// Use centralized constants
PostgreSQLTestConstants.createStandardContainer()

// Always suppress resource warnings for static containers
@Container
@SuppressWarnings("resource")
static PostgreSQLContainer<?> postgres = ...

// Always clean up properties
@BeforeEach void setUp() { saveOriginalProperties(); configureSystemPropertiesForContainer(); }
@AfterEach void tearDown() { restoreOriginalProperties(); }

// Use standard property names
System.setProperty("peegeeq.database.host", postgres.getHost());
```

## 🔧 Migration Tools

### Check for Violations
```bash
# Run the pre-commit check
./scripts/pre-commit-postgresql-check

# Search for hardcoded versions
findstr /R /S /C:"new PostgreSQLContainer.*postgres:" *.java
```

### Fix Violations
```bash
# Run migration script (Linux/Mac)
./scripts/migrate-postgresql-versions.sh

# Run migration script (Windows)
powershell -ExecutionPolicy Bypass -File ./scripts/migrate-postgresql-versions.ps1
```

## 📋 Checklist for New Tests

- [ ] Import `dev.mars.peegeeq.test.PostgreSQLTestConstants`
- [ ] Use `PostgreSQLTestConstants.createStandardContainer()` or variants
- [ ] Add `@SuppressWarnings("resource")` to static containers
- [ ] Implement property save/restore pattern in `@BeforeEach`/`@AfterEach`
- [ ] Use standard `peegeeq.database.*` property names
- [ ] Test compiles and runs successfully
- [ ] No hardcoded PostgreSQL versions in code

## 🎯 Benefits

1. **Single PostgreSQL Version**: Only `postgres:15.13-alpine3.20` across entire project
2. **Consistent Configuration**: Standard property names and patterns
3. **Clean Test Isolation**: Proper property cleanup between tests
4. **Maintainable**: Easy to update PostgreSQL version project-wide
5. **No Docker Pollution**: Prevents multiple PostgreSQL images
