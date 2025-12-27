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
20. [Appendix](#appendix)
    - 20.1 Deployment Checklist
    - 20.2 Future Enhancements
    - **20.3 Upgrade and Migration Plan** ⭐
        - 20.3.1 SQLite to PostgreSQL Migration
        - 20.3.2 Encryption Key Management Upgrade
        - 20.3.3 High Availability Upgrade
        - 20.3.4 Service Manager Integration
        - 20.3.5 Scalability Improvements
        - 20.3.6 Multi-Region Deployment
        - 20.3.7 Compliance and Audit Enhancements
        - 20.3.8 Migration Timeline Summary
        - 20.3.9 Rollback and Risk Mitigation
21. [Conclusion](#conclusion)
    - 21.1 Implementation Roadmap
    - 21.2 Evolution Path
    - 21.3 Trade-off Management
    - 21.4 Service Manager Integration
    - 21.5 Next Steps
    - 21.6 Success Metrics

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
-- NOTE: Users can belong to multiple tenants (see user_tenant_roles table)
CREATE TABLE user_accounts (
    user_id TEXT PRIMARY KEY,  -- UUID as TEXT
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,  -- bcrypt
    email TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    last_login TEXT,
    is_active INTEGER DEFAULT 1
);

CREATE INDEX idx_user_username ON user_accounts(username);

-- User-Tenant-Role associations (many-to-many)
-- A user can have different roles in different tenants
CREATE TABLE user_tenant_roles (
    id TEXT PRIMARY KEY,  -- UUID as TEXT
    user_id TEXT NOT NULL REFERENCES user_accounts(user_id) ON DELETE CASCADE,
    tenant_id TEXT NOT NULL REFERENCES tenant_configs(tenant_id) ON DELETE CASCADE,
    role_id TEXT NOT NULL REFERENCES roles(role_id),
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    is_active INTEGER DEFAULT 1,
    UNIQUE(user_id, tenant_id)  -- One role per user per tenant
);

CREATE INDEX idx_user_tenant_roles_user ON user_tenant_roles(user_id);
CREATE INDEX idx_user_tenant_roles_tenant ON user_tenant_roles(tenant_id);
CREATE INDEX idx_user_tenant_roles_role ON user_tenant_roles(role_id);

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

### 12.2 User JWT Token (Multi-Tenant)

**Claims:**
```json
{
  "sub": "user-uuid",
  "username": "john.doe",
  "userType": "user",
  "tenantId": "tenant-uuid",
  "tenantName": "ACME Corporation",
  "schemaName": "tenant_acme",
  "role": "developer",
  "roleId": "role-uuid",
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
  "availableTenants": [
    {
      "tenantId": "tenant-uuid-1",
      "tenantName": "ACME Corporation",
      "roleId": "role-uuid-1",
      "roleName": "developer"
    },
    {
      "tenantId": "tenant-uuid-2",
      "tenantName": "BETA Industries",
      "roleId": "role-uuid-2",
      "roleName": "viewer"
    }
  ],
  "iat": 1703721600,
  "exp": 1703722500,
  "type": "access"
}
```

**Characteristics:**
- Includes current tenant context (tenantId, tenantName, schemaName)
- Includes role and permissions for current tenant
- Includes `availableTenants` array for tenant switching
- `schemaName` is critical for connection pool isolation
- Short expiration (15 minutes)
- User can switch tenants without re-login (see Section 12.5)

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

### 12.4 Tenant Switching

**Endpoint:** `POST /api/v1/auth/switch-tenant`

**Request:**
```json
{
  "tenantId": "tenant-uuid-2"
}
```

**Process:**
1. Validate current JWT token
2. Extract userId from token
3. Verify user has access to requested tenant (check `user_tenant_roles` table)
4. Get role and permissions for new tenant
5. Generate new JWT with updated tenant context
6. Return new token

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "currentTenant": {
    "tenantId": "tenant-uuid-2",
    "tenantName": "BETA Industries",
    "roleId": "role-uuid-2",
    "roleName": "viewer"
  },
  "availableTenants": [...]
}
```

**UI Flow:**
1. User clicks "Switch Tenant" dropdown
2. Selects tenant from `availableTenants` list
3. Frontend calls `/api/v1/auth/switch-tenant`
4. Frontend stores new token
5. UI refreshes with new tenant context

### 12.5 Token Validation

**Middleware Process:**
1. Extract token from `Authorization: Bearer <token>` header
2. Verify signature using JWT secret
3. Check expiration
4. Extract claims
5. For user tokens: verify tenant still exists and is enabled
6. For user tokens: verify user still exists and is enabled
7. For user tokens: verify user still has access to tenant (check `user_tenant_roles`)
8. Populate request context with user/admin info
9. Continue to handler

---

## 13. API Endpoint Reference

**Note:** No setup endpoint is required. The system auto-initializes on first startup and logs the default admin credentials.

### 13.1 Authentication Endpoints

| Endpoint | Method | Description | Auth Required |
|----------|--------|-------------|---------------|
| `/api/v1/auth/admin/login` | POST | Admin login | None |
| `/api/v1/auth/login` | POST | User login (returns available tenants) | None |
| `/api/v1/auth/logout` | POST | Logout (invalidate refresh token) | JWT |
| `/api/v1/auth/refresh` | POST | Refresh access token | Refresh Token |
| `/api/v1/auth/switch-tenant` | POST | Switch to different tenant context | User JWT |
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
| `/api/v1/admin/users` | POST | Create user (with initial tenant/role) | Admin |
| `/api/v1/admin/users/{id}` | GET | Get user details (includes all tenants) | Admin |
| `/api/v1/admin/users/{id}` | PUT | Update user (email, password, etc.) | Admin |
| `/api/v1/admin/users/{id}` | DELETE | Delete user (removes all tenant associations) | Admin |
| `/api/v1/admin/users/{id}/reset-password` | POST | Reset user password | Admin |
| `/api/v1/admin/users/{id}/tenants` | GET | List user's tenant associations | Admin |
| `/api/v1/admin/users/{id}/tenants` | POST | Add user to tenant with role | Admin |
| `/api/v1/admin/users/{id}/tenants/{tenantId}` | PUT | Update user's role in tenant | Admin |
| `/api/v1/admin/users/{id}/tenants/{tenantId}` | DELETE | Remove user from tenant | Admin |

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

### 15.2 Flow 2: Admin Creates Multi-Tenant User

```
1. Admin creates user with initial tenant
   POST /api/v1/admin/users
   Headers: Authorization: Bearer <admin-jwt>
   Body: {
     "username": "john.doe",
     "password": "...",
     "email": "john.doe@acme.com",
     "tenantId": "tenant-uuid-1",
     "roleId": "developer-role-uuid"
   }
   → Validates admin JWT
   → Hashes password (bcrypt)
   → Inserts into SQLite user_accounts table
   → Inserts into user_tenant_roles table (user + tenant-1 + developer role)
   → Returns user info

2. Admin adds user to second tenant
   POST /api/v1/admin/users/{userId}/tenants
   Headers: Authorization: Bearer <admin-jwt>
   Body: {
     "tenantId": "tenant-uuid-2",
     "roleId": "viewer-role-uuid"
   }
   → Validates admin JWT
   → Verifies user exists
   → Verifies tenant exists
   → Inserts into user_tenant_roles table (user + tenant-2 + viewer role)
   → Returns updated user info

3. User can now log in with access to both tenants
```

### 15.3 Flow 3: Multi-Tenant User Logs In and Creates Queue

```
1. User logs in
   POST /api/v1/auth/login
   Body: {"username": "john.doe", "password": "..."}
   → Queries SQLite:
      - user_accounts (verify password)
      - user_tenant_roles (get all tenant associations)
      - tenant_configs (get tenant details)
      - roles (get permissions for each tenant)
   → Verifies password
   → Selects default tenant (first tenant or last used)
   → Decrypts PostgreSQL credentials for default tenant
   → Generates JWT with:
      - userType="user"
      - tenantId, tenantName, schemaName (for default tenant)
      - role, permissions (for default tenant)
      - availableTenants: [{tenantId, tenantName, roleId, roleName}, ...]
   → Returns JWT + available tenants

2. User creates queue in default tenant (ACME)
   POST /api/v1/queues/create
   Headers: Authorization: Bearer <user-jwt>
   Body: {"queueName": "orders_queue"}
   → Middleware validates JWT
   → Extracts tenantId="tenant-uuid-1", schemaName="tenant_acme"
   → Checks permission "queue:create" ✓ (developer role)
   → Gets connection pool for tenant (search_path=tenant_acme)
   → Executes: CREATE TABLE orders_queue (...)
   → Actual execution: CREATE TABLE tenant_acme.orders_queue (...)
   → Returns success

3. User switches to second tenant (BETA)
   POST /api/v1/auth/switch-tenant
   Headers: Authorization: Bearer <user-jwt>
   Body: {"tenantId": "tenant-uuid-2"}
   → Validates current JWT
   → Verifies user has access to tenant-uuid-2 (check user_tenant_roles)
   → Gets role and permissions for tenant-2 (viewer role)
   → Generates new JWT with updated tenant context
   → Returns new JWT

4. User tries to create queue in BETA tenant
   POST /api/v1/queues/create
   Headers: Authorization: Bearer <new-user-jwt>
   Body: {"queueName": "beta_queue"}
   → Middleware validates JWT
   → Extracts tenantId="tenant-uuid-2", schemaName="tenant_beta"
   → Checks permission "queue:create" ✗ (viewer role - no create permission)
   → Returns 403 Forbidden

5. Queues are isolated by tenant schema
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

### 20.1 Design Decisions (Resolved)

#### ✅ **Decision 1: Cross-Tenant Users**
**Question:** Should a user be able to belong to multiple tenants?

**Decision:** **YES** - Users can have multiple tenant associations with different roles per tenant.

**Rationale:**
- Enterprise users often need access to multiple client tenants
- Consultants/support staff need multi-tenant access
- Simplifies user management (one login, multiple contexts)
- Aligns with modern SaaS patterns

**Implementation Impact:**
- Requires `user_tenant_roles` junction table (many-to-many)
- JWT must include `tenantId` for current context
- UI must support tenant switching
- API endpoints must validate user has access to requested tenant

**Schema Changes Required:**
```sql
-- Remove tenant_id and role_id from user_accounts
-- Add user_tenant_roles junction table
CREATE TABLE user_tenant_roles (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    role_id TEXT NOT NULL,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    is_active INTEGER DEFAULT 1,
    FOREIGN KEY (user_id) REFERENCES user_accounts(user_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id) REFERENCES tenant_configs(tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(role_id),
    UNIQUE(user_id, tenant_id)
);

-- Modified user_accounts table (no tenant_id, no role_id)
CREATE TABLE user_accounts (
    user_id TEXT PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    email TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    last_login TEXT,
    is_active INTEGER DEFAULT 1
);
```

**JWT Changes:**
```json
{
  "userId": "user-uuid",
  "username": "john.doe",
  "userType": "user",
  "tenantId": "tenant-uuid",        // Current active tenant
  "tenantName": "ACME Corporation", // Current tenant name
  "roleId": "role-uuid",            // Role for current tenant
  "permissions": {...},             // Permissions for current tenant
  "availableTenants": [             // All tenants user has access to
    {"tenantId": "tenant-1", "tenantName": "ACME", "roleId": "role-1"},
    {"tenantId": "tenant-2", "tenantName": "BETA", "roleId": "role-2"}
  ],
  "exp": 1234567890
}
```

**API Changes:**
```java
// New endpoint: Switch tenant context
POST /api/v1/auth/switch-tenant
Body: {"tenantId": "tenant-uuid"}
Response: {new JWT with updated tenantId and permissions}

// Modified login response includes available tenants
POST /api/v1/auth/login
Response: {
  "token": "...",
  "user": {...},
  "currentTenant": {...},
  "availableTenants": [...]
}
```

---

#### ✅ **Decision 2: Tenant Limits**
**Question:** Should we enforce limits on tenants?

**Decision:** **FUTURE ENHANCEMENT** - Not in Phase 1-4, add in Phase 5.

**Rationale:**
- Adds complexity to initial implementation
- Can be added later without schema changes
- Most deployments won't need limits initially
- Better to validate real-world usage patterns first

**Future Implementation (Phase 5):**
- Max users per tenant
- Max queues per tenant
- Storage quotas per tenant
- Rate limits per tenant
- Configurable per-tenant or global defaults

---

#### ✅ **Decision 3: Tenant Migration**
**Question:** Support for moving tenants between databases?

**Decision:** **FUTURE ENHANCEMENT** - Not in Phase 1-4, add in Phase 5.

**Rationale:**
- Complex feature requiring careful design
- Low priority for initial deployment
- Can be built on top of existing backup/restore
- Requires zero-downtime migration strategy

**Future Implementation (Phase 5):**
- Export tenant schema to SQL
- Import into new database
- Update tenant config in SQLite
- Zero-downtime migration with dual-write

---

#### ✅ **Decision 4: API Keys**
**Question:** Support for programmatic access without user login?

**Decision:** **YES** - Role-based API keys with multi-tenant support (Phase 5).

**Rationale:**
- Essential for programmatic access (CI/CD, integrations)
- Aligns with cross-tenant users decision
- User can have API keys for multiple tenants
- Each API key has specific role and permissions

**Implementation (Phase 5):**
```sql
CREATE TABLE api_keys (
    api_key_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    role_id TEXT NOT NULL,
    key_hash TEXT NOT NULL,        -- bcrypt hash of API key
    key_prefix TEXT NOT NULL,      -- First 8 chars for identification
    description TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    expires_at TEXT,
    last_used_at TEXT,
    is_active INTEGER DEFAULT 1,
    FOREIGN KEY (user_id) REFERENCES user_accounts(user_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id) REFERENCES tenant_configs(tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(role_id)
);
```

**API Key Format:** `pgq_<tenant-prefix>_<random-32-chars>`
- Example: `pgq_acme_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6`

---

#### ✅ **Decision 5: Multi-Factor Authentication (MFA)**
**Question:** Future support for MFA?

**Decision:** **FUTURE ENHANCEMENT** - Phase 5.

**Rationale:**
- Important for security but not critical for initial deployment
- Requires additional infrastructure (SMS, TOTP, email)
- Can be added without breaking existing authentication

---

#### ✅ **Decision 6: SSO Integration**
**Question:** Future support for SAML/OAuth2?

**Decision:** **FUTURE ENHANCEMENT** - Phase 5.

**Rationale:**
- Enterprise feature, not needed for initial deployment
- Complex integration requiring careful design
- Per-tenant SSO configuration needed
- Can be added alongside existing password authentication

---

#### ✅ **Decision 7: Audit Logging**
**Question:** Should we log all operations with tenant context?

**Decision:** **YES** - Implement in Phase 1 (basic), enhance in Phase 5.

**Phase 1 Implementation:**
- Store in SQLite `audit_log` table
- Log authentication events only
- 90-day retention

**Phase 5 Enhancements:**
- Log all CRUD operations
- Export to external logging system (Elasticsearch, CloudWatch)
- Configurable retention per tenant
- Compliance reporting (GDPR, SOC 2)

---

#### ✅ **Decision 8: Backup/Restore**
**Question:** How to backup/restore SQLite and PostgreSQL together?

**Decision:** **FUTURE ENHANCEMENT** - Phase 6 (HA).

**Rationale:**
- SQLite backup is simple (copy file)
- PostgreSQL backup is standard (pg_dump)
- Coordinated backups needed for consistency
- Part of broader HA/DR strategy

**Future Implementation (Phase 6):**
- Coordinated backups with transaction consistency
- Point-in-time recovery
- Automated backup to S3/Azure Blob
- Disaster recovery procedures

---

#### ✅ **Decision 9: High Availability**
**Question:** How to replicate SQLite across multiple API instances?

**Decision:** **FUTURE ENHANCEMENT** - Phase 6 (HA).

**Rationale:**
- Single instance sufficient for initial deployment
- Litestream provides simple replication when needed
- PostgreSQL migration (Section 20.3.1) is better long-term solution

**Future Implementation (Phase 6):**
- Litestream for real-time SQLite replication
- Read replicas for scalability
- Automated failover procedures
- Or migrate to PostgreSQL for management plane

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

## 20.3 Upgrade and Migration Plan

This section provides a detailed roadmap for addressing the trade-offs identified in Section 19 and evolving the authentication system to meet enterprise requirements.

### 20.3.1 SQLite to PostgreSQL Migration (Management Plane)

**When to Migrate:**
- More than 10,000 users
- More than 1,000 tenants
- High availability requirements (99.99%+ uptime)
- Multi-region deployment needs
- Compliance requirements for management data replication

**Migration Strategy:**

**Step 1: Schema Compatibility (Week 1)**
```sql
-- Create PostgreSQL schema matching SQLite structure
CREATE TABLE admin_accounts (
    admin_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    password_must_change BOOLEAN DEFAULT TRUE,
    is_active BOOLEAN DEFAULT TRUE
);

CREATE TABLE tenant_configs (
    tenant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_name VARCHAR(255) UNIQUE NOT NULL,
    pg_host VARCHAR(255) NOT NULL,
    pg_port INTEGER NOT NULL DEFAULT 5432,
    pg_database VARCHAR(255) NOT NULL,
    pg_schema VARCHAR(255) NOT NULL,
    pg_username VARCHAR(255) NOT NULL,
    pg_password_encrypted TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE
);

CREATE TABLE user_accounts (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE
);

CREATE TABLE user_tenant_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES user_accounts(user_id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenant_configs(tenant_id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(role_id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    UNIQUE(user_id, tenant_id)
);

CREATE TABLE roles (
    role_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    permissions JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_log (
    log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    user_id UUID,
    admin_id UUID,
    tenant_id UUID,
    action VARCHAR(255) NOT NULL,
    resource_type VARCHAR(255),
    resource_id VARCHAR(255),
    details JSONB,
    ip_address VARCHAR(45),
    user_agent TEXT
);

-- Indexes for performance
CREATE INDEX idx_user_accounts_username ON user_accounts(username);
CREATE INDEX idx_user_tenant_roles_user_id ON user_tenant_roles(user_id);
CREATE INDEX idx_user_tenant_roles_tenant_id ON user_tenant_roles(tenant_id);
CREATE INDEX idx_user_tenant_roles_role_id ON user_tenant_roles(role_id);
CREATE INDEX idx_audit_log_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_log_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_log_admin_id ON audit_log(admin_id);
CREATE INDEX idx_audit_log_tenant_id ON audit_log(tenant_id);
```

**Step 2: Data Migration Tool (Week 2)**
```java
public class ManagementPlaneMigrationTool {

    private final SQLiteService sqliteService;
    private final PgPool pgPool;

    public Future<MigrationResult> migrate() {
        return migrateAdminAccounts()
            .compose(v -> migrateTenantConfigs())
            .compose(v -> migrateUserAccounts())
            .compose(v -> migrateRoles())
            .compose(v -> migrateAuditLog())
            .compose(v -> validateMigration());
    }

    private Future<Void> migrateAdminAccounts() {
        // Export from SQLite, import to PostgreSQL
        // Preserve UUIDs, timestamps, encrypted data
    }

    private Future<MigrationResult> validateMigration() {
        // Compare row counts, validate data integrity
        // Test authentication against PostgreSQL
    }
}
```

**Step 3: Dual-Write Period (Week 3-4)**
- Write to both SQLite and PostgreSQL
- Read from SQLite (primary)
- Validate PostgreSQL data consistency
- Monitor for discrepancies

**Step 4: Cutover (Week 5)**
- Switch reads to PostgreSQL
- Continue dual-write for 1 week
- Monitor performance and errors
- Keep SQLite as backup

**Step 5: Cleanup (Week 6)**
- Stop writing to SQLite
- Archive SQLite database
- Remove SQLite dependencies from code
- Update documentation

**Rollback Plan:**
- Keep SQLite database for 30 days
- Ability to switch back to SQLite in < 5 minutes
- Automated health checks to detect issues

---

### 20.3.2 Encryption Key Management Upgrade

**Current State:** Master key in environment variable
**Target State:** HashiCorp Vault or AWS Secrets Manager

**Migration Strategy:**

**Step 1: Vault Integration (Week 1-2)**
```java
public class VaultEncryptionService implements EncryptionService {

    private final VaultClient vaultClient;
    private final String keyPath;

    @Override
    public String encrypt(String plaintext) {
        // Get encryption key from Vault
        SecretKey key = vaultClient.getKey(keyPath);
        return encryptWithKey(plaintext, key);
    }

    @Override
    public String decrypt(String ciphertext) {
        // Get decryption key from Vault
        SecretKey key = vaultClient.getKey(keyPath);
        return decryptWithKey(ciphertext, key);
    }
}
```

**Step 2: Key Rotation Support (Week 3)**
```java
public class KeyRotationService {

    public Future<Void> rotateEncryptionKey() {
        // 1. Generate new key in Vault
        // 2. Re-encrypt all PostgreSQL credentials with new key
        // 3. Update key version in database
        // 4. Retire old key after grace period
    }
}
```

**Step 3: Gradual Migration (Week 4-6)**
- Deploy Vault integration alongside environment variable
- Re-encrypt credentials using Vault key
- Monitor for issues
- Remove environment variable dependency

---

### 20.3.3 High Availability Upgrade

**Current State:** Single SQLite file
**Target State:** PostgreSQL with replication

**Migration Strategy:**

**Step 1: PostgreSQL Primary-Replica Setup (Week 1-2)**
```yaml
# PostgreSQL HA Configuration
primary:
  host: pg-primary.internal
  port: 5432
  database: peegeeq_management

replicas:
  - host: pg-replica-1.internal
    port: 5432
    lag_threshold_ms: 100
  - host: pg-replica-2.internal
    port: 5432
    lag_threshold_ms: 100

connection_pool:
  primary_pool_size: 20
  replica_pool_size: 50

routing:
  writes: primary
  reads: replicas (round-robin)
```

**Step 2: Connection Pool Manager (Week 3)**
```java
public class HAConnectionManager {

    private final PgPool primaryPool;
    private final List<PgPool> replicaPools;
    private final LoadBalancer loadBalancer;

    public Future<RowSet<Row>> executeQuery(String sql) {
        // Route reads to replicas
        PgPool pool = loadBalancer.selectReplica(replicaPools);
        return pool.query(sql).execute();
    }

    public Future<RowSet<Row>> executeUpdate(String sql) {
        // Route writes to primary
        return primaryPool.query(sql).execute();
    }
}
```

**Step 3: Failover Automation (Week 4-5)**
```java
public class FailoverManager {

    public Future<Void> handlePrimaryFailure() {
        // 1. Detect primary failure
        // 2. Promote replica to primary
        // 3. Update connection pools
        // 4. Notify monitoring systems
    }
}
```

---

### 20.3.4 Service Manager Integration (Phase 8)

**Objective:** Integrate `peegeeq-service-manager` for multi-instance authentication and federated management.

**Current State:**
- Single PeeGeeQ REST API instance
- Authentication tied to single instance
- No service discovery

**Target State:**
- Multiple PeeGeeQ REST API instances
- Centralized authentication via Service Manager
- Consul-based service discovery
- Load-balanced authentication requests

**Architecture:**

```
┌─────────────────────────────────────────────────────────────┐
│ PeeGeeQ Service Manager (Port 9090)                         │
│                                                             │
│ ┌─────────────────┐  ┌──────────────────┐  ┌─────────────┐│
│ │ Auth Service    │  │ Service Discovery│  │ Load Balancer││
│ │ (Centralized)   │  │ (Consul)         │  │ (Round Robin)││
│ └─────────────────┘  └──────────────────┘  └─────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              │ Discovers & Routes
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ PeeGeeQ REST API Instances (Registered with Consul)        │
│                                                             │
│ ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│ │ Instance 1   │  │ Instance 2   │  │ Instance 3   │      │
│ │ Port 8080    │  │ Port 8081    │  │ Port 8082    │      │
│ │ Tenant: ACME │  │ Tenant: BETA │  │ Tenant: ACME │      │
│ └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

**Implementation Plan:**

**Week 1-2: Service Manager Authentication Module**
```java
// Add authentication to Service Manager
public class ServiceManagerAuthHandler implements Handler<RoutingContext> {

    private final AuthService authService;
    private final JWTValidator jwtValidator;

    @Override
    public void handle(RoutingContext ctx) {
        // Validate JWT from request
        String token = extractToken(ctx);

        jwtValidator.validate(token)
            .onSuccess(claims -> {
                // Add user context to routing context
                ctx.put("userId", claims.getUserId());
                ctx.put("tenantId", claims.getTenantId());
                ctx.put("permissions", claims.getPermissions());
                ctx.next();
            })
            .onFailure(err -> {
                ctx.response()
                    .setStatusCode(401)
                    .end(new JsonObject()
                        .put("error", "Unauthorized")
                        .encode());
            });
    }
}
```

**Week 3-4: Federated Authentication Endpoints**
```java
// Service Manager routes authentication requests
router.post("/api/v1/auth/login").handler(authHandler::login);
router.post("/api/v1/auth/logout").handler(authHandler::logout);
router.post("/api/v1/auth/refresh").handler(authHandler::refreshToken);

// Federated management endpoints (authenticated)
router.get("/api/v1/federated/overview")
    .handler(authMiddleware)
    .handler(federatedHandler::getOverview);

router.get("/api/v1/federated/queues")
    .handler(authMiddleware)
    .handler(federatedHandler::getQueues);
```

**Week 5-6: Instance Registration with Auth Context**
```java
public class AuthenticatedInstanceRegistration {

    public Future<Void> registerInstance(PeeGeeQInstance instance) {
        // Register instance with Consul
        // Include tenant information in service tags
        ServiceOptions options = new ServiceOptions()
            .setId(instance.getInstanceId())
            .setName("peegeeq-api")
            .setTags(List.of(
                "tenant:" + instance.getTenantId(),
                "version:" + instance.getVersion(),
                "region:" + instance.getRegion()
            ));

        return consulClient.registerService(options);
    }
}
```

**Week 7-8: Tenant-Aware Load Balancing**
```java
public class TenantAwareLoadBalancer extends LoadBalancer {

    @Override
    public PeeGeeQInstance selectInstance(
            List<PeeGeeQInstance> instances,
            String tenantId) {

        // Filter instances by tenant
        List<PeeGeeQInstance> tenantInstances = instances.stream()
            .filter(i -> i.getTenantId().equals(tenantId))
            .filter(PeeGeeQInstance::isHealthy)
            .collect(Collectors.toList());

        // Apply load balancing strategy
        return super.selectInstance(tenantInstances);
    }
}
```

**Week 9-10: Federated User Management**
```java
// Service Manager provides centralized user management
router.get("/api/v1/admin/users")
    .handler(adminAuthMiddleware)
    .handler(userManagementHandler::listUsers);

router.post("/api/v1/admin/users")
    .handler(adminAuthMiddleware)
    .handler(userManagementHandler::createUser);

// Propagate user changes to all instances
public class UserChangeNotifier {

    public Future<Void> notifyUserChange(UserChangeEvent event) {
        // Notify all registered instances via Consul events
        return consulClient.fireEvent(
            "user-change",
            new JsonObject()
                .put("userId", event.getUserId())
                .put("action", event.getAction())
                .encode()
        );
    }
}
```

**Benefits of Service Manager Integration:**
- ✅ Centralized authentication across multiple instances
- ✅ Automatic service discovery and health monitoring
- ✅ Load balancing with tenant awareness
- ✅ Federated management API (aggregate data from all instances)
- ✅ Automatic failover when instances go down
- ✅ Simplified deployment (instances auto-register)
- ✅ Multi-region support with regional load balancing

---

### 20.3.5 Scalability Improvements

**Current Limitations:**
- SQLite write bottleneck (single writer)
- No horizontal scaling for authentication
- Limited to ~10,000 users

**Upgrade Path:**

**Step 1: Read Replicas (Week 1-2)**
```bash
# Use litestream for SQLite replication
litestream replicate peegeeq_management.db s3://backup-bucket/peegeeq_management.db

# Configure read replicas
PEEGEEQ_MANAGEMENT_DB_PRIMARY=/data/peegeeq_management.db
PEEGEEQ_MANAGEMENT_DB_REPLICAS=/data/replicas/replica-1.db,/data/replicas/replica-2.db
```

```java
public class ReplicatedSQLiteService {

    private final Connection primaryConnection;
    private final List<Connection> replicaConnections;
    private final LoadBalancer loadBalancer;

    public Future<ResultSet> executeQuery(String sql) {
        // Route reads to replicas
        Connection replica = loadBalancer.selectReplica(replicaConnections);
        return executeOnConnection(replica, sql);
    }

    public Future<Integer> executeUpdate(String sql) {
        // Route writes to primary
        return executeOnConnection(primaryConnection, sql);
    }
}
```

**Step 2: Connection Pooling (Week 3)**
```java
public class SQLiteConnectionPool {

    private final HikariDataSource dataSource;

    public SQLiteConnectionPool(String dbPath) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);

        this.dataSource = new HikariDataSource(config);
    }
}
```

**Step 3: Caching Layer (Week 4-5)**
```java
public class CachedAuthService implements AuthService {

