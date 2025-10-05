# PeeGeeQ Database Migrations

This module contains **ONLY** database migration scripts for PeeGeeQ. It is **NOT** a dependency of any other module and is **NOT** included in the application runtime.

## ⚠️ Important

- **This module is for deployment/setup ONLY**
- **Never run migrations from application code**
- **Migrations are a separate deployment step**

## Structure

```
peegeeq-migrations/
├── pom.xml                          # Flyway plugin configuration
├── README.md                        # This file
└── src/main/resources/db/migration/
    ├── V001__Create_Base_Tables.sql
    ├── V002__Add_New_Feature.sql    # Future migrations
    └── ...
```

## Running Migrations

### Local Development

```bash
cd peegeeq-migrations

# Run migrations against local database
mvn flyway:migrate -Plocal

# Check migration status
mvn flyway:info -Plocal

# Validate migrations
mvn flyway:validate -Plocal
```

### Test Environment

```bash
mvn flyway:migrate -Ptest
```

### Production

**IMPORTANT**: Production migrations should be run from CI/CD pipeline with credentials from environment variables:

```bash
# Set environment variables
export DB_URL=jdbc:postgresql://prod-db:5432/peegeeq
export DB_USER=peegeeq_admin
export DB_PASSWORD=<secure-password>

# Run migrations
mvn flyway:migrate -Pproduction
```

Or pass credentials directly:

```bash
mvn flyway:migrate \
  -Dflyway.url=jdbc:postgresql://prod-db:5432/peegeeq \
  -Dflyway.user=peegeeq_admin \
  -Dflyway.password=<secure-password>
```

## Migration Naming Convention

Flyway uses versioned migrations with the following naming pattern:

```
V<VERSION>__<DESCRIPTION>.sql
```

Examples:
- `V001__Create_Base_Tables.sql`
- `V002__Add_Consumer_Groups.sql`
- `V003__Add_Bitemporal_Indexes.sql`

## Deployment Process

### Recommended Production Deployment Flow

1. **Run migrations FIRST** (separate step, before app deployment)
   ```bash
   mvn flyway:migrate -Pproduction
   ```

2. **Deploy application** (after migrations complete successfully)
   ```bash
   kubectl apply -f peegeeq-deployment.yaml
   ```

### CI/CD Integration Example

```yaml
# Example GitLab CI/CD
deploy-production:
  stage: deploy
  script:
    # Step 1: Run database migrations
    - cd peegeeq-migrations
    - mvn flyway:migrate -Pproduction
    
    # Step 2: Deploy application (only if migrations succeed)
    - cd ..
    - kubectl apply -f k8s/production/
```

## Flyway Commands

| Command | Description |
|---------|-------------|
| `flyway:migrate` | Apply pending migrations |
| `flyway:info` | Show migration status |
| `flyway:validate` | Validate applied migrations against available ones |
| `flyway:baseline` | Baseline an existing database |
| `flyway:repair` | Repair metadata table after failed migration |

## Safety Features

- **`cleanDisabled=true`** - Prevents accidental database wipe
- **`validateOnMigrate=true`** - Validates checksums before migrating
- **`baselineOnMigrate=true`** - Allows migrating existing databases
- **`outOfOrder=false`** - Enforces sequential migration order

## Adding New Migrations

1. Create new SQL file with next version number:
   ```bash
   touch src/main/resources/db/migration/V002__Add_New_Feature.sql
   ```

2. Write your SQL:
   ```sql
   -- V002__Add_New_Feature.sql
   ALTER TABLE queue_messages ADD COLUMN new_field VARCHAR(255);
   CREATE INDEX idx_new_field ON queue_messages(new_field);
   ```

3. Test locally:
   ```bash
   mvn flyway:migrate -Plocal
   mvn flyway:info -Plocal
   ```

4. Commit and deploy through CI/CD

## Troubleshooting

### Migration Failed

If a migration fails, Flyway marks it as failed in the metadata table:

```bash
# Check status
mvn flyway:info -Plocal

# Fix the SQL file, then repair
mvn flyway:repair -Plocal

# Try again
mvn flyway:migrate -Plocal
```

### Baseline Existing Database

If you have an existing database without Flyway metadata:

```bash
mvn flyway:baseline -Plocal
```

## Best Practices

1. ✅ **Always test migrations locally first**
2. ✅ **Run migrations in a transaction** (Flyway default for PostgreSQL)
3. ✅ **Keep migrations small and focused**
4. ✅ **Never modify applied migrations** (create new ones instead)
5. ✅ **Use descriptive migration names**
6. ✅ **Run migrations before deploying application**
7. ❌ **Never run migrations from application code**
8. ❌ **Never use `flyway:clean` in production**

## Module Dependencies

**NONE** - This module has no dependencies on other PeeGeeQ modules and is not a dependency of any other module.

The application runtime does NOT include this module or its migration scripts.

