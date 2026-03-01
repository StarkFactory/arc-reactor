# Tenant Onboarding Runbook

Use this runbook to avoid tenant/auth misconfiguration during rollout.

## 1) Preconditions

- Arc Reactor is running with auth enabled (always-on).
- Admin token is available (or admin login credentials).
- `arc-admin` module is enabled if you need platform tenant APIs.

## 2) Create tenant (platform control plane)

If `arc-admin` is enabled, create tenant metadata first:

```bash
curl -X POST http://localhost:8080/api/admin/platform/tenants \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "acme-prod",
    "name": "Acme Production",
    "plan": "ENTERPRISE"
  }'
```

Verify:

```bash
curl http://localhost:8080/api/admin/platform/tenants \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 3) Issue user token and verify tenant context

Register/login to obtain JWT:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@acme.com","password":"passw0rd!","name":"Acme User"}'
```

Important behavior:

- JWT contains `tenantId` from `arc.reactor.auth.default-tenant-id`.
- Runtime tenant resolution priority:
  1. JWT `tenantId` claim
  2. `X-Tenant-Id` header
- If claim/header mismatch occurs, chat endpoints fail-close with `400`.

## 4) Validate tenant fail-close behavior

Use runtime contract + agent QA scripts:

```bash
./scripts/dev/validate-runtime-contract.sh --base-url http://localhost:8080
./scripts/dev/validate-agent-e2e.sh --base-url http://localhost:8080 --admin-token "$ADMIN_TOKEN"
```

Expected:

- Unauthenticated request -> `401`
- Non-admin MCP inventory read -> `403`
- Missing/invalid tenant context for chat -> fail-close (`400`)

## 5) Tenant dashboard verification (admin)

```bash
curl http://localhost:8080/api/admin/tenant/overview \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Tenant-Id: acme-prod"
```

If the tenant-scoped endpoint is unavailable (`404`), check:

- `arc-admin` dependency is included in runtime.
- A `DataSource` is configured.
- Tenant admin feature path is active for the deployed profile.

## 6) Common operator confusion checklist

- "Config applied but not effective":
  - Confirm the runtime profile and effective env vars.
  - Confirm JWT tenant claim value for current token.
- "Header is set but still rejected":
  - Check whether JWT tenant claim mismatches header.
- "Approval endpoint missing":
  - `arc.reactor.approval.enabled=true` is required for `/api/approvals`.

## References

- `docs/en/governance/authentication.md`
- `docs/en/admin/api-reference.md`
- `scripts/dev/validate-runtime-contract.sh`
- `scripts/dev/validate-agent-e2e.sh`
