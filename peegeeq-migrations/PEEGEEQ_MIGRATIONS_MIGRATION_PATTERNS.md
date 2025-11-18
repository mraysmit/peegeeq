# PeeGeeQ Migration Patterns

This document describes the recommended patterns for running database migrations in different environments.

## Architecture Principles

### ✅ DO

1. **Separate migrations from application runtime**
   - Migrations are in a separate module (`peegeeq-migrations`)
   - No Flyway dependency in core modules (`peegeeq-db`, `peegeeq-rest`, etc.)
   - Migrations run as a separate step before application deployment

2. **Run migrations before deploying application**
   - Migrations complete successfully → Deploy application
   - Migrations fail → Abort deployment

3. **Use appropriate tool for each environment**
   - **Production/Kubernetes**: Standalone JAR (Job or InitContainer)
   - **CI/CD**: Standalone JAR or Maven plugin
   - **Local Development**: Dev scripts or Maven plugin

### ❌ DON'T

1. **Never run migrations from application code**
   - No Flyway in `@PostConstruct` or startup hooks
   - No automatic migration on application boot
   - Migrations are a deployment concern, not a runtime concern

2. **Never use `clean` in production**
   - `DB_CLEAN_ON_START=true` is for local development only
   - Production databases should never be cleaned automatically

3. **Never race multiple pods to migrate**
   - Use Kubernetes Jobs (not Deployments) for migrations
   - Or use InitContainers (but Jobs are better for observability)

## Pattern 1: Kubernetes Job (Recommended for Production)

**Best for**: Production, Staging, Test environments

**Advantages**:
- Clear separation of concerns
- Easy to monitor and debug
- Explicit success/failure
- Can be retried independently
- Doesn't block pod startup

**Implementation**:

```yaml
# k8s/migration-job.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: peegeeq-migrations-{{ .Release.Revision }}
  labels:
    app: peegeeq
    component: migrations
spec:
  template:
    spec:
      containers:
      - name: migrations
        image: peegeeq-migrations:{{ .Values.image.tag }}
        command: ["java", "-jar", "/app/peegeeq-migrations.jar", "migrate"]
        env:
        - name: DB_JDBC_URL
          value: "jdbc:postgresql://{{ .Values.postgres.host }}:5432/{{ .Values.postgres.database }}"
        - name: DB_USER
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
      restartPolicy: Never
  backoffLimit: 3
```

**Deployment**:

```bash
# Step 1: Run migrations
kubectl apply -f k8s/migration-job.yaml
kubectl wait --for=condition=complete job/peegeeq-migrations --timeout=300s

# Step 2: Deploy application (only if migrations succeed)
kubectl apply -f k8s/peegeeq-deployment.yaml
```

## Pattern 2: Kubernetes InitContainer

**Best for**: Simple deployments where you want migrations to run automatically

**Advantages**:
- Automatic - no separate step needed
- Migrations run before app container starts
- Simple deployment process

**Disadvantages**:
- Harder to debug (logs are in pod, not separate Job)
- Migrations run on every pod restart
- Can't easily retry just migrations

**Implementation**:

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: peegeeq
spec:
  replicas: 3
  template:
    spec:
      initContainers:
      - name: migrations
        image: peegeeq-migrations:latest
        command: ["java", "-jar", "/app/peegeeq-migrations.jar", "migrate"]
        env:
        - name: DB_JDBC_URL
          value: "jdbc:postgresql://postgres:5432/peegeeq"
        - name: DB_USER
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
      containers:
      - name: peegeeq
        image: peegeeq-rest:latest
        # ... rest of app config
```

## Pattern 3: CI/CD Pipeline

**Best for**: GitLab CI, GitHub Actions, Jenkins, etc.

**Advantages**:
- Full control over deployment process
- Easy to add pre/post migration steps
- Can run validation before deploying

**Implementation (GitLab CI)**:

```yaml
# .gitlab-ci.yml
stages:
  - build
  - migrate
  - deploy

build-migrations:
  stage: build
  script:
    - mvn clean package -pl peegeeq-migrations -DskipTests
  artifacts:
    paths:
      - peegeeq-migrations/target/peegeeq-migrations.jar

migrate-production:
  stage: migrate
  dependencies:
    - build-migrations
  script:
    - export DB_JDBC_URL=$PROD_DB_URL
    - export DB_USER=$PROD_DB_USER
    - export DB_PASSWORD=$PROD_DB_PASSWORD
    - java -jar peegeeq-migrations/target/peegeeq-migrations.jar migrate
  only:
    - main

