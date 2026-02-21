# Security Release Framework

This document defines a practical security release baseline for Arc Reactor.

## Release Security Goals

- Prevent shipping known critical vulnerabilities
- Prevent secret leakage and unsafe defaults
- Produce verifiable release artifacts (SBOM/signature/provenance)
- Keep incident response and patch release process predictable

## Minimum Security Gates

| Gate | Required check |
|---|---|
| Source | Secret scanning + dependency vulnerability scan |
| Build | Reproducible build metadata + immutable artifact tag |
| Artifact | SBOM generation and storage |
| Container | Image vulnerability scan (block critical/high by policy) |
| Release | Signed artifacts + release notes with security section |
| Operations | Runtime config hardening and key rotation policy |

## Recommended Pipeline Stages

1. **Pre-merge**
   - SAST/linters
   - Secret scan
   - Dependency CVE scan
2. **Pre-release**
   - SBOM generation (CycloneDX/SPDX)
   - Container scan
   - License policy check
3. **Release publish**
   - Sign container/image and checksums
   - Attach SBOM + security scan summary
4. **Post-release**
   - Monitor advisories
   - Patch-window SLA (for example critical within 24h)

Current repository baseline:

- `.github/workflows/security-baseline.yml` for secret and filesystem vulnerability scans

## Arc Reactor-Specific Hardening

- Keep `arc.reactor.auth.enabled=true` in production.
- Keep admin APIs behind JWT role checks and network restrictions.
- Use `arc.reactor.mcp.security.allowed-server-names` allowlist.
- Treat MCP server credentials as secrets managed outside Git.
- Keep Flyway enabled for durable policy/audit state.

## Release Checklist Template

- [ ] All tests passed (`./gradlew test`)
- [ ] Security scans passed by policy
- [ ] SBOM generated and attached to release artifacts
- [ ] Container image signed
- [ ] CVE exceptions reviewed and approved
- [ ] Production config reviewed (auth, secrets, policy toggles)
- [ ] Rollback image tag and procedure validated

## Incident Readiness

Define and publish:

- Security contact route (`SECURITY.md`)
- Severity model (critical/high/medium/low)
- Patch SLAs per severity
- Backport policy for supported versions