    private final AuthService delegate;
    private final Cache<String, UserInfo> userCache;
    private final Cache<String, TenantConfig> tenantCache;

    public CachedAuthService(AuthService delegate) {
        this.delegate = delegate;
        this.userCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
        this.tenantCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();
    }

    @Override
    public Future<UserInfo> getUserInfo(String userId) {
        UserInfo cached = userCache.getIfPresent(userId);
        if (cached != null) {
            return Future.succeededFuture(cached);
        }

        return delegate.getUserInfo(userId)
            .onSuccess(user -> userCache.put(userId, user));
    }
}
```

**Step 4: JWT Token Caching (Week 6)**
```java
public class JWTValidatorWithCache implements JWTValidator {

    private final JWTValidator delegate;
    private final Cache<String, JWTClaims> tokenCache;

    @Override
    public Future<JWTClaims> validate(String token) {
        // Check cache first
        JWTClaims cached = tokenCache.getIfPresent(token);
        if (cached != null && !cached.isExpired()) {
            return Future.succeededFuture(cached);
        }

        // Validate and cache
        return delegate.validate(token)
            .onSuccess(claims -> tokenCache.put(token, claims));
    }
}
```

---

### 20.3.6 Multi-Region Deployment

**Current State:** Single region
**Target State:** Multi-region with regional failover

**Architecture:**

```
┌─────────────────────────────────────────────────────────────┐
│ Global Load Balancer (Route 53 / CloudFlare)               │
│ - Geo-routing                                               │
│ - Health checks                                             │
│ - Failover to nearest healthy region                        │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│ Region: US   │      │ Region: EU   │      │ Region: APAC │
│              │      │              │      │              │
│ Service Mgr  │      │ Service Mgr  │      │ Service Mgr  │
│ + Consul     │      │ + Consul     │      │ + Consul     │
│              │      │              │      │              │
│ PostgreSQL   │◄────►│ PostgreSQL   │◄────►│ PostgreSQL   │
│ (Primary)    │ Repl │ (Replica)    │ Repl │ (Replica)    │
└──────────────┘      └──────────────┘      └──────────────┘
```

**Implementation:**

**Week 1-2: Regional Service Managers**
```yaml
# us-east-1 configuration
region: us-east-1
consul:
  datacenter: us-east-1
  wan_join:
    - consul-eu-west-1.internal
    - consul-ap-southeast-1.internal

