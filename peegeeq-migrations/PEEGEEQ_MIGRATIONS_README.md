# PeeGeeQ Database Migrations

This module contains **ONLY** database migration scripts for PeeGeeQ. It is **NOT** a dependency of any other module and is **NOT** included in the application runtime.

---

## ⚠️ CRITICAL: Database Setup Required

**Before using PeeGeeQ**, you **MUST** run database migrations to create required tables. This is the #1 issue reported by new users.

**Common errors if you skip this step:**
```
FATAL: dead_letter_queue table does not exist - schema not initialized properly
FATAL: bitemporal_event_log table does not exist - schema not initialized properly
FATAL: queue_messages table does not exist - schema not initialized properly
```

**Solution**: Follow the [Quick Start](#quick-start) section below.

---

## 📖 Documentation

| Document | Purpose |
|----------|---------|
| **[PEEGEEQ_MIGRATIONS_README.md](PEEGEEQ_MIGRATIONS_README.md)** (this file) | Overview and quick start |
| **[PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md](PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md)** | Complete JAR usage guide |
| **[PEEGEEQ_MIGRATIONS_DEPLOYMENT_GUIDE.md](PEEGEEQ_MIGRATIONS_DEPLOYMENT_GUIDE.md)** | Deployment patterns for all environments |
| **[TESTING.md](TESTING.md)** | Automated test suite and testing guide |
| **[scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md](scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md)** | Dev convenience scripts |
| **[scripts/TEST_SCRIPTS_README.md](scripts/TEST_SCRIPTS_README.md)** | Automated test scripts reference |

---

## ⚠️ Important Principles

- **This module is for deployment/setup ONLY**
- **Never run migrations from application code** (no Flyway in `@PostConstruct`)
- **Migrations are a separate deployment step** (run before deploying application)
- **Never use `DB_CLEAN_ON_START=true` in production**

---

## Module Structure

```
peegeeq-migrations/
├── src/main/
│   ├── java/dev/mars/peegeeq/migrations/
│   │   └── RunMigrations.java                 # Standalone CLI runner
│   └── resources/db/migration/
│       ├── V001__Create_Base_Tables.sql       # Base schema
│       └── V002__*.sql                        # Future migrations
├── scripts/
│   ├── dev-reset-db.sh                        # Dev script (Linux/Mac)
│   ├── dev-reset-db.bat                       # Dev script (Windows)
│   ├── dev-migrate.sh                         # Dev script (Linux/Mac)
│   └── dev-migrate.bat                        # Dev script (Windows)
├── Dockerfile                                 # Container image
├── docker-compose.dev.yml                     # Dev environment
├── pom.xml                                    # Maven config + Shade plugin
└── *.md                                       # Documentation
```

---

## Quick Start

### For Developers (Local Development)

#### Option 1: Dev Scripts (Easiest)

```bash
# Linux/Mac - Reset database (clean + migrate)
cd peegeeq-migrations/scripts
./dev-reset-db.sh

# Windows - Reset database (clean + migrate)
cd peegeeq-migrations\scripts
dev-reset-db.bat
```

**That's it!** Your database is ready.

**See**: [scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md](scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md) for details.

#### Option 2: Standalone JAR

```bash
# Build migrations JAR
mvn clean package -pl peegeeq-migrations -DskipTests

# Set environment variables
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
export DB_USER=peegeeq_dev
export DB_PASSWORD=peegeeq_dev

# Run migrations
java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
```

**See**: [PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md](PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md) for complete JAR documentation.

#### Option 3: Maven Plugin

```bash
cd peegeeq-migrations
mvn flyway:migrate -Plocal
```

---

### For DevOps (Production)

#### CI/CD Pipeline

```bash
#!/bin/bash
set -e

# Build migrations JAR
mvn clean package -pl peegeeq-migrations -DskipTests

# Run migrations
export DB_JDBC_URL=$PROD_DB_URL
export DB_USER=$PROD_DB_USER
export DB_PASSWORD=$PROD_DB_PASSWORD

java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate

# Deploy application only if migrations succeed
# (your deployment commands here)
```



### Indexes
- Performance indexes on all tables
- Concurrent indexes for bitemporal queries
- Composite indexes for common query patterns

### Views
- `bitemporal_current_state` - Current state of bi-temporal events
- `bitemporal_latest_events` - Latest events per entity
- `bitemporal_event_stats` - Event statistics
- `bitemporal_event_type_stats` - Statistics by event type

### Functions & Triggers
- `notify_message_inserted()` - LISTEN/NOTIFY trigger for queue messages
- `update_message_processing_updated_at()` - Automatic timestamp updates
- `cleanup_completed_message_processing()` - Cleanup old processing records
- `register_consumer_group_for_existing_messages()` - Consumer group registration
- `create_consumer_group_entries_for_new_message()` - Auto-create consumer entries
- `cleanup_completed_outbox_messages()` - Cleanup processed outbox messages
- `notify_bitemporal_event()` - LISTEN/NOTIFY for bi-temporal events
- `cleanup_old_metrics()` - Cleanup old metric records
- `get_events_as_of_time()` - Query bi-temporal state at specific time

---

## Common Tasks

### Reset Local Database

```bash
cd peegeeq-migrations/scripts
./dev-reset-db.sh
```

### Apply New Migration

```bash
cd peegeeq-migrations/scripts
./dev-migrate.sh
```

### Check Migration Status

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar info
```

### Validate Migrations

```bash
java -jar peegeeq-migrations/target/peegeeq-migrations.jar validate
```

---

## Environment Variables

| Variable | Required | Description | Example |
|----------|----------|-------------|---------|
| `DB_JDBC_URL` | ✅ Yes | JDBC connection URL | `jdbc:postgresql://localhost:5432/peegeeq_dev` |
| `DB_USER` | ✅ Yes | Database username | `peegeeq_dev` |
| `DB_PASSWORD` | ✅ Yes | Database password | `peegeeq_dev` |
| `DB_SCHEMA` | ❌ No | Target database schema | `public` (default) or `myschema` |
| `DB_CLEAN_ON_START` | ❌ No | Clean before migrate (⚠️ dev only!) | `true` or `false` (default: `false`) |

---

## Troubleshooting

### "Table does not exist" errors

**Problem**: Application fails with "table does not exist" errors.

**Solution**: You haven't run migrations. Follow the [Quick Start](#quick-start) section.

### "Connection refused" errors

**Problem**: Cannot connect to PostgreSQL.

**Solutions**:
- Ensure PostgreSQL is running: `docker ps` or `pg_isready`
- Check connection settings (host, port, database name)
- Verify database exists: `psql -h localhost -U postgres -l`

### "Migration failed" errors

**Problem**: Migration fails mid-way.

**Solutions**:
1. Check Flyway metadata: `SELECT * FROM flyway_schema_history;`
2. Run repair: `java -jar peegeeq-migrations.jar repair`
3. Fix failed migration SQL
4. Re-run: `java -jar peegeeq-migrations.jar migrate`

### "Authentication failed" errors

**Problem**: Cannot authenticate to database.

**Solutions**:
- Verify database credentials
- Check that database user exists: `psql -h localhost -U postgres -c "\du"`
- Ensure user has proper permissions

---

## Architecture Decision: Why Separate Module?

**Benefits**:
- **Safety**: Application cannot accidentally drop/recreate production schema
- **Control**: Explicit, auditable migration step in deployment pipeline
- **Rollback**: Can rollback migrations independently of application deployment
- **Zero Downtime**: Run migrations before deploying new application version
- **Separation of Concerns**: Schema management is separate from application logic

---

## Migration Workflow

```
1. Developer creates new migration file
   └─> V002__Add_New_Feature.sql

2. Developer tests locally
   └─> cd peegeeq-migrations/scripts
   └─> ./dev-reset-db.sh
   └─> Run application
   └─> Verify feature works

3. Developer commits and pushes
   └─> git add src/main/resources/db/migration/V002__*.sql
   └─> git commit -m "Add new feature"
   └─> git push

4. CI/CD pipeline runs
   └─> Build migrations JAR
   └─> Run migrations on test environment
   └─> Run integration tests
   └─> Deploy to staging
   └─> Run migrations on staging
   └─> Deploy application to staging

5. Production deployment
   └─> Run migrations on production (CI/CD pipeline)
   └─> Wait for completion
   └─> Deploy application (only if migrations succeed)
```

---

## Best Practices

### ✅ DO

1. **Run migrations before deploying application**
2. **Use CI/CD pipelines for production** (automated and auditable)
3. **Store credentials securely** (environment variables, secrets management)
4. **Test migrations in staging before production**
5. **Keep migrations idempotent** (safe to run multiple times)
6. **Make migrations backwards compatible** (old app version can still run)
7. **Take database backups before production migrations**

### ❌ DON'T

1. **Never run migrations from application code**
2. **Never set `DB_CLEAN_ON_START=true` in production**
3. **Never race multiple pods to migrate**
4. **Never skip migration validation in CI/CD**
5. **Never hardcode credentials in scripts or YAML**
6. **Never run migrations manually in production** (use automation)

---

## See Also

- **[PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md](PEEGEEQ_MIGRATIONS_JAR_REFERENCE.md)** - Complete JAR usage guide
- **[PEEGEEQ_MIGRATIONS_DEPLOYMENT_GUIDE.md](PEEGEEQ_MIGRATIONS_DEPLOYMENT_GUIDE.md)** - Deployment patterns for all environments
- **[scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md](scripts/PEEGEEQ_MIGRATIONS_SCRIPTS_GUIDE.md)** - Dev convenience scripts

## What Gets Created

### Tables
- `schema_version` - Tracks schema version history
- `outbox` - Outbox pattern for transactional messaging
- `outbox_consumer_groups` - Consumer group tracking for outbox
- `queue_messages` - Native queue messages
- `message_processing` - Message processing state tracking
- `dead_letter_queue` - Failed messages
- `queue_metrics` - Queue performance metrics
- `connection_pool_metrics` - Connection pool statistics
- `bitemporal_event_log` - Bi-temporal event store

