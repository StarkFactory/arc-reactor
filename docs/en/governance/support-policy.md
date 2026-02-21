# Support and Compatibility Policy

This policy defines support scope, compatibility promises, and deprecation behavior for Arc Reactor.

## Version Support Window

Arc Reactor follows an **N / N-1 minor support model**:

- **N (latest minor)**: active support (features, bug fixes, security fixes)
- **N-1 (previous minor)**: critical bug fixes and security fixes only
- Older lines: unsupported

At the time of writing (February 21, 2026):

- Active support: `3.9.x`
- Maintenance-only: `3.8.x`
- Unsupported: `< 3.8`

## Compatibility Expectations

### API Compatibility

- Public REST endpoints aim to preserve backward compatibility within the same minor line.
- Breaking API changes are scheduled for a new minor/major line and documented in release notes.

### Configuration Compatibility

- Existing config keys should remain valid through at least one minor line.
- Deprecated keys are documented before removal.

### Data and Migration Compatibility

- Flyway migrations are append-only and versioned.
- Roll-forward migrations are preferred over rollback-based migration plans.

## Deprecation Policy

When deprecating APIs/config/features:

1. Mark as deprecated in docs and release notes
2. Keep behavior for at least one minor line when feasible
3. Provide migration guidance and replacement path
4. Remove only in a planned release with explicit changelog notice

## Patch and Hotfix Policy

- Security vulnerabilities: handled according to `SECURITY.md` SLA targets
- Critical production regressions: patch release prioritized
- Non-critical issues: scheduled into upcoming minor releases

## Reporting and Escalation

- Standard issues and requests: GitHub Issues
- Security disclosures: follow `SECURITY.md`

## Fork and Deployment Responsibility Boundary

- Arc Reactor is distributed under Apache-2.0 and provided **AS IS**.
- Upstream maintainers support upstream code lines by this policy, but do not assume operational liability
  for downstream fork deployments.
- Fork operators are solely responsible for runtime security, secrets handling, compliance, incident response,
  and production operations in their own environments.

## Related

- `SECURITY.md`
- `CHANGELOG.md`
- `docs/en/releases/README.md`