# eu-west-1 configuration
region: eu-west-1
consul:
  datacenter: eu-west-1
  wan_join:
    - consul-us-east-1.internal
    - consul-ap-southeast-1.internal
```

**Week 3-4: Cross-Region PostgreSQL Replication**
```sql
-- Primary (us-east-1)
CREATE PUBLICATION peegeeq_management_pub FOR ALL TABLES;

-- Replica (eu-west-1)
CREATE SUBSCRIPTION peegeeq_management_sub
    CONNECTION 'host=pg-us-east-1.internal port=5432 dbname=peegeeq_management'
    PUBLICATION peegeeq_management_pub;

-- Replica (ap-southeast-1)
CREATE SUBSCRIPTION peegeeq_management_sub
    CONNECTION 'host=pg-us-east-1.internal port=5432 dbname=peegeeq_management'
    PUBLICATION peegeeq_management_pub;
```

**Week 5-6: Regional Failover**
```java
public class RegionalFailoverManager {

    private final List<Region> regions;
    private final HealthChecker healthChecker;

    public Future<Region> selectHealthyRegion(String preferredRegion) {
        // Try preferred region first
        Region preferred = findRegion(preferredRegion);
        if (healthChecker.isHealthy(preferred)) {
            return Future.succeededFuture(preferred);
        }

        // Failover to nearest healthy region
        return findNearestHealthyRegion(preferredRegion);
    }
}
```

---

### 20.3.7 Compliance and Audit Enhancements

**Current State:** Basic audit logging
**Target State:** Comprehensive compliance reporting

**Implementation:**

**Week 1-2: Enhanced Audit Schema**
```sql
-- Add compliance-specific fields
ALTER TABLE audit_log ADD COLUMN compliance_category VARCHAR(50);
ALTER TABLE audit_log ADD COLUMN data_classification VARCHAR(50);
ALTER TABLE audit_log ADD COLUMN retention_period_days INTEGER;
ALTER TABLE audit_log ADD COLUMN gdpr_relevant BOOLEAN DEFAULT FALSE;
ALTER TABLE audit_log ADD COLUMN pii_accessed BOOLEAN DEFAULT FALSE;

