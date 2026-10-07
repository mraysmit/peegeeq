# PeeGeeQ Authentication and Authorization Decision Summary

**Status:** DESIGN DIRECTION RECORDED — NOT APPROVED OR IMPLEMENTED

**Original decision workshop:** 2025-12-27

**Last reconciled:** 2026-09-06

**Repository baseline:** `7db748b8e77f3aba850be7b73547d192dac5b83f`

## Purpose

This document preserves decisions made while exploring a PeeGeeQ authentication and authorization
system. It does not describe current runtime behaviour.

At the reviewed baseline, PeeGeeQ has no authentication module, JWT middleware, tenant-management
implementation, tenant-switch endpoint, or implemented authorization boundary. The authoritative
status is the Authentication and Authorization entry in the
[consolidated task register](../tasks/tasks.md#unscheduled-product-and-coverage-backlog).

## Recorded design direction

| Topic | Direction if the product is approved | Delivery status |
|---|---|---|
| Cross-tenant users | One identity may hold different roles in multiple tenants | Proposed only |
| Tenant context | A signed access token would identify one active tenant; switching would issue a new token | Proposed only |
| Tenant limits | Defer quotas and limits until operational demand is understood | Deferred idea |
| Tenant migration | Treat cross-database movement as a separate migration capability | Deferred idea |
| API keys | Consider role- and tenant-scoped keys for programmatic clients | Deferred idea |
| Multi-factor authentication | Define after the base identity provider and threat model are chosen | Deferred idea |
| Enterprise SSO | Consider OIDC or SAML with per-tenant configuration | Deferred idea |
| Audit logging | Authentication and authorization decisions must be auditable from the first release | Required design property |
| Backup and recovery | Management-plane identity data must participate in a tested recovery plan | Required design property |
| High availability | Select the management-plane datastore before choosing replication technology | Unresolved architecture decision |

## Important corrections to the original draft

The original summary used completed checkmarks and implementation language for proposed schema,
token, endpoint, and audit changes. No such implementation exists in the reviewed repository.
In particular:

- `user_tenant_roles` is a candidate schema, not a deployed table;
- `availableTenants` is a candidate claim, not a current token contract;
- `POST /api/v1/auth/switch-tenant` is a candidate endpoint, not a current API;
- an embedded management-plane database was discussed but not selected; and
- the earlier phase numbers and eight-week estimate are not approved tasks or commitments.

## Decisions still required

Before work can be promoted into implementation tasks, the product and security owners must define:

1. The deployment boundary: trusted internal service, administrative plane, or public service.
2. The threat model and assets requiring protection.
3. The identity provider and protocol, including key rotation and token revocation.
4. Tenant identity, tenant selection, and prevention of cross-tenant confused-deputy failures.
5. Role and permission semantics, including administrative privilege boundaries.
6. Service-account and API-key requirements.
7. Audit event content, integrity, retention, access, and privacy controls.
8. Management-plane storage, backup, recovery, and high-availability requirements.
9. Compatibility and rollout behaviour for currently unauthenticated clients.

## Minimum security properties

Any approved implementation must:

- deny access by default;
- derive tenant scope from validated identity and server-side authorization, never from an
  untrusted request field alone;
- validate issuer, audience, signature algorithm, expiry, and key rotation for signed tokens;
- use short-lived credentials and explicit revocation or rotation procedures;
- separate platform administration from tenant administration;
- avoid disclosing whether an unrelated tenant or identity exists;
- record security-relevant success and failure events without logging secrets; and
- document the behaviour when identity, key, or audit dependencies are unavailable.

## Entry criteria and test evidence

If the product decision is approved, the consolidated task register must first contain a bounded,
ordered TDD plan. Verification must include real protocol and persistence boundaries:

- authentication success, rejection, expiry, revocation, and key rotation;
- authorization for every protected operation and role transition;
- cross-tenant isolation and tenant-switch attacks;
- concurrent role changes and stale-token behaviour;
- audit completeness and secret-redaction checks;
- restart, backup, restore, and dependency-outage scenarios; and
- compatibility tests for the selected rollout policy.

Mocking frameworks and mocked database or repository layers are not acceptable evidence for these
security guarantees.

## References

- [Authentication and authorization design](PEEGEEQ_AUTHENTICATION_AUTHORIZATION_DESIGN.md)
- [Consolidated task register](../tasks/tasks.md)
- [Coding principles](../dev/pgq-coding-principles.md)
- [Testing standards](../testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md)
