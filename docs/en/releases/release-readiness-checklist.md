# Release Readiness Checklist

Use this checklist before tagging a public release.

## Quality Gates

- [ ] `./gradlew test` passed
- [ ] Required integration suites passed for release scope
- [ ] Docs link checks passed (`bash scripts/dev/check-docs.sh`)
- [ ] Runtime contract smoke passed (`./scripts/dev/validate-runtime-contract.sh`)
- [ ] Agent scenario QA smoke passed (`./scripts/dev/validate-agent-e2e.sh`)
- [ ] Release notes prepared under `docs/en/releases/<tag>.md`

## Security Gates

- [ ] Security scans passed by policy
- [ ] Tag release workflow security-gates job passed (Gitleaks + Trivy)
- [ ] Dependency vulnerability review completed
- [ ] No secrets in repository diff
- [ ] External/provider credentials rotated or revalidated before release
- [ ] Runtime hardening defaults verified (auth/policy/guard settings)
- [ ] Flyway migration immutability guard passed (no edits to existing `V*.sql`)

## Artifact and Release

- [ ] Docker image built and smoke-tested
- [ ] Image tag is immutable and documented
- [ ] SBOM + checksum + signature + provenance artifacts attached to release
- [ ] Changelog and compatibility notes updated

## Operations

- [ ] Rollback target/version identified
- [ ] Database migration impact reviewed
- [ ] Critical dashboards/alerts ready for post-release monitoring
- [ ] MCP duplicate/stale cleanup dry-run reviewed (`scripts/ops/cleanup_mcp_duplicate_servers.py`)

## References

- `docs/en/governance/security-release-framework.md`
- `docs/en/governance/support-policy.md`
- `docs/en/getting-started/kubernetes-reference.md`
- `docs/en/operations/tenant-onboarding-runbook.md`
- `scripts/dev/pre-open-check.sh`