-- Create compliance views
CREATE VIEW gdpr_audit_trail AS
SELECT * FROM audit_log WHERE gdpr_relevant = TRUE;

CREATE VIEW pii_access_log AS
SELECT * FROM audit_log WHERE pii_accessed = TRUE;
```

**Week 3-4: Compliance Reporting API**
```java
public class ComplianceReportingService {

    public Future<ComplianceReport> generateGDPRReport(
            String tenantId,
            Instant startDate,
            Instant endDate) {

        return auditLogService.queryLogs(
            new AuditQuery()
                .tenantId(tenantId)
                .gdprRelevant(true)
                .dateRange(startDate, endDate)
        ).map(logs -> new ComplianceReport()
            .reportType("GDPR")
            .tenantId(tenantId)
            .period(startDate, endDate)
            .totalEvents(logs.size())
            .piiAccessCount(countPIIAccess(logs))
            .dataExportRequests(countDataExports(logs))
            .dataDeletionRequests(countDataDeletions(logs))
        );
    }
}
```

**Week 5-6: Automated Compliance Checks**
```java
public class ComplianceChecker {

    @Scheduled(cron = "0 0 2 * * *") // Daily at 2 AM
    public void runComplianceChecks() {
        // Check password expiration
        checkPasswordExpiration();

        // Check inactive users
        checkInactiveUsers();

        // Check audit log retention
        checkAuditLogRetention();

        // Generate compliance alerts
        generateComplianceAlerts();
    }
}
```

---

### 20.3.8 Migration Timeline Summary

| Phase | Duration | Effort | Risk | Dependencies |
|-------|----------|--------|------|--------------|
| **SQLite → PostgreSQL** | 6 weeks | High | Medium | Database team, testing |
| **Encryption Key Management** | 6 weeks | Medium | Low | Vault setup, security review |
| **High Availability** | 5 weeks | High | High | PostgreSQL HA, monitoring |
| **Service Manager Integration** | 10 weeks | Very High | Medium | Service Manager module, Consul |
| **Scalability Improvements** | 6 weeks | Medium | Low | Caching infrastructure |
| **Multi-Region Deployment** | 6 weeks | Very High | High | Network team, global infrastructure |
| **Compliance Enhancements** | 6 weeks | Medium | Low | Legal review, compliance team |

**Total Estimated Timeline:** 45 weeks (with parallel execution: ~30 weeks)

**Recommended Sequence:**
1. **Phase 1-2:** Encryption Key Management (Low risk, high value)
2. **Phase 3:** Scalability Improvements (Prepare for growth)
3. **Phase 4:** SQLite → PostgreSQL (Foundation for HA)
4. **Phase 5:** High Availability (Critical for production)
5. **Phase 6:** Service Manager Integration (Advanced features)
6. **Phase 7:** Multi-Region Deployment (Global scale)
7. **Phase 8:** Compliance Enhancements (Regulatory requirements)

---

### 20.3.9 Rollback and Risk Mitigation

**For Each Migration Phase:**

**Pre-Migration Checklist:**
- [ ] Full backup of SQLite database
- [ ] Documented rollback procedure
- [ ] Automated health checks in place
- [ ] Monitoring and alerting configured
- [ ] Load testing completed
- [ ] Security review completed
- [ ] Stakeholder approval obtained

**During Migration:**
- [ ] Dual-write to old and new systems
- [ ] Continuous data validation
- [ ] Real-time monitoring of error rates
- [ ] Automated rollback triggers
- [ ] Communication plan for incidents

**Post-Migration:**
- [ ] Keep old system running for 30 days
- [ ] Daily data consistency checks
- [ ] Performance monitoring
- [ ] User feedback collection
- [ ] Gradual traffic migration (10% → 50% → 100%)

**Rollback Triggers:**
- Error rate > 1%
- Response time > 2x baseline
- Data inconsistency detected
- Security vulnerability discovered
- Critical bug in production

**Rollback Procedure:**
```bash
# Automated rollback script
./rollback.sh --phase=postgres-migration --reason="high-error-rate"

