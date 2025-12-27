# PeeGeeQ Authentication Design Decisions Summary

**Date:** 2025-12-27  
**Status:** ✅ All Open Questions Resolved

---

## Overview

This document summarizes the key design decisions made for the PeeGeeQ authentication and authorization system, based on the open questions in Section 20 of the main design document.

---

## ✅ Decision 1: Cross-Tenant Users

**Question:** Should a user be able to belong to multiple tenants?

**Decision:** **YES** - Users can have multiple tenant associations with different roles per tenant.

### Rationale
- Enterprise users often need access to multiple client tenants
- Consultants/support staff need multi-tenant access
- Simplifies user management (one login, multiple contexts)
- Aligns with modern SaaS patterns

### Implementation Impact

#### Schema Changes
- **Removed** `tenant_id` and `role_id` from `user_accounts` table
- **Added** `user_tenant_roles` junction table for many-to-many relationships
- Each user can have different roles in different tenants

#### JWT Changes
- JWT includes `tenantId` for current active tenant
- JWT includes `availableTenants` array for tenant switching
- Permissions are scoped to current tenant

#### API Changes
- **New endpoint:** `POST /api/v1/auth/switch-tenant` for changing tenant context
- Login response includes all available tenants
- User management endpoints support multi-tenant associations

### User Experience
1. User logs in once with username/password
2. System returns JWT with default tenant context + list of available tenants
3. User can switch tenants without re-login
4. Each tenant switch generates new JWT with updated permissions

---

## ✅ Decision 2: Tenant Limits

**Question:** Should we enforce limits on tenants?

**Decision:** **FUTURE ENHANCEMENT** - Not in Phase 1-4, add in Phase 5.

### Rationale
- Adds complexity to initial implementation
- Can be added later without schema changes
- Most deployments won't need limits initially
- Better to validate real-world usage patterns first

### Future Implementation (Phase 5)
- Max users per tenant
- Max queues per tenant
- Storage quotas per tenant
- Rate limits per tenant
- Configurable per-tenant or global defaults

---

## ✅ Decision 3: Tenant Migration

**Question:** Support for moving tenants between databases?

**Decision:** **FUTURE ENHANCEMENT** - Not in Phase 1-4, add in Phase 5.

### Rationale
- Complex feature requiring careful design
- Low priority for initial deployment
- Can be built on top of existing backup/restore
- Requires zero-downtime migration strategy

### Future Implementation (Phase 5)
- Export tenant schema to SQL
- Import into new database
- Update tenant config in SQLite
- Zero-downtime migration with dual-write

---

## ✅ Decision 4: API Keys

**Question:** Support for programmatic access without user login?

**Decision:** **YES** - Role-based API keys with multi-tenant support (Phase 5).

### Rationale
- Essential for programmatic access (CI/CD, integrations)
- Aligns with cross-tenant users decision
- User can have API keys for multiple tenants
- Each API key has specific role and permissions

### Implementation (Phase 5)
- API keys stored in `api_keys` table
- Each key associated with user, tenant, and role
- Format: `pgq_<tenant-prefix>_<random-32-chars>`
- Support for expiration and rotation

---

## ✅ Decision 5: Multi-Factor Authentication (MFA)

**Question:** Future support for MFA?

**Decision:** **FUTURE ENHANCEMENT** - Phase 5.

### Rationale
- Important for security but not critical for initial deployment
- Requires additional infrastructure (SMS, TOTP, email)
- Can be added without breaking existing authentication

---

## ✅ Decision 6: SSO Integration

**Question:** Future support for SAML/OAuth2?

**Decision:** **FUTURE ENHANCEMENT** - Phase 5.

### Rationale
- Enterprise feature, not needed for initial deployment
- Complex integration requiring careful design
- Per-tenant SSO configuration needed
- Can be added alongside existing password authentication

---

## ✅ Decision 7: Audit Logging

**Question:** Should we log all operations with tenant context?

**Decision:** **YES** - Implement in Phase 1 (basic), enhance in Phase 5.

### Phase 1 Implementation
- Store in SQLite `audit_log` table
- Log authentication events only
- 90-day retention

### Phase 5 Enhancements
- Log all CRUD operations
- Export to external logging system (Elasticsearch, CloudWatch)
- Configurable retention per tenant
- Compliance reporting (GDPR, SOC 2)

---

## ✅ Decision 8: Backup/Restore

**Question:** How to backup/restore SQLite and PostgreSQL together?

**Decision:** **FUTURE ENHANCEMENT** - Phase 6 (HA).

### Rationale
- SQLite backup is simple (copy file)
- PostgreSQL backup is standard (pg_dump)
- Coordinated backups needed for consistency
- Part of broader HA/DR strategy

### Future Implementation (Phase 6)
- Coordinated backups with transaction consistency
- Point-in-time recovery
- Automated backup to S3/Azure Blob
- Disaster recovery procedures

---

## ✅ Decision 9: High Availability

**Question:** How to replicate SQLite across multiple API instances?

**Decision:** **FUTURE ENHANCEMENT** - Phase 6 (HA).

### Rationale
- Single instance sufficient for initial deployment
- Litestream provides simple replication when needed
- PostgreSQL migration (Section 20.3.1) is better long-term solution

### Future Implementation (Phase 6)
- Litestream for real-time SQLite replication
- Read replicas for scalability
- Automated failover procedures
- Or migrate to PostgreSQL for management plane

---

## Impact Summary

### Phase 1-4 (Core Implementation)
**Immediate Changes Required:**
1. ✅ Multi-tenant user schema (`user_tenant_roles` table)
2. ✅ Updated JWT structure with `availableTenants`
3. ✅ Tenant switching endpoint
4. ✅ Basic audit logging
5. ✅ Updated user management APIs

**Deferred to Future:**
- Tenant limits
- Tenant migration
- API keys
- MFA
- SSO
- Advanced audit logging
- Backup/restore coordination
- High availability

### Timeline Impact
- **Phase 1-4:** No timeline change (8 weeks)
- Multi-tenant users add ~1 week to implementation
- Offset by deferring other features to Phase 5-6

---

## Next Steps

1. ✅ Update main design document with schema changes
2. ✅ Update JWT structure documentation
3. ✅ Update API endpoint reference
4. ✅ Update example flows
5. ⏳ Begin Phase 1 implementation with multi-tenant support
6. ⏳ Implement tenant switching in UI
7. ⏳ Add integration tests for multi-tenant scenarios

---

## References

- Main Design Document: `PEEGEEQ_AUTHENTICATION_AUTHORIZATION_DESIGN.md`
- Section 20.1: Design Decisions (Resolved)
- Section 20.3: Upgrade and Migration Plan

