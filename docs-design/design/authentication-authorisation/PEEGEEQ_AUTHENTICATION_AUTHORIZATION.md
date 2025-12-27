# PeeGeeQ Authentication & Authorization Design

**Version:** 2.0
**Date:** 2025-12-27
**Status:** Design & Implementation Reference
**Author:** Mark Andrew Ray-Smith Cityline Ltd

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architecture Overview](#architecture-overview)
3. [SQLite Management Database](#sqlite-management-database)
4. [User Roles and Permissions](#user-roles-and-permissions)
5. [Authentication Flow](#authentication-flow)
6. [Tenant Management](#tenant-management)
7. [User Management](#user-management)
8. [Connection Pooling Strategy](#connection-pooling-strategy)
9. [Authorization Middleware](#authorization-middleware)
10. [Bootstrap and Initial Setup](#bootstrap-and-initial-setup)
11. [Credential Encryption](#credential-encryption)
12. [JWT Token Structure](#jwt-token-structure)
13. [API Endpoint Reference](#api-endpoint-reference)
14. [Implementation Plan](#implementation-plan)
15. [Example API Flows](#example-api-flows)
16. [Testing Strategy](#testing-strategy)
17. [Deployment Patterns](#deployment-patterns)
18. [Security Considerations](#security-considerations)
19. [Advantages and Trade-offs](#advantages-and-trade-offs)

---

## Executive Summary

This document defines the comprehensive authentication and authorization architecture for PeeGeeQ using a **two-tier architecture**:

- **Management Plane (SQLite):** Admin accounts, tenant configurations, user management, encrypted credentials
- **Data Plane (PostgreSQL):** Tenant data (queues, events, outbox, bi-temporal stores)

### Key Principles

**Architecture:**
- SQLite database stores all management data locally (simple, portable, embedded)
- Admin accounts manage tenant configurations and PostgreSQL connection details
- Complete separation between management plane and data plane
- Multi-tenant support via PostgreSQL schema-based isolation

**Security:**
- JWT-based authentication for all API requests
- Role-based access control (RBAC) with granular permissions
- AES-256-GCM encryption for PostgreSQL credentials
- Bcrypt password hashing for user accounts
- Three-layer tenant isolation (JWT, connection pool, database schema)

**REST API Layer:**
- Authorization enforced at REST API layer only
- UI is stateless and credential-agnostic
- All security logic in backend
- JWT tokens include tenant context for automatic isolation

---

## 1. Architecture Overview

### 1.1 Two-Tier Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ Management Plane (SQLite - peegeeq_management.db)            │
│                                                              │
│ ┌────────────────┐  ┌──────────────────┐  ┌──────────────┐ │
│ │ Admin Accounts │  │ Tenant Configs   │  │ User Accounts│ │
│ │ - username     │  │ - tenant_name    │  │ - username   │ │
│ │ - password     │  │ - pg_host        │  │ - password   │ │
│ │ - email        │  │ - pg_database    │  │ - role       │ │
│ └────────────────┘  │ - pg_schema      │  │ - tenant_id  │ │
│                     │ - pg_credentials │  └──────────────┘ │
│                     │   (encrypted)    │                    │
│                     └──────────────────┘                    │
└──────────────────────────────────────────────────────────────┘
                              │
                              │ Connects to
                              ▼
┌──────────────────────────────────────────────────────────────┐
│ Data Plane (PostgreSQL - Multiple Tenants)                  │
│                                                              │
│ Database: saas_platform                                      │
│ ├── Schema: tenant_acme                                      │
│ │   ├── queue_messages                                       │
│ │   ├── outbox                                               │
│ │   ├── events_bitemporal                                    │
│ │   └── orders_queue (user-created)                          │
│ ├── Schema: tenant_globex                                    │
│ │   ├── queue_messages                                       │
│ │   ├── outbox                                               │
│ │   ├── events_bitemporal                                    │
│ │   └── trades_queue (user-created)                          │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 Scope Boundary

**IN SCOPE (REST API Layer):**
- User authentication (admin and regular users)
- JWT token generation and validation
- Role-based authorization checks
- Tenant configuration management
- PostgreSQL credential management (encrypted)
- Tenant-aware connection pooling
- API endpoint access control

**OUT OF SCOPE (UI Layer):**
- UI-level authorization logic
- Client-side route protection
- Client-side permission checks
- UI state management for auth

**UI Responsibility:**
- Display login form (admin or user)
- Store JWT token (localStorage/sessionStorage)
- Include JWT token in API requests (Authorization header)
- Display errors from API (401, 403)
- Redirect to login on 401

**API Responsibility:**
- Validate all requests
- Enforce all authorization rules
- Return appropriate HTTP status codes
- Manage database connections per tenant
- Decrypt PostgreSQL credentials on-demand

### 1.3 Component Responsibilities

**Management Plane (SQLite):**
- Store admin accounts (super users who manage the system)
- Store tenant configurations (PostgreSQL connection details)
- Store user accounts and roles
- Store encrypted PostgreSQL credentials
- Manage authentication (login/logout)
- Manage authorization (role-based permissions)
- No tenant data - only metadata

**Data Plane (PostgreSQL):**
- Store tenant data (queues, events, outbox, bi-temporal stores)
- Execute PeeGeeQ operations (publish, consume, query)
- Enforce schema-based isolation via `search_path`
- No authentication/authorization logic
- No cross-tenant access possible

### 1.4 High-Level Flow

```
1. User enters credentials in UI (admin or regular user)
2. UI sends POST /api/v1/auth/login or /api/v1/auth/admin/login
3. API validates credentials against SQLite database
4. API generates JWT token with user/tenant context
5. UI stores token, includes in all subsequent requests
6. API validates JWT on every request (middleware)
7. API extracts role, tenantId, and schemaName from JWT
8. API checks if role has permission for operation
9. API gets tenant's PostgreSQL credentials from SQLite (decrypted)
10. API creates/reuses connection pool with tenant's schema in search_path
11. API executes operation in tenant's isolated schema
12. API returns result
```

### 1.5 Multi-Tenant Architecture Integration

**PeeGeeQ's Multi-Tenant Model:**
- **Schema-per-Tenant**: Each tenant has a dedicated PostgreSQL schema
- **Complete Isolation**: All tables (queues, event stores, outbox) in tenant's schema
- **Schema-based Security**: Connection pools use `search_path` for tenant isolation
- **No Cross-Tenant Access**: Tenants cannot access each other's data
- **Flexible Deployment**: Tenants can use different PostgreSQL databases

**Authentication Integration:**
- User belongs to a tenant (stored in SQLite `user_accounts.tenant_id`)
- Admin can manage multiple tenants
- JWT token includes `tenantId` and `schemaName` claims
- All database operations automatically use tenant's schema
- PostgreSQL credentials are tenant-specific (stored encrypted in SQLite)

---

## 2. SQLite Management Database

### 2.1 Database Location

**File:** `peegeeq_management.db`

**Default Location:** `~/.peegeeq/peegeeq_management.db`

**Configurable via:**
```bash
PEEGEEQ_MANAGEMENT_DB_PATH=/path/to/peegeeq_management.db
```

### 2.2 Schema Definition

```sql
-- Admin accounts (super users who manage the system)
CREATE TABLE admin_accounts (
    admin_id TEXT PRIMARY KEY,  -- UUID as TEXT
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,  -- bcrypt
    email TEXT,
    enabled INTEGER DEFAULT 1,  -- SQLite boolean (1=true, 0=false)
    password_must_change INTEGER DEFAULT 0,  -- Force password change on next login
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    last_login TEXT
);

CREATE INDEX idx_admin_username ON admin_accounts(username);

-- Tenant configurations (PostgreSQL connection details)
CREATE TABLE tenant_configs (
    tenant_id TEXT PRIMARY KEY,  -- UUID as TEXT
    tenant_name TEXT UNIQUE NOT NULL,

    -- PostgreSQL connection details
    pg_host TEXT NOT NULL,
    pg_port INTEGER NOT NULL DEFAULT 5432,
    pg_database TEXT NOT NULL,
    pg_schema TEXT NOT NULL,  -- Schema name for this tenant (e.g., tenant_acme)

    -- Encrypted PostgreSQL credentials
    pg_username_encrypted TEXT NOT NULL,
    pg_password_encrypted TEXT NOT NULL,

    -- Metadata
    admin_id TEXT NOT NULL REFERENCES admin_accounts(admin_id) ON DELETE CASCADE,
    enabled INTEGER DEFAULT 1,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(pg_host, pg_port, pg_database, pg_schema)
);

CREATE INDEX idx_tenant_admin ON tenant_configs(admin_id);
CREATE INDEX idx_tenant_enabled ON tenant_configs(enabled);

-- User roles (defines permissions for UI/API features)
CREATE TABLE user_roles (
    role_id TEXT PRIMARY KEY,  -- UUID as TEXT
    role_name TEXT UNIQUE NOT NULL,
    description TEXT,
    permissions TEXT NOT NULL,  -- JSON string
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- User accounts (users who access the UI/API)
CREATE TABLE user_accounts (
    user_id TEXT PRIMARY KEY,  -- UUID as TEXT
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,  -- bcrypt
    email TEXT,
    tenant_id TEXT NOT NULL REFERENCES tenant_configs(tenant_id) ON DELETE CASCADE,
    role_id TEXT NOT NULL REFERENCES user_roles(role_id),
    enabled INTEGER DEFAULT 1,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    last_login TEXT
);

CREATE INDEX idx_user_username ON user_accounts(username);
CREATE INDEX idx_user_tenant ON user_accounts(tenant_id);
CREATE INDEX idx_user_role ON user_accounts(role_id);

-- Refresh tokens
CREATE TABLE refresh_tokens (
    token_id TEXT PRIMARY KEY,  -- UUID as TEXT
    user_id TEXT NOT NULL REFERENCES user_accounts(user_id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

---

## 3. User Roles and Permissions

### 3.1 Role Definitions

**Default Roles:**

| Role | Description | Permissions |
|------|-------------|-------------|
| **admin** | Full system access | All permissions |
| **developer** | Create/modify queues and event stores | queue:*, eventStore:*, database:view |
| **viewer** | Read-only access | queue:read, eventStore:read, database:view |

### 3.2 Permission Model

**Permissions JSON Structure:**
```json
{
  "database": {
    "create": true,
    "delete": true,
    "view": true
  },
  "queue": {
    "create": true,
    "delete": true,
    "read": true,
    "write": true,
    "purge": true
  },
  "eventStore": {
    "create": true,
    "delete": true,
    "read": true,
    "write": true
  },
  "user": {
    "create": true,
    "delete": true,
    "view": true,
    "modify": true
  }
}
```

**Example Role Permissions:**

**Admin Role:**
```json
{
  "database": {"create": true, "delete": true, "view": true},
  "queue": {"create": true, "delete": true, "read": true, "write": true, "purge": true},
  "eventStore": {"create": true, "delete": true, "read": true, "write": true},
  "user": {"create": true, "delete": true, "view": true, "modify": true}
}
```

**Developer Role:**
```json
{
  "database": {"create": false, "delete": false, "view": true},
  "queue": {"create": true, "delete": true, "read": true, "write": true, "purge": false},
  "eventStore": {"create": true, "delete": true, "read": true, "write": true},
  "user": {"create": false, "delete": false, "view": false, "modify": false}
}
```

**Viewer Role:**
```json
{
  "database": {"create": false, "delete": false, "view": true},
  "queue": {"create": false, "delete": false, "read": true, "write": false, "purge": false},
  "eventStore": {"create": false, "delete": false, "read": true, "write": false},
  "user": {"create": false, "delete": false, "view": false, "modify": false}
}
```

---

## 4. Authentication Flow

### 4.1 Admin Login

**Endpoint:** `POST /api/v1/auth/admin/login`

**Request:**
```json
{
  "username": "admin",
  "password": "Xy9#mK2$pL5@nQ8!wR3%"
}
```

**Process:**
1. Query SQLite: `SELECT * FROM admin_accounts WHERE username = ?`
2. Verify password using bcrypt
3. **Check `password_must_change` flag**
4. Generate JWT token with admin claims
5. Update `last_login` timestamp
6. Return JWT token (with `passwordMustChange` flag if applicable)

**Response (Normal):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresIn": 900,
  "userType": "admin",
  "username": "admin",
  "passwordMustChange": false
}
```

**Response (First Login - Must Change Password):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresIn": 900,
  "userType": "admin",
  "username": "admin",
  "passwordMustChange": true
}
```

**JWT Token (Admin):**
```json
{
  "sub": "admin-uuid",
  "username": "admin",
  "userType": "admin",
  "passwordMustChange": true,
  "iat": 1703721600,
  "exp": 1703722500
}
```

**UI Behavior:**
- If `passwordMustChange: true`, redirect to password change page
- User cannot access other endpoints until password is changed
- After password change, `password_must_change` flag is set to `0` in database

### 4.2 User Login

**Endpoint:** `POST /api/v1/auth/login`

**Request:**
```json
{
  "username": "john.doe",
  "password": "UserPassword123!"
}
```

**Process:**
1. Query SQLite: `SELECT u.*, t.*, r.* FROM user_accounts u JOIN tenant_configs t ON u.tenant_id = t.tenant_id JOIN user_roles r ON u.role_id = r.role_id WHERE u.username = ?`
2. Verify password using bcrypt
3. Decrypt PostgreSQL credentials for tenant
4. Generate JWT token with user + tenant claims
5. Update `last_login` timestamp
6. Return JWT token

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresIn": 900,
  "userType": "user",
  "username": "john.doe",
  "tenantName": "ACME Corporation",
  "role": "developer"
}
```

**JWT Token (User):**
```json
{
  "sub": "user-uuid",
  "username": "john.doe",
  "userType": "user",
  "role": "developer",
  "roleId": "role-uuid",
  "tenantId": "tenant-uuid",
  "tenantName": "ACME Corporation",
  "schemaName": "tenant_acme",
  "permissions": ["queue:create", "queue:read", "queue:write", "queue:delete", "eventStore:create", ...],
  "iat": 1703721600,
  "exp": 1703722500
}
```

### 4.3 Logout

**Endpoint:** `POST /api/v1/auth/logout`

**Request:**
```
Headers: Authorization: Bearer <token>
```

**Process:**
1. Validate JWT token
2. Delete refresh token from SQLite (if exists)
3. Return success

**Response:**
```json
{
  "message": "Logged out successfully"
}
```

---

## 5. Tenant Management (Admin Only)

### 5.1 Create Tenant Configuration

**Endpoint:** `POST /api/v1/admin/tenants`

**Request:**
```json
{
  "tenantName": "ACME Corporation",
  "pgHost": "localhost",
  "pgPort": 5432,
  "pgDatabase": "saas_platform",
  "pgSchema": "tenant_acme",
  "pgUsername": "peegeeq_user",
  "pgPassword": "SecurePassword123!"
}
```

**Process:**
1. Validate admin JWT token
2. Encrypt PostgreSQL credentials (AES-256-GCM)
3. Insert into `tenant_configs` table
4. Test PostgreSQL connection
5. Initialize tenant schema (create PeeGeeQ tables)
6. Return tenant configuration

**Response:**
```json
{
  "tenantId": "tenant-uuid",
  "tenantName": "ACME Corporation",
  "pgHost": "localhost",
  "pgPort": 5432,
  "pgDatabase": "saas_platform",
  "pgSchema": "tenant_acme",
  "enabled": true,
  "createdAt": "2025-12-27T10:00:00Z"
}
```

**Schema Initialization:**
```sql
-- Executed in PostgreSQL when tenant is created
CREATE SCHEMA IF NOT EXISTS tenant_acme;
SET search_path = tenant_acme;

-- Initialize PeeGeeQ tables
CREATE TABLE queue_messages (...);
CREATE TABLE outbox (...);
CREATE TABLE events_bitemporal (...);
-- ... other PeeGeeQ tables
```

### 5.2 List Tenants

**Endpoint:** `GET /api/v1/admin/tenants`

**Response:**
```json
{
  "tenants": [
    {
      "tenantId": "tenant-uuid-1",
      "tenantName": "ACME Corporation",
      "pgHost": "localhost",
      "pgDatabase": "saas_platform",
      "pgSchema": "tenant_acme",
      "enabled": true,
      "userCount": 15,
      "createdAt": "2025-01-01T00:00:00Z"
    },
    {
      "tenantId": "tenant-uuid-2",
      "tenantName": "Globex Corporation",
      "pgHost": "localhost",
      "pgDatabase": "saas_platform",
      "pgSchema": "tenant_globex",
      "enabled": true,
      "userCount": 8,
      "createdAt": "2025-01-15T00:00:00Z"
    }
  ]
}
```

### 5.3 Delete Tenant

**Endpoint:** `DELETE /api/v1/admin/tenants/{tenantId}`

**Process:**
1. Validate admin JWT token
2. Delete all users for tenant (CASCADE)
3. Close all connection pools for tenant
4. Optionally drop PostgreSQL schema: `DROP SCHEMA tenant_acme CASCADE`
5. Delete tenant configuration from SQLite
6. Return success

**Response:**
```json
{
  "message": "Tenant deleted successfully",
  "tenantId": "tenant-uuid"
}
```

---

## 6. User Management (Admin Only)

### 6.1 Create User

**Endpoint:** `POST /api/v1/admin/users`

**Request:**
```json
{
  "username": "john.doe",
  "password": "UserPassword123!",
  "email": "john.doe@acme.com",
  "tenantId": "tenant-uuid",
  "roleId": "developer-role-uuid"
}
```

**Process:**
1. Validate admin JWT token
2. Verify tenant exists
3. Verify role exists
4. Hash password using bcrypt
5. Insert into `user_accounts` table
6. Return user info

**Response:**
```json
{
  "userId": "user-uuid",
  "username": "john.doe",
  "email": "john.doe@acme.com",
  "tenantId": "tenant-uuid",
  "tenantName": "ACME Corporation",
  "roleId": "developer-role-uuid",
  "roleName": "developer",
  "enabled": true,
  "createdAt": "2025-12-27T10:00:00Z"
}
```

### 6.2 List Users

**Endpoint:** `GET /api/v1/admin/users?tenantId={tenantId}`

**Response:**
```json
{
  "users": [
    {
      "userId": "user-uuid-1",
      "username": "john.doe",
      "email": "john.doe@acme.com",
      "tenantName": "ACME Corporation",
      "roleName": "developer",
      "enabled": true,
      "lastLogin": "2025-12-27T09:30:00Z"
    },
    {
      "userId": "user-uuid-2",
      "username": "jane.smith",
      "email": "jane.smith@acme.com",
      "tenantName": "ACME Corporation",
      "roleName": "viewer",
      "enabled": true,
      "lastLogin": "2025-12-26T14:20:00Z"
    }
  ]
}
```

### 6.3 Delete User

**Endpoint:** `DELETE /api/v1/admin/users/{userId}`

**Process:**
1. Validate admin JWT token
2. Delete refresh tokens (CASCADE)
3. Delete user account
4. Return success

**Response:**
```json
{
  "message": "User deleted successfully",
  "userId": "user-uuid"
}
```

---

## 7. Connection Pooling Strategy

### 7.1 Tenant-Aware Connection Manager

**Design:**
- One connection pool per tenant
- Pool uses tenant's PostgreSQL credentials (decrypted from SQLite)
- Pool sets `search_path` to tenant's schema
- Pools created on-demand and cached
- All operations automatically scoped to tenant's schema

**Implementation:**
```java
public class TenantConnectionManager {

    private final Map<String, PgClient> pools = new ConcurrentHashMap<>();
    private final SQLiteService sqliteService;
    private final EncryptionService encryptionService;
    private final PgClientFactory clientFactory;

    public PgClient getClient(String tenantId) {
        return pools.computeIfAbsent(tenantId, id -> {
            // Get tenant config from SQLite
            TenantConfig tenant = sqliteService.getTenantConfig(tenantId);

            // Decrypt PostgreSQL credentials
            String pgUsername = encryptionService.decrypt(tenant.getPgUsernameEncrypted());
            String pgPassword = encryptionService.decrypt(tenant.getPgPasswordEncrypted());

            // Create connection config with tenant's schema
            PgConnectionConfig config = new PgConnectionConfig.Builder()
                .host(tenant.getPgHost())
                .port(tenant.getPgPort())
                .database(tenant.getPgDatabase())
                .username(pgUsername)
                .password(pgPassword)
                .schema(tenant.getPgSchema())  // Sets search_path = tenant_acme
                .build();

            // Create pool
            PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(10)
                .build();

            logger.info("Creating connection pool for tenant={}, schema={}",
                tenant.getTenantName(), tenant.getPgSchema());

            return clientFactory.createClient(tenantId, config, poolConfig);
        });
    }

    public void closePool(String tenantId) {
        PgClient client = pools.remove(tenantId);
        if (client != null) {
            client.close();
        }
    }
}
```

**Schema Isolation:**
```java
// When connection is created, PgConnectionConfig sets:
// SET search_path = tenant_acme;

// All subsequent queries use tenant's schema:
SELECT * FROM queue_messages;
// Resolves to: tenant_acme.queue_messages

INSERT INTO outbox (payload) VALUES ('...');
// Resolves to: tenant_acme.outbox

// No cross-tenant access possible without explicit schema qualification
```




---

## 8. Authorization Middleware

### 8.1 JWT Validation Middleware

**Implementation:**
```java
public class AuthorizationHandler implements Handler<RoutingContext> {

    private final JWTValidator jwtValidator;

    @Override
    public void handle(RoutingContext ctx) {
        // 1. Extract JWT from Authorization header
        String token = extractToken(ctx);
        if (token == null) {
            ctx.response().setStatusCode(401).end("{\"error\":\"Missing token\"}");
            return;
        }

        // 2. Validate and decode JWT
        DecodedJWT jwt = jwtValidator.validate(token);
        if (jwt == null) {
            ctx.response().setStatusCode(401).end("{\"error\":\"Invalid token\"}");
            return;
        }

        // 3. Extract user context from JWT
        String userType = jwt.getClaim("userType").asString();

        if ("admin".equals(userType)) {
            // Admin user
            AdminContext adminContext = new AdminContext(
                jwt.getSubject(),
                jwt.getClaim("username").asString()
            );
            ctx.put("adminContext", adminContext);
        } else {
            // Regular user
            UserContext userContext = new UserContext(
                jwt.getSubject(),
                jwt.getClaim("username").asString(),
                jwt.getClaim("role").asString(),
                jwt.getClaim("roleId").asString(),
                jwt.getClaim("tenantId").asString(),
                jwt.getClaim("tenantName").asString(),
                jwt.getClaim("schemaName").asString(),
                jwt.getClaim("permissions").asList(String.class)
            );
            ctx.put("userContext", userContext);
        }

        // 4. Continue to next handler
        ctx.next();
    }
}
```

### 8.2 Permission Checking

**Handler Pattern:**
```java
public class QueueHandler {

    private final TenantConnectionManager connectionManager;

    public void handleCreateQueue(RoutingContext ctx) {
        // 1. Get user context (middleware already validated JWT)
        UserContext user = ctx.get("userContext");
        if (user == null) {
            ctx.response().setStatusCode(401).end("{\"error\":\"Unauthorized\"}");
            return;
        }

        // 2. Check permission
        if (!user.hasPermission("queue:create")) {
            ctx.response().setStatusCode(403)
                .end("{\"error\":\"Insufficient permissions\"}");
            return;
        }

        // 3. Extract parameters
        CreateQueueRequest request = parseRequest(ctx);

        // 4. Get tenant-specific connection (automatically uses tenant's schema)
        PgClient client = connectionManager.getClient(user.getTenantId());

        // 5. Log tenant context for audit
        logger.info("Creating queue for tenant={}, schema={}, user={}",
            user.getTenantName(), user.getSchemaName(), user.getUsername());

        // 6. Execute operation (automatically in tenant's schema)
        queueService.createQueue(client, request)
            .onSuccess(queue -> {
                ctx.response()
                    .setStatusCode(201)
                    .end(Json.encode(queue));
            })
            .onFailure(error -> {
                logger.error("Failed to create queue for tenant={}: {}",
                    user.getTenantName(), error.getMessage());
                ctx.response()
                    .setStatusCode(500)
                    .end("{\"error\":\"" + error.getMessage() + "\"}");
            });
    }
}
```

**UserContext Class:**
```java
public class UserContext {
    private final String userId;
    private final String username;
    private final String role;
    private final String roleId;
    private final String tenantId;
    private final String tenantName;
    private final String schemaName;
    private final List<String> permissions;

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    // ... getters
}
```

---

## 9. Bootstrap and Initial Setup

### 9.1 Auto-Initialization on First Startup

**Configuration (System Properties or peegeeq-auth.properties):**

The management database path can be configured via:

1. **System property** (highest priority):
   ```bash
   java -Dpeegeeq.management.db.path=/path/to/peegeeq_management.db -jar peegeeq-rest.jar
   ```

2. **Environment variable**:
   ```bash
   export PEEGEEQ_MANAGEMENT_DB_PATH=/path/to/peegeeq_management.db
   ```

3. **Default location** (if not specified):
   ```
   ${user.home}/.peegeeq/peegeeq_management.db
   ```

**Encryption and JWT Configuration:**

These are loaded from environment variables (never hardcoded):

```bash
# Encryption key for PostgreSQL credentials (AES-256)
# Generate with: openssl rand -base64 32
export PEEGEEQ_ENCRYPTION_KEY=<base64-encoded-256-bit-key>

# JWT secret
# Generate with: openssl rand -base64 64
export PEEGEEQ_JWT_SECRET=<base64-encoded-secret>

# JWT expiration (optional, defaults to 900 seconds = 15 minutes)
export PEEGEEQ_JWT_EXPIRATION=900
```

**First Startup Process:**

When the `peegeeq-rest` application starts up (in `StartRestServer.main()`), the `ManagementDatabaseInitializer` checks if the SQLite database exists at the configured path.

**If database does NOT exist:**

1. **Create SQLite database** at configured path (e.g., `~/.peegeeq/peegeeq_management.db`)
2. **Create all tables:**
   - `admin_accounts`
   - `tenant_configs`
   - `user_accounts`
   - `user_roles`
   - `refresh_tokens`
3. **Seed default roles** (admin, developer, viewer)
4. **Generate secure random password** for default admin user (20 characters, alphanumeric + symbols)
5. **Create default admin account:**
   - Username: `admin`
   - Password: `<auto-generated>`
   - Email: `admin@localhost`
6. **Write credentials to log file** (WARN level for visibility):

```
╔════════════════════════════════════════════════════════════════════════════╗
║                    PEEGEEQ FIRST-TIME INITIALIZATION                       ║
╔════════════════════════════════════════════════════════════════════════════╗
║                                                                            ║
║  SQLite management database created at:                                   ║
║  /home/user/.peegeeq/peegeeq_management.db                                ║
║                                                                            ║
║  Default admin account created:                                           ║
║                                                                            ║
║    Username: admin                                                         ║
║    Password: Xy9#mK2$pL5@nQ8!wR3%                                         ║
║                                                                            ║
║  IMPORTANT:                                                                ║
║  - This password will NOT be shown again                                   ║
║  - Change this password immediately after first login                      ║
║  - Use POST /api/v1/admin/change-password to change password              ║
║                                                                            ║
╚════════════════════════════════════════════════════════════════════════════╝
```

7. **Application starts normally** with all endpoints available

**If database DOES exist:**

1. **Verify database schema** (check all tables exist)
2. **Application starts normally**

**Security Considerations:**

- Auto-generated password is cryptographically secure (using `SecureRandom`)
- Password is logged only once at WARN level (visible in console and log file)
- Password is never stored in plaintext (only bcrypt hash in database)
- Admin must change password on first login (enforced by `password_must_change` flag)
- Log file should be secured with appropriate file permissions (600 on Unix systems)

### 9.2 Default Roles Seeding

**Executed on first startup:**
```sql
INSERT INTO user_roles (role_id, role_name, description, permissions) VALUES
(
  'admin-role-uuid',
  'admin',
  'Full system access',
  '{"database":{"create":true,"delete":true,"view":true},"queue":{"create":true,"delete":true,"read":true,"write":true,"purge":true},"eventStore":{"create":true,"delete":true,"read":true,"write":true},"user":{"create":true,"delete":true,"view":true,"modify":true}}'
),
(
  'developer-role-uuid',
  'developer',
  'Application development access',
  '{"database":{"create":false,"delete":false,"view":true},"queue":{"create":true,"delete":true,"read":true,"write":true,"purge":false},"eventStore":{"create":true,"delete":true,"read":true,"write":true},"user":{"create":false,"delete":false,"view":false,"modify":false}}'
),
(
  'viewer-role-uuid',
  'viewer',
  'Read-only access',
  '{"database":{"create":false,"delete":false,"view":true},"queue":{"create":false,"delete":false,"read":true,"write":false,"purge":false},"eventStore":{"create":false,"delete":false,"read":true,"write":false},"user":{"create":false,"delete":false,"view":false,"modify":false}}'
);
```

### 9.3 Implementation: ManagementDatabaseInitializer

**Vert.x Service (called from StartRestServer.main):**

```java
package dev.mars.peegeeq.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Initializes the SQLite management database on first startup.
 *
 * This service is called from StartRestServer.main() before the Vert.x
 * verticle is deployed. It checks if the SQLite database exists and
 * creates it with default admin credentials if not found.
 */
public class ManagementDatabaseInitializer {
    private static final Logger logger = LoggerFactory.getLogger(ManagementDatabaseInitializer.class);

    private final String dbPath;
    private final SQLiteService sqliteService;
    private final PasswordService passwordService;

    public ManagementDatabaseInitializer(String dbPath, SQLiteService sqliteService, PasswordService passwordService) {
        this.dbPath = dbPath;
        this.sqliteService = sqliteService;
        this.passwordService = passwordService;
    }

    /**
     * Initialize the management database if it doesn't exist.
     * This method is called synchronously during application startup.
     */
    public void initialize() {
        File dbFile = new File(dbPath);

        if (!dbFile.exists()) {
            logger.warn("SQLite management database not found. Initializing...");
            initializeDatabase();
        } else {
            logger.info("SQLite management database found at: {}", dbPath);
            verifyDatabaseSchema();
        }
    }

    private void initializeDatabase() {
        try {
            // 1. Create database and tables
            sqliteService.createDatabase();
            logger.info("Created SQLite database at: {}", dbPath);

            // 2. Seed default roles
            seedDefaultRoles();
            logger.info("Seeded default roles (admin, developer, viewer)");

            // 3. Generate secure random password
            String generatedPassword = generateSecurePassword();

            // 4. Create default admin account
            String adminId = UUID.randomUUID().toString();
            String passwordHash = passwordService.hashPassword(generatedPassword);

            sqliteService.createAdminAccount(
                adminId,
                "admin",
                passwordHash,
                "admin@localhost",
                true,  // enabled
                true   // password_must_change
            );

            // 5. Log credentials (WARN level for visibility)
            logInitialCredentials(generatedPassword);

            logger.info("PeeGeeQ management database initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize management database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private String generateSecurePassword() {
        // Generate 20-character password with uppercase, lowercase, digits, and symbols
        String uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lowercase = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        String symbols = "!@#$%^&*";
        String allChars = uppercase + lowercase + digits + symbols;

        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(20);

        // Ensure at least one of each type
        password.append(uppercase.charAt(random.nextInt(uppercase.length())));
        password.append(lowercase.charAt(random.nextInt(lowercase.length())));
        password.append(digits.charAt(random.nextInt(digits.length())));
        password.append(symbols.charAt(random.nextInt(symbols.length())));

        // Fill remaining characters randomly
        for (int i = 4; i < 20; i++) {
            password.append(allChars.charAt(random.nextInt(allChars.length())));
        }

        // Shuffle the password
        char[] passwordArray = password.toString().toCharArray();
        for (int i = passwordArray.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = passwordArray[i];
            passwordArray[i] = passwordArray[j];
            passwordArray[j] = temp;
        }

        return new String(passwordArray);
    }

    private void logInitialCredentials(String password) {
        String banner = String.format("""

            ╔════════════════════════════════════════════════════════════════════════════╗
            ║                    PEEGEEQ FIRST-TIME INITIALIZATION                       ║
            ╔════════════════════════════════════════════════════════════════════════════╗
            ║                                                                            ║
            ║  SQLite management database created at:                                   ║
            ║  %-74s║
            ║                                                                            ║
            ║  Default admin account created:                                           ║
            ║                                                                            ║
            ║    Username: admin                                                         ║
            ║    Password: %-60s║
            ║                                                                            ║
            ║  IMPORTANT:                                                                ║
            ║  - This password will NOT be shown again                                   ║
            ║  - Change this password immediately after first login                      ║
            ║  - Use POST /api/v1/admin/change-password to change password              ║
            ║                                                                            ║
            ╚════════════════════════════════════════════════════════════════════════════╝
            """, dbPath, password);

        logger.warn(banner);
    }

    private void seedDefaultRoles() {
        // Insert default roles (admin, developer, viewer)
        // See section 9.2 for SQL
        sqliteService.seedDefaultRoles();
    }

    private void verifyDatabaseSchema() {
        // Verify all required tables exist
        sqliteService.verifySchema();
    }
}
```

**Integration with StartRestServer:**

```java
package dev.mars.peegeeq.rest;

import dev.mars.peegeeq.auth.ManagementDatabaseInitializer;
import dev.mars.peegeeq.auth.SQLiteService;
import dev.mars.peegeeq.auth.PasswordService;
import dev.mars.peegeeq.runtime.PeeGeeQContext;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StartRestServer {
    private static final Logger logger = LoggerFactory.getLogger(StartRestServer.class);

    public static void main(String[] args) {
        int port = Integer.getInteger("peegeeq.rest.port", 8080);

        logger.info("Starting PeeGeeQ REST API server...");

        // 1. Load configuration
        String dbPath = System.getProperty("peegeeq.management.db.path",
            System.getProperty("user.home") + "/.peegeeq/peegeeq_management.db");

        // 2. Initialize management database (if needed)
        logger.info("Checking management database...");
        SQLiteService sqliteService = new SQLiteService(dbPath);
        PasswordService passwordService = new PasswordService();
        ManagementDatabaseInitializer initializer = new ManagementDatabaseInitializer(
            dbPath, sqliteService, passwordService);
        initializer.initialize();

        // 3. Bootstrap PeeGeeQ runtime (for data plane)
        logger.info("Bootstrapping PeeGeeQ runtime...");
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
        DatabaseSetupService setupService = context.getDatabaseSetupService();
        logger.info("PeeGeeQ runtime bootstrapped successfully");

        // 4. Create authentication services
        AuthService authService = new AuthService(sqliteService, passwordService);
        TenantService tenantService = new TenantService(sqliteService);
        UserService userService = new UserService(sqliteService);

        // 5. Start HTTP server
        logger.info("Starting HTTP server on port {}...", port);
        Vertx vertx = Vertx.vertx();
        final int serverPort = port;
        vertx.deployVerticle(new PeeGeeQRestServer(
                serverPort,
                setupService,      // Data plane service
                authService,       // Management plane service
                tenantService,     // Management plane service
                userService        // Management plane service
            ))
            .onSuccess(id -> {
                logger.info("PeeGeeQ REST API server started on port {}", serverPort);
                logger.info("Health check: http://localhost:{}/health", serverPort);
                logger.info("API base URL: http://localhost:{}/api/v1", serverPort);
            })
            .onFailure(cause -> {
                logger.error("Failed to start PeeGeeQ REST API server", cause);
                System.exit(1);
            });
    }
}
```

### 9.4 Admin Password Change Endpoint

**New endpoint for admin to change password:**

```
POST /api/v1/admin/change-password

Request:
{
  "currentPassword": "Xy9#mK2$pL5@nQ8!wR3%",
  "newPassword": "MyNewSecurePassword123!"
}

Response (200 OK):
{
  "message": "Password changed successfully"
}

Response (400 Bad Request):
{
  "error": "Current password is incorrect"
}
```

**Implementation:**
- Verify current password matches
- Validate new password meets complexity requirements
- Hash new password with bcrypt
- Update `password_hash` in `admin_accounts` table
- Set `password_must_change = 0`
- Invalidate all existing JWT tokens (optional: increment token version)

### 9.5 Configuration Summary

**Required Environment Variables:**

```bash
# Encryption key for PostgreSQL credentials (AES-256)
# Generate with: openssl rand -base64 32
export PEEGEEQ_ENCRYPTION_KEY=<base64-encoded-256-bit-key>

# JWT secret
# Generate with: openssl rand -base64 64
export PEEGEEQ_JWT_SECRET=<base64-encoded-secret>
```

**Optional Configuration:**

```bash
# SQLite database path (defaults to ~/.peegeeq/peegeeq_management.db)
export PEEGEEQ_MANAGEMENT_DB_PATH=/custom/path/peegeeq_management.db

# JWT expiration in seconds (defaults to 900 = 15 minutes)
export PEEGEEQ_JWT_EXPIRATION=900

# REST API port (defaults to 8080)
export PEEGEEQ_REST_PORT=8080
```

**Startup Command:**

```bash
# With environment variables
export PEEGEEQ_ENCRYPTION_KEY=$(openssl rand -base64 32)
export PEEGEEQ_JWT_SECRET=$(openssl rand -base64 64)
mvn exec:java -pl peegeeq-rest

# Or with system properties
mvn exec:java -pl peegeeq-rest \
  -Dpeegeeq.management.db.path=/custom/path/db.sqlite \
  -Dpeegeeq.rest.port=9090
```

---

## 10. Credential Encryption

### 10.1 Encryption Strategy

**Algorithm:** AES-256-GCM

**Master Key:** Stored in environment variable `PEEGEEQ_ENCRYPTION_KEY`

**Implementation:**
```java
public class EncryptionService {

    private final SecretKey masterKey;

    public String encrypt(String plaintext) {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = generateIV();
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, spec);

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(UTF_8));

        // Combine IV + ciphertext
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    public String decrypt(String encrypted) {
        byte[] combined = Base64.getDecoder().decode(encrypted);

        // Extract IV + ciphertext
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[combined.length - 12];
        System.arraycopy(combined, 0, iv, 0, 12);
        System.arraycopy(combined, 12, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec);

        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, UTF_8);
    }
}
```



---

## 12. JWT Token Structure

### 12.1 Admin JWT Token

**Claims:**
```json
{
  "sub": "admin-uuid",
  "username": "admin",
  "userType": "admin",
  "iat": 1703721600,
  "exp": 1703722500,
  "type": "access"
}
```

**Characteristics:**
- No tenant information (admin manages all tenants)
- Short expiration (15 minutes)
- Used for admin endpoints only

### 12.2 User JWT Token

**Claims:**
```json
{
  "sub": "user-uuid",
  "username": "john.doe",
  "userType": "user",
  "role": "developer",
  "roleId": "role-uuid",
  "tenantId": "tenant-uuid",
  "tenantName": "ACME Corporation",
  "schemaName": "tenant_acme",
  "permissions": [
    "queue:create",
    "queue:read",
    "queue:write",
    "queue:delete",
    "eventStore:create",
    "eventStore:read",
    "eventStore:write",
    "database:view"
  ],
  "iat": 1703721600,
  "exp": 1703722500,
  "type": "access"
}
```

**Characteristics:**
- Includes complete tenant context
- Includes role and permissions (for quick authorization checks)
- `schemaName` is critical for connection pool isolation
- Short expiration (15 minutes)

### 12.3 Refresh Token

**Claims:**
```json
{
  "sub": "user-uuid",
  "type": "refresh",
  "iat": 1703721600,
  "exp": 1704326400
}
```

**Characteristics:**
- Long expiration (7 days)
- Stored in SQLite `refresh_tokens` table (hashed)
- Used to obtain new access token without re-login
- Can be revoked by deleting from database

### 12.4 Token Validation

**Middleware Process:**
1. Extract token from `Authorization: Bearer <token>` header
2. Verify signature using JWT secret
3. Check expiration
4. Extract claims
5. For user tokens: verify tenant still exists and is enabled
6. For user tokens: verify user still exists and is enabled
7. Populate request context with user/admin info
8. Continue to handler

---

## 13. API Endpoint Reference

**Note:** No setup endpoint is required. The system auto-initializes on first startup and logs the default admin credentials.

### 13.1 Authentication Endpoints

| Endpoint | Method | Description | Auth Required |
|----------|--------|-------------|---------------|
| `/api/v1/auth/admin/login` | POST | Admin login | None |
| `/api/v1/auth/login` | POST | User login | None |
| `/api/v1/auth/logout` | POST | Logout (invalidate refresh token) | JWT |
| `/api/v1/auth/refresh` | POST | Refresh access token | Refresh Token |
| `/api/v1/admin/change-password` | POST | Change admin password | Admin JWT |

### 13.2 Admin Endpoints (Tenant Management)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/admin/tenants` | GET | List all tenants | Admin |
| `/api/v1/admin/tenants` | POST | Create tenant configuration | Admin |
| `/api/v1/admin/tenants/{id}` | GET | Get tenant details | Admin |
| `/api/v1/admin/tenants/{id}` | PUT | Update tenant configuration | Admin |
| `/api/v1/admin/tenants/{id}` | DELETE | Delete tenant (with cleanup) | Admin |
| `/api/v1/admin/tenants/{id}/test` | POST | Test PostgreSQL connection | Admin |

### 13.3 Admin Endpoints (User Management)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/admin/users` | GET | List all users (filter by tenant) | Admin |
| `/api/v1/admin/users` | POST | Create user | Admin |
| `/api/v1/admin/users/{id}` | GET | Get user details | Admin |
| `/api/v1/admin/users/{id}` | PUT | Update user | Admin |
| `/api/v1/admin/users/{id}` | DELETE | Delete user | Admin |
| `/api/v1/admin/users/{id}/reset-password` | POST | Reset user password | Admin |

### 13.4 Admin Endpoints (Role Management)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/admin/roles` | GET | List all roles | Admin |
| `/api/v1/admin/roles` | POST | Create custom role | Admin |
| `/api/v1/admin/roles/{id}` | GET | Get role details | Admin |
| `/api/v1/admin/roles/{id}` | PUT | Update role permissions | Admin |
| `/api/v1/admin/roles/{id}` | DELETE | Delete custom role | Admin |

### 13.5 User Endpoints (Database Setup)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/database-setup/create` | POST | Create database setup | database:create |
| `/api/v1/database-setup/{setupId}` | GET | Get setup details | database:view |
| `/api/v1/database-setup/{setupId}` | DELETE | Delete setup | database:delete |
| `/api/v1/setups` | GET | List all setups | database:view |

### 13.6 User Endpoints (Queue Operations)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/queues` | GET | List queues (in tenant's schema) | queue:read |
| `/api/v1/queues/create` | POST | Create queue | queue:create |
| `/api/v1/queues/{name}` | GET | Get queue details | queue:read |
| `/api/v1/queues/{name}` | DELETE | Delete queue | queue:delete |
| `/api/v1/queues/{name}/publish` | POST | Publish message | queue:write |
| `/api/v1/queues/{name}/consume` | POST | Consume message | queue:read |
| `/api/v1/queues/{name}/purge` | POST | Purge queue | queue:purge |

### 13.7 User Endpoints (Event Store Operations)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/eventstores/:setupId/:storeName/events` | POST | Append event to event store | eventStore:write |
| `/api/v1/eventstores/:setupId/:storeName/events` | GET | Query events from event store | eventStore:read |
| `/api/v1/eventstores/:setupId/:storeName/events/:eventId` | GET | Get specific event by ID | eventStore:read |
| `/api/v1/eventstores/:setupId/:storeName/events/:eventId/versions` | GET | Get all versions of event | eventStore:read |
| `/api/v1/eventstores/:setupId/:storeName/events/:eventId/at` | GET | Get event as of transaction time | eventStore:read |
| `/api/v1/eventstores/:setupId/:storeName/events/:eventId/corrections` | POST | Append correction to event | eventStore:write |
| `/api/v1/eventstores/:setupId/:storeName/events/stream` | GET | Stream events (SSE) | eventStore:read |
| `/api/v1/eventstores/:setupId/:storeName/stats` | GET | Get event store statistics | eventStore:read |

### 13.8 User Endpoints (Consumer Group Operations)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/queues/:setupId/:queueName/consumer-groups` | POST | Create consumer group | queue:write |
| `/api/v1/queues/:setupId/:queueName/consumer-groups` | GET | List consumer groups | queue:read |
| `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | GET | Get consumer group details | queue:read |
| `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName` | DELETE | Delete consumer group | queue:delete |
| `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members` | POST | Join consumer group | queue:write |
| `/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId` | DELETE | Leave consumer group | queue:write |
| `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | POST | Update subscription options | queue:write |
| `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | GET | Get subscription options | queue:read |
| `/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription` | DELETE | Delete subscription options | queue:delete |

### 13.9 User Endpoints (Webhook Subscriptions)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions` | POST | Create webhook subscription | queue:write |
| `/api/v1/webhook-subscriptions/:subscriptionId` | GET | Get webhook subscription | queue:read |
| `/api/v1/webhook-subscriptions/:subscriptionId` | DELETE | Delete webhook subscription | queue:delete |

### 13.10 User Endpoints (Dead Letter Queue)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/setups/:setupId/deadletter/messages` | GET | List dead letter messages | queue:read |
| `/api/v1/setups/:setupId/deadletter/messages/:messageId` | GET | Get dead letter message | queue:read |
| `/api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess` | POST | Reprocess dead letter message | queue:write |
| `/api/v1/setups/:setupId/deadletter/messages/:messageId` | DELETE | Delete dead letter message | queue:delete |
| `/api/v1/setups/:setupId/deadletter/stats` | GET | Get dead letter statistics | queue:read |
| `/api/v1/setups/:setupId/deadletter/cleanup` | POST | Cleanup old dead letters | queue:purge |

### 13.11 User Endpoints (Subscription Lifecycle)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/setups/:setupId/subscriptions/:topic` | GET | List subscriptions for topic | queue:read |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName` | GET | Get subscription details | queue:read |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause` | POST | Pause subscription | queue:write |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume` | POST | Resume subscription | queue:write |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat` | POST | Update subscription heartbeat | queue:write |
| `/api/v1/setups/:setupId/subscriptions/:topic/:groupName` | DELETE | Cancel subscription | queue:delete |

### 13.12 User Endpoints (Health & Monitoring)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/health` | GET | Global health check | None (public) |
| `/api/v1/setups/:setupId/health` | GET | Get overall health for setup | database:view |
| `/api/v1/setups/:setupId/health/components` | GET | List component health | database:view |
| `/api/v1/setups/:setupId/health/components/:name` | GET | Get component health | database:view |

### 13.13 User Endpoints (Management API)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/management/overview` | GET | Get system overview | database:view |
| `/api/v1/management/queues` | GET | List all queues | queue:read |
| `/api/v1/management/queues` | POST | Create queue | queue:create |
| `/api/v1/management/queues/:queueId` | PUT | Update queue | queue:write |
| `/api/v1/management/queues/:queueId` | DELETE | Delete queue | queue:delete |
| `/api/v1/management/consumer-groups` | GET | List all consumer groups | queue:read |
| `/api/v1/management/consumer-groups` | POST | Create consumer group | queue:write |
| `/api/v1/management/consumer-groups/:groupId` | DELETE | Delete consumer group | queue:delete |
| `/api/v1/management/event-stores` | GET | List all event stores | eventStore:read |
| `/api/v1/management/event-stores` | POST | Create event store | eventStore:create |
| `/api/v1/management/event-stores/:storeId` | DELETE | Delete event store | eventStore:delete |
| `/api/v1/management/messages` | GET | List all messages | queue:read |
| `/api/v1/management/metrics` | GET | Get system metrics | database:view |

### 13.14 User Endpoints (Queue Details)

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/queues/:setupId/:queueName` | GET | Get queue details | queue:read |
| `/api/v1/queues/:setupId/:queueName/consumers` | GET | Get queue consumers | queue:read |
| `/api/v1/queues/:setupId/:queueName/bindings` | GET | Get queue bindings | queue:read |
| `/api/v1/queues/:setupId/:queueName/messages` | GET | Get queue messages | queue:read |
| `/api/v1/queues/:setupId/:queueName/publish` | POST | Publish message to queue | queue:write |
| `/api/v1/queues/:setupId/:queueName/purge` | POST | Purge queue | queue:purge |
| `/api/v1/queues/:setupId/:queueName/pause` | POST | Pause queue | queue:write |
| `/api/v1/queues/:setupId/:queueName/resume` | POST | Resume queue | queue:write |
| `/api/v1/queues/:setupId/:queueName` | DELETE | Delete queue | queue:delete |

### 13.15 Real-Time Streaming Endpoints

| Endpoint | Method | Description | Required Permission |
|----------|--------|-------------|-------------------|
| `/api/v1/queues/:setupId/:queueName/stream` | GET | Stream queue messages (SSE) | queue:read |
| `/api/v1/eventstores/:setupId/:storeName/events/stream` | GET | Stream event store events (SSE) | eventStore:read |
| `/ws/queues/:setupId/:queueName` | WebSocket | WebSocket queue stream | queue:read |

**Note:** All endpoints require JWT authentication except `/api/v1/health` (global health check).

---

## 14. Implementation Plan

### 14.1 Phase 1: SQLite Management Database (Week 1-2)
- [ ] Create `peegeeq-auth` module
- [ ] Implement SQLite database schema (with `password_must_change` field)
- [ ] Implement `SQLiteService` (CRUD operations for all tables)
- [ ] Implement `EncryptionService` (AES-256-GCM for credentials)
- [ ] Implement `ManagementDatabaseInitializer` (auto-initialization on first startup)
- [ ] Implement secure password generation (20 chars, alphanumeric + symbols)
- [ ] Implement credential logging with formatted banner
- [ ] Seed default roles (admin, developer, viewer)
- [ ] Add configuration in `application.properties` (`peegeeq.management.db.path`)
- [ ] Test SQLite operations
- [ ] Test auto-initialization flow

### 14.2 Phase 2: Authentication (Week 3-4)
- [ ] Implement `AuthService` (admin login, user login, logout)
- [ ] Implement JWT generation/validation (with `password_must_change` check)
- [ ] Implement password hashing (bcrypt)
- [ ] Implement refresh token management
- [ ] Implement admin password change endpoint (`/api/v1/admin/change-password`)
- [ ] Add authentication middleware to REST API
- [ ] Create login page in UI (admin + user)
- [ ] Add "must change password" flow in UI
- [ ] Test authentication flow
- [ ] Test password change flow

### 14.3 Phase 3: Tenant Management (Week 5-6)
- [ ] Implement `TenantService` (CRUD operations)
- [ ] Implement tenant creation (with schema initialization)
- [ ] Implement tenant deletion (with cleanup)
- [ ] Implement `TenantConnectionManager` (connection pooling)
- [ ] Create tenant management UI (admin only)
- [ ] Test tenant isolation
- [ ] Test connection pooling

### 14.4 Phase 4: User Management & Authorization (Week 7-8)
- [ ] Implement `UserService` (CRUD operations)
- [ ] Implement `RoleService` (CRUD operations)
- [ ] Implement permission checking middleware
- [ ] Add authorization to all API endpoints
- [ ] Create user management UI (admin only)
- [ ] Create role management UI (admin only)
- [ ] Test authorization rules
- [ ] Security testing

---

## 15. Example API Flows

### 15.1 Flow 1: Admin Creates Tenant Configuration

```
1. Admin logs in
   POST /api/v1/auth/admin/login
   Body: {"username": "admin", "password": "..."}
   → Returns JWT with userType="admin"

2. Admin creates tenant
   POST /api/v1/admin/tenants
   Headers: Authorization: Bearer <admin-jwt>
   Body: {
     "tenantName": "ACME Corporation",
     "pgHost": "localhost",
     "pgDatabase": "saas_platform",
     "pgSchema": "tenant_acme",
     "pgUsername": "peegeeq_user",
     "pgPassword": "..."
   }
   → Validates admin JWT
   → Encrypts PostgreSQL credentials
   → Inserts into SQLite tenant_configs table
   → Connects to PostgreSQL
   → Creates schema: CREATE SCHEMA tenant_acme
   → Initializes PeeGeeQ tables in schema
   → Returns tenant configuration

3. Tenant ready for users
```

### 15.2 Flow 2: Admin Creates User for Tenant

```
1. Admin creates user
   POST /api/v1/admin/users
   Headers: Authorization: Bearer <admin-jwt>
   Body: {
     "username": "john.doe",
     "password": "...",
     "email": "john.doe@acme.com",
     "tenantId": "tenant-uuid",
     "roleId": "developer-role-uuid"
   }
   → Validates admin JWT
   → Hashes password (bcrypt)
   → Inserts into SQLite user_accounts table
   → Returns user info

2. User can now log in
```

### 15.3 Flow 3: User Logs In and Creates Queue

```
1. User logs in
   POST /api/v1/auth/login
   Body: {"username": "john.doe", "password": "..."}
   → Queries SQLite (joins user_accounts + tenant_configs + user_roles)
   → Verifies password
   → Decrypts PostgreSQL credentials for tenant
   → Generates JWT with:
      - userType="user"
      - tenantId, schemaName
      - role, permissions
   → Returns JWT

2. User creates queue
   POST /api/v1/queues/create
   Headers: Authorization: Bearer <user-jwt>
   Body: {"queueName": "orders_queue"}
   → Middleware validates JWT
   → Extracts tenantId="tenant-uuid", schemaName="tenant_acme"
   → Checks permission "queue:create" ✓
   → Gets connection pool for tenant (search_path=tenant_acme)
   → Executes: CREATE TABLE orders_queue (...)
   → Actual execution: CREATE TABLE tenant_acme.orders_queue (...)
   → Returns success

3. Queue created in tenant_acme schema only
```

### 15.4 Flow 4: Cross-Tenant Isolation

```
Scenario: User from tenant_globex tries to access tenant_acme's queue

1. User from tenant_globex logs in
   POST /api/v1/auth/login
   Body: {"username": "jane.smith", "password": "..."}
   → Returns JWT with:
      - tenantId="globex-uuid"
      - schemaName="tenant_globex"

2. User tries to list queues
   GET /api/v1/queues
   Headers: Authorization: Bearer <globex-user-jwt>
   → Middleware validates JWT
   → Extracts tenantId="globex-uuid", schemaName="tenant_globex"
   → Gets connection pool for tenant_globex (search_path=tenant_globex)
   → Executes: SELECT * FROM queue_messages
   → Actual execution: SELECT * FROM tenant_globex.queue_messages
   → Returns only queues in tenant_globex schema
   → orders_queue NOT visible (exists only in tenant_acme)

3. Complete isolation enforced
```

---

## 16. Testing Strategy

### 16.1 Unit Tests
- Password hashing/validation (bcrypt)
- JWT generation/validation
- Permission checking logic
- Credential encryption/decryption (AES-256-GCM)
- SQLite CRUD operations

### 16.2 Integration Tests
- Admin login flow
- User login flow
- Token refresh flow
- Tenant creation (with schema initialization)
- User creation
- Authorization checks on all endpoints
- Connection pooling (tenant-specific)

### 16.3 Multi-Tenant Isolation Tests

**Critical Test:**
```java
@Test
void testTenantIsolation() {
    // 1. Admin creates two tenants
    TenantConfig tenantA = adminCreateTenant("ACME", "tenant_acme");
    TenantConfig tenantB = adminCreateTenant("Globex", "tenant_globex");

    // 2. Admin creates users for each tenant
    User userA = adminCreateUser("user_a", tenantA.getTenantId(), "developer");
    User userB = adminCreateUser("user_b", tenantB.getTenantId(), "developer");

    // 3. User A logs in and creates queue
    String tokenA = login(userA);
    createQueue(tokenA, "orders_queue");

    // 4. Verify queue exists in tenant_acme schema only
    assertQueueExists("tenant_acme", "orders_queue");
    assertQueueNotExists("tenant_globex", "orders_queue");

    // 5. User B logs in and tries to access tenant A's queue
    String tokenB = login(userB);
    List<String> queuesB = listQueues(tokenB);

    // 6. Verify complete isolation
    assertThat(queuesB).doesNotContain("orders_queue");

    // 7. User B creates their own queue
    createQueue(tokenB, "trades_queue");

    // 8. Verify each tenant sees only their own queues
    List<String> queuesA = listQueues(tokenA);
    assertThat(queuesA).contains("orders_queue").doesNotContain("trades_queue");
    assertThat(queuesB).contains("trades_queue").doesNotContain("orders_queue");
}
```

### 16.4 Security Tests
- SQL injection attempts (especially in schema names)
- JWT tampering (changing tenantId, permissions)
- Permission bypass attempts
- Cross-tenant access attempts
- Brute force login attempts
- Token expiration handling
- Credential encryption/decryption
- Schema name validation (reserved names, special characters)

---

## 17. Deployment Patterns

### 17.1 Pattern 1: Schema-per-Tenant (Recommended for Most Use Cases)

**Architecture:**
```
SQLite: ~/.peegeeq/peegeeq_management.db
  ├── Admin: admin
  ├── Tenant: ACME Corp → tenant_acme
  ├── Tenant: Globex Corp → tenant_globex
  └── Users: john.doe (ACME), jane.smith (Globex)

PostgreSQL: saas_platform
  ├── Schema: tenant_acme
  │   ├── queue_messages
  │   ├── outbox
  │   ├── events_bitemporal
  │   └── orders_queue (user-created)
  ├── Schema: tenant_globex
  │   ├── queue_messages
  │   ├── outbox
  │   ├── events_bitemporal
  │   └── trades_queue (user-created)
```

**Benefits:**
- ✅ Single PostgreSQL database = simpler infrastructure
- ✅ Strong data isolation via PostgreSQL schemas
- ✅ Per-tenant backup/restore via `pg_dump --schema=tenant_acme`
- ✅ Easy tenant offboarding: `DROP SCHEMA tenant_acme CASCADE`
- ✅ Cost-effective for many small/medium tenants

**Drawbacks:**
- ❌ All tenants share same database resources
- ❌ One tenant's heavy load affects others
- ❌ Schema limit (PostgreSQL has no hard limit but performance degrades with thousands)

**Best For:** SaaS applications with many small to medium tenants

### 17.2 Pattern 2: Database-per-Tenant (For Large/Enterprise Tenants)

**Architecture:**
```
SQLite: ~/.peegeeq/peegeeq_management.db
  ├── Admin: admin
  ├── Tenant: ACME Corp → pg_host=db1.example.com, pg_database=tenant_acme_db
  └── Tenant: Globex Corp → pg_host=db2.example.com, pg_database=tenant_globex_db

PostgreSQL Instance 1 (db1.example.com):
  Database: tenant_acme_db
    └── Schema: tenant_acme
        ├── queue_messages
        ├── outbox
        └── ...

PostgreSQL Instance 2 (db2.example.com):
  Database: tenant_globex_db
    └── Schema: tenant_globex
        ├── queue_messages
        ├── outbox
        └── ...
```

**Benefits:**
- ✅ Complete resource isolation
- ✅ Independent scaling per tenant
- ✅ Better for large/enterprise tenants
- ✅ Easier to move tenants to different servers
- ✅ Can use different PostgreSQL versions per tenant

**Drawbacks:**
- ❌ More complex infrastructure
- ❌ Higher resource overhead
- ❌ More connection pools required

**Best For:** Enterprise tenants with dedicated SLAs, compliance requirements, or high resource needs

### 17.3 Pattern 3: Hybrid (Recommended for Production)

**Architecture:**
```
SQLite: ~/.peegeeq/peegeeq_management.db
  ├── Admin: admin
  ├── Tenant: Small1 → pg_database=saas_platform, pg_schema=tenant_small1
  ├── Tenant: Small2 → pg_database=saas_platform, pg_schema=tenant_small2
  ├── Tenant: Medium1 → pg_database=saas_platform, pg_schema=tenant_medium1
  └── Tenant: Enterprise1 → pg_host=dedicated.example.com, pg_database=tenant_enterprise1_db

PostgreSQL: saas_platform (shared)
  ├── Schema: tenant_small1
  ├── Schema: tenant_small2
  └── Schema: tenant_medium1

PostgreSQL: dedicated.example.com (dedicated)
  └── Database: tenant_enterprise1_db
      └── Schema: tenant_enterprise1
```

**Benefits:**
- ✅ Flexibility to match tenant size/requirements
- ✅ Cost-effective for small tenants
- ✅ Isolation for large tenants
- ✅ Can migrate tenants between patterns as they grow

**Strategy:**
1. Start all tenants in shared database (Pattern 1)
2. Monitor resource usage per tenant
3. Migrate large tenants to dedicated databases (Pattern 2) when needed
4. SQLite `tenant_configs` table tracks which pattern each tenant uses

---

## 18. Security Considerations

### 18.1 Credential Security

**PostgreSQL Credentials:**
- Stored encrypted in SQLite using AES-256-GCM
- Master encryption key in environment variable (never in code)
- Credentials decrypted only when creating connection pools
- Decrypted credentials never logged or exposed in API responses

**User Passwords:**
- Hashed using bcrypt (cost factor 12)
- Never stored in plaintext
- Never returned in API responses
- Password reset requires admin intervention

**JWT Secrets:**
- Stored in environment variable
- Rotated periodically (requires re-login for all users)
- Different secrets for access and refresh tokens (optional)

### 18.2 Multi-Tenant Isolation

**Three Layers of Defense:**

1. **JWT Layer:**
   - Token contains `tenantId` and `schemaName`
   - Middleware validates token on every request
   - Tampering with JWT invalidates signature

2. **Connection Pool Layer:**
   - Separate pool per tenant
   - Each pool has `search_path` set to tenant's schema
   - No way to access other tenant's pool

3. **Database Layer:**
   - PostgreSQL schema isolation
   - All queries automatically scoped to tenant's schema
   - Cross-schema access requires explicit qualification (blocked by app)

**Attack Scenarios:**

| Attack | Defense |
|--------|---------|
| User modifies JWT `tenantId` | JWT signature validation fails → 401 |
| User tries SQL injection in schema name | Schema name validated on tenant creation, parameterized queries |
| User tries to access other tenant's data | Connection pool uses `search_path`, queries scoped to their schema |
| Admin credentials compromised | Admin can only manage tenants, cannot access tenant data directly |
| PostgreSQL credentials compromised | Only affects one tenant (credentials are tenant-specific) |

### 18.3 API Security

**Rate Limiting:**
- Implement rate limiting per IP address
- Stricter limits for login endpoints (prevent brute force)
- Per-tenant rate limiting for API operations

**Input Validation:**
- Validate all inputs (schema names, usernames, etc.)
- Reject reserved PostgreSQL schema names (pg_*, information_schema, public)
- Sanitize all user inputs
- Use parameterized queries (never string concatenation)

**HTTPS Only:**
- All API endpoints require HTTPS in production
- JWT tokens transmitted only over HTTPS
- Redirect HTTP to HTTPS

**CORS Configuration:**
- Whitelist allowed origins
- Restrict to specific domains in production
- Include credentials in CORS policy

### 18.4 Audit Logging

**What to Log:**
- All admin operations (tenant creation, user creation, etc.)
- All authentication attempts (success and failure)
- All authorization failures (403 responses)
- All tenant data access (with tenant context)
- All credential decryption operations

**Log Format:**
```json
{
  "timestamp": "2025-12-27T10:00:00Z",
  "level": "INFO",
  "event": "user.login.success",
  "userId": "user-uuid",
  "username": "john.doe",
  "tenantId": "tenant-uuid",
  "tenantName": "ACME Corporation",
  "ipAddress": "192.168.1.100",
  "userAgent": "Mozilla/5.0..."
}
```

**Storage:**
- Store audit logs in separate table in SQLite
- Rotate logs periodically
- Export to external logging system (ELK, Splunk, etc.)

### 18.5 Secrets Management

**Environment Variables (Development):**
```bash
PEEGEEQ_MANAGEMENT_DB_PATH=~/.peegeeq/peegeeq_management.db
PEEGEEQ_ENCRYPTION_KEY=<base64-encoded-256-bit-key>
PEEGEEQ_JWT_SECRET=<base64-encoded-secret>
```

**Production Secrets Management:**
- Use HashiCorp Vault, AWS Secrets Manager, or Azure Key Vault
- Rotate encryption keys periodically
- Rotate JWT secrets periodically (requires re-login)
- Never commit secrets to version control
- Use different secrets per environment (dev, staging, prod)

---

## 19. Advantages and Trade-offs

### 19.1 Advantages of SQLite-Based Design

**✅ Simplicity**
- Single file database (`peegeeq_management.db`)
- No separate database server required
- Easy backup (copy single file)
- Easy deployment (embedded database)
- Zero configuration for development

**✅ Clear Separation of Concerns**
- Management Plane (SQLite): Admin accounts, tenant configs, user management
- Data Plane (PostgreSQL): Tenant data (queues, events, outbox)
- No mixing of authentication data with tenant data
- Easy to understand and maintain

**✅ Flexibility**
- Admin can configure multiple PostgreSQL databases
- Each tenant can use different PostgreSQL instance
- Easy to add new tenants without touching PostgreSQL
- Support for hybrid deployment patterns

**✅ Security**
- PostgreSQL credentials encrypted in SQLite
- Tenants never see each other's credentials
- Complete schema-based isolation in PostgreSQL
- Three-layer defense (JWT, connection pool, database)

**✅ Portability**
- SQLite database can be moved between servers
- No dependency on specific PostgreSQL instance for auth
- Easy to migrate management data
- Works on Windows, Linux, macOS

**✅ Performance**
- Fast reads (auth checks use JWT, not database)
- Minimal latency for credential lookups
- Connection pooling reduces overhead
- No network round-trip for management queries

### 19.2 Trade-offs and Mitigations

**❌ Single Point of Failure**
- **Issue:** SQLite database is single file
- **Mitigation:**
  - Regular backups (automated)
  - File replication (rsync, litestream)
  - Consider PostgreSQL for production HA
  - Keep SQLite for development/small deployments

**❌ Concurrency Limitations**
- **Issue:** SQLite has limited write concurrency
- **Mitigation:**
  - Read-heavy workload (auth checks use JWT, not database)
  - Connection pooling reduces database writes
  - Admin operations are infrequent
  - User creation/updates are infrequent

**❌ No Built-in Replication**
- **Issue:** SQLite doesn't have built-in replication
- **Mitigation:**
  - File-based replication (litestream for real-time replication)
  - Periodic backups to S3/Azure Blob
  - Migrate to PostgreSQL for HA requirements
  - Use read replicas for PostgreSQL (data plane)

**❌ Encryption Key Management**
- **Issue:** Master encryption key in environment variable
- **Mitigation:**
  - Use secrets management (HashiCorp Vault, AWS Secrets Manager)
  - Rotate keys periodically
  - Use different keys per environment
  - Never commit keys to version control

**❌ Scalability Limits**
- **Issue:** SQLite may not scale to millions of users
- **Mitigation:**
  - Sufficient for most use cases (thousands of tenants, tens of thousands of users)
  - Migrate to PostgreSQL if needed (schema compatible)
  - Horizontal scaling via multiple API instances (read-only SQLite replicas)

### 19.3 When to Migrate to PostgreSQL for Management Plane

**Consider PostgreSQL when:**
- More than 10,000 users
- More than 1,000 tenants
- High availability requirements (99.99%+ uptime)
- Multi-region deployment
- Frequent admin operations (high write concurrency)
- Compliance requirements for database replication

**Migration Path:**
1. SQLite schema is compatible with PostgreSQL
2. Export SQLite data to SQL
3. Import into PostgreSQL
4. Update connection string
5. No code changes required (same SQL syntax)

---

## 20. Open Questions and Future Enhancements

### 20.1 Open Questions

1. **Cross-Tenant Users:** Should a user be able to belong to multiple tenants?
   - Current design: No (one user = one tenant)
   - Alternative: User can have multiple tenant associations with different roles

2. **Tenant Limits:** Should we enforce limits on tenants?
   - Max users per tenant
   - Max queues per tenant
   - Storage quotas per tenant
   - Rate limits per tenant

3. **Tenant Migration:** Support for moving tenants between databases?
   - Export tenant schema to SQL
   - Import into new database
   - Update tenant config in SQLite
   - Zero-downtime migration?

4. **API Keys:** Support for programmatic access without user login?
   - Tenant-scoped API keys
   - Role-based API keys
   - Expiration and rotation

5. **SSO Integration:** Future support for SAML/OAuth2?
   - Per-tenant SSO configuration
   - Multiple identity providers
   - Just-in-time user provisioning

6. **Audit Logging:** Should we log all operations with tenant context?
   - Store in SQLite or separate system?
   - Retention policy
   - Export to external logging system

7. **Backup/Restore:** How to backup/restore SQLite and PostgreSQL together?
   - Coordinated backups
   - Point-in-time recovery
   - Disaster recovery procedures

8. **High Availability:** How to replicate SQLite across multiple API instances?
   - Litestream for real-time replication
   - Read replicas
   - Failover procedures

### 20.2 Future Enhancements

**Phase 5: Advanced Features (Future)**
- [ ] API key authentication (tenant-scoped)
- [ ] SSO integration (SAML, OAuth2)
- [ ] Multi-factor authentication (MFA)
- [ ] Password policies (complexity, expiration)
- [ ] Session management (active sessions, force logout)
- [ ] Audit log viewer in UI
- [ ] Tenant usage metrics and quotas
- [ ] Self-service tenant registration
- [ ] Tenant billing integration

**Phase 6: High Availability (Future)**
- [ ] PostgreSQL as alternative to SQLite for management plane
- [ ] Multi-region deployment support
- [ ] Read replicas for SQLite (litestream)
- [ ] Automated failover
- [ ] Disaster recovery procedures
- [ ] Backup and restore automation

**Phase 7: Advanced Security (Future)**
- [ ] IP whitelisting per tenant
- [ ] Advanced rate limiting (per tenant, per user)
- [ ] Anomaly detection (unusual access patterns)
- [ ] Security event notifications
- [ ] Compliance reporting (SOC 2, GDPR)
- [ ] Data encryption at rest (PostgreSQL)

---

## 21. Conclusion

This design provides a **production-ready, secure, multi-tenant authentication and authorization system** for PeeGeeQ that:

✅ **Simple:** SQLite for management plane, no separate auth server required
✅ **Secure:** AES-256 encryption, bcrypt passwords, JWT tokens, three-layer tenant isolation
✅ **Flexible:** Support for multiple deployment patterns (schema-per-tenant, database-per-tenant, hybrid)
✅ **Scalable:** Connection pooling, JWT-based auth, read-heavy workload
✅ **Portable:** SQLite database can be moved between servers, works on all platforms
✅ **Maintainable:** Clear separation of concerns, well-defined APIs, comprehensive testing strategy

The implementation plan provides a clear roadmap for building this system over 8 weeks, with comprehensive testing at each phase.

**Next Steps:**
1. Review and approve this design
2. Set up development environment
3. Begin Phase 1: SQLite Management Database
4. Iterate based on feedback and testing