# Steps:
# 1. Stop writes to new system
# 2. Switch reads to old system
# 3. Validate old system health
# 4. Notify stakeholders
# 5. Investigate root cause
```

---

## 21. Conclusion

This design provides a **production-ready, secure, multi-tenant authentication and authorization system** for PeeGeeQ that:

✅ **Simple:** SQLite for management plane, no separate auth server required
✅ **Secure:** AES-256 encryption, bcrypt passwords, JWT tokens, three-layer tenant isolation
✅ **Flexible:** Support for multiple deployment patterns (schema-per-tenant, database-per-tenant, hybrid)
✅ **Scalable:** Connection pooling, JWT-based auth, read-heavy workload
✅ **Portable:** SQLite database can be moved between servers, works on all platforms
✅ **Maintainable:** Clear separation of concerns, well-defined APIs, comprehensive testing strategy
✅ **Evolvable:** Clear upgrade path from SQLite to PostgreSQL, single-instance to multi-region

### 21.1 Implementation Roadmap

**Phase 1-4: Core Implementation (8 weeks)**
- SQLite management database
- JWT authentication
- Tenant management
- User management and RBAC

**Phase 5-7: Advanced Features (Future)**
- API keys, SSO, MFA
- High availability
- Advanced security

**Phase 8: Enterprise Scale (Section 20.3)**
- PostgreSQL migration for management plane
- Vault-based encryption key management
- Multi-region deployment
- Service Manager integration
- Compliance and audit enhancements

### 21.2 Evolution Path

The design intentionally starts simple (SQLite, single instance) with a clear evolution path to enterprise scale:

```
Start Here          →    Growth Phase    →    Enterprise Scale
─────────────────────────────────────────────────────────────────
SQLite              →    PostgreSQL      →    Multi-Region PostgreSQL
Single Instance     →    Load Balanced   →    Service Manager + Consul
Env Var Keys        →    Vault           →    HSM / Cloud KMS
Basic Audit         →    Enhanced Audit  →    Compliance Reporting
Manual Deployment   →    CI/CD           →    Multi-Region Auto-Deploy
```

**Key Principle:** Each evolution step is **optional** and **incremental**. The system works at every stage.

### 21.3 Trade-off Management

The design acknowledges trade-offs (Section 19) and provides **specific mitigation strategies** (Section 20.3):

| Trade-off | Initial Approach | Upgrade Path |
|-----------|------------------|--------------|
| SQLite HA limitations | Acceptable for < 1000 tenants | PostgreSQL migration (Section 20.3.1) |
| Encryption key in env var | Simple, works for dev/test | Vault integration (Section 20.3.2) |
| Single instance | Fast to deploy | Service Manager (Section 20.3.4) |
| Limited scalability | Sufficient for 10K users | Caching + replicas (Section 20.3.5) |
| Single region | Lower complexity | Multi-region (Section 20.3.6) |

### 21.4 Service Manager Integration

The **peegeeq-service-manager** module provides the foundation for enterprise-scale deployment:

**Current Capabilities:**
- Service discovery via Consul
- Health monitoring and failover
- Load balancing (round-robin, random, least-connections)
- Federated management API

**Future Integration (Section 20.3.4):**
- Centralized authentication across instances
- Tenant-aware load balancing
- Multi-region service discovery
- Automatic instance registration
- Federated user management

**Timeline:** 10 weeks after Phase 1-4 completion

### 21.5 Next Steps

**Immediate (Weeks 1-8):**
1. Review and approve this design
2. Set up development environment
3. Implement Phase 1-4 (core authentication)
4. Deploy to development environment
5. Conduct security review
6. Deploy to production

**Short-term (Months 3-6):**
1. Monitor production usage and performance
2. Collect user feedback
3. Implement Phase 5 features (API keys, SSO)
4. Plan for scalability upgrades

**Long-term (Months 6-12):**
1. Evaluate need for PostgreSQL migration
2. Implement Service Manager integration
3. Deploy multi-region if needed
4. Enhance compliance and audit capabilities

### 21.6 Success Metrics

**Phase 1-4 Success Criteria:**
- ✅ All API endpoints protected with JWT authentication
- ✅ Multi-tenant isolation verified (no cross-tenant access)
- ✅ Admin can create tenants and users via UI
- ✅ Users can log in and access only their tenant data
- ✅ Password change flow works correctly
- ✅ Audit log captures all authentication events
- ✅ Security review passed with no critical issues
- ✅ Performance: < 100ms authentication latency
- ✅ Reliability: 99.9% uptime for authentication service

**Enterprise Scale Success Criteria (Phase 8):**
- ✅ Support 10,000+ users across 1,000+ tenants
- ✅ 99.99% uptime with multi-region failover
- ✅ < 50ms authentication latency (global average)
- ✅ Zero-downtime deployments
- ✅ Compliance reporting for GDPR, SOC 2
- ✅ Automated disaster recovery (RTO < 5 minutes)

---

**This design provides a complete, production-ready authentication system with a clear path from simple deployment to enterprise scale.**