deploy-production:
  stage: deploy
  dependencies:
    - migrate-production
  script:
    - kubectl apply -f k8s/production/
  only:
    - main
```

## Pattern 4: Docker Compose (Local Development)

**Best for**: Local development with Docker

**Advantages**:
- Complete environment in one command
- Automatic migrations on startup
- Easy to reset and restart

**Implementation**:

```yaml
# docker-compose.dev.yml
version: '3.8'

services:
  postgres:
    image: postgres:15.13-alpine3.20
    environment:
      POSTGRES_DB: peegeeq_dev
      POSTGRES_USER: peegeeq_dev
      POSTGRES_PASSWORD: peegeeq_dev
    ports:
      - "5432:5432"

  migrations:
    build:
      context: .
      dockerfile: peegeeq-migrations/Dockerfile
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_JDBC_URL: jdbc:postgresql://postgres:5432/peegeeq_dev
      DB_USER: peegeeq_dev
      DB_PASSWORD: peegeeq_dev
      DB_CLEAN_ON_START: "true"  # Dev only!
    command: migrate
    restart: "no"

  peegeeq-rest:
    build: .
    depends_on:
      migrations:
        condition: service_completed_successfully
    ports:
      - "8080:8080"
```

## Pattern 5: Dev Scripts (Recommended for Local Development)

**Best for**: Local development without Docker

**Advantages**:
- Simple one-command reset
- No Docker required
- Fast iteration

**Implementation**:

```bash
# Linux/Mac
./dev-reset-db.sh

# Windows
dev-reset-db.bat
```

These scripts:
1. Build the migrations JAR
2. Run migrations (with optional clean)
3. Provide clear feedback

## Comparison Matrix

| Pattern | Production | Staging | CI/CD | Local Dev | Observability | Complexity |
|---------|------------|---------|-------|-----------|---------------|------------|
| **Kubernetes Job** | ✅ Best | ✅ Best | ✅ Good | ❌ Overkill | ⭐⭐⭐⭐⭐ | Medium |
| **InitContainer** | ⚠️ OK | ⚠️ OK | ⚠️ OK | ❌ Overkill | ⭐⭐⭐ | Low |
| **CI/CD Pipeline** | ✅ Best | ✅ Best | ✅ Best | ❌ N/A | ⭐⭐⭐⭐ | Medium |
| **Docker Compose** | ❌ No | ❌ No | ❌ No | ✅ Best | ⭐⭐⭐ | Low |
| **Dev Scripts** | ❌ No | ❌ No | ❌ No | ✅ Best | ⭐⭐⭐⭐ | Very Low |
| **Maven Plugin** | ⚠️ OK | ⚠️ OK | ✅ Good | ✅ Good | ⭐⭐⭐ | Low |

## Recommendations by Environment

### Production
1. **Primary**: Kubernetes Job
2. **Alternative**: CI/CD Pipeline with standalone JAR

### Staging/Test
1. **Primary**: Kubernetes Job
2. **Alternative**: CI/CD Pipeline

### Local Development
1. **Primary**: Dev scripts (`./dev-reset-db.sh`)
2. **Alternative**: Docker Compose
3. **Alternative**: Maven plugin (`mvn flyway:migrate -Plocal`)

### CI/CD
1. **Primary**: Standalone JAR in pipeline
2. **Alternative**: Maven plugin

## Migration Workflow

```
┌─────────────────────────────────────────────────────────────┐
│                    Development Workflow                      │
└─────────────────────────────────────────────────────────────┘

1. Developer creates new migration file
   └─> V002__Add_New_Feature.sql

2. Developer tests locally
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
   └─> Run migrations on production (Kubernetes Job)
   └─> Wait for completion
   └─> Deploy application (only if migrations succeed)
```

## Safety Checklist

Before deploying to production:

- [ ] Migrations tested locally
- [ ] Migrations tested in staging
- [ ] Migrations are idempotent (can be run multiple times safely)
- [ ] Migrations are backwards compatible (old app version can still run)
- [ ] Rollback plan documented
- [ ] Database backup taken
- [ ] `DB_CLEAN_ON_START` is NOT set in production
- [ ] Credentials stored in Kubernetes Secrets
- [ ] Migration timeout configured appropriately
- [ ] Monitoring/alerting configured for migration job

