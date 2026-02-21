# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 3.9.x   | :white_check_mark: |
| 3.8.x   | :warning: security/critical fixes only |
| < 3.8   | :x:                |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in Arc Reactor, please report it responsibly.

### How to Report

1. **Do NOT** open a public GitHub issue for security vulnerabilities
2. Email your findings to **arc.reactor.oss@gmail.com**
3. Include a detailed description of the vulnerability
4. Provide steps to reproduce the issue if possible

### What to Expect

- Acknowledgment of your report within 48 hours
- An assessment of the vulnerability within 1 week
- A fix or mitigation plan within 2 weeks for confirmed vulnerabilities
- Credit in the security advisory (unless you prefer to remain anonymous)

### Scope

The following are in scope for security reports:

- **Guard Pipeline bypasses** - Ways to circumvent the 5-stage guardrail
- **Prompt injection** - Techniques that bypass the injection detection stage
- **Memory leaks** - Session data exposed to unauthorized users
- **MCP transport security** - Vulnerabilities in STDIO/SSE transport handling
- **Dependency vulnerabilities** - Known CVEs in direct dependencies

### Out of Scope

- Issues in upstream dependencies (Spring AI, Spring Boot) - report to those projects directly
- Denial of service via legitimate API usage (rate limiting is configurable)
- Issues requiring physical access to the server

## Fork Responsibility Boundary

This policy applies to the upstream Arc Reactor repository and supported upstream versions only.

For forked/customized/self-hosted deployments, the fork operator is solely responsible for:

- Deployment security posture and runtime hardening
- Secrets, key management, and access control configuration
- Compliance controls and legal obligations
- Incident response and recovery operations

Out of scope for upstream maintainer responsibility:

- Vulnerabilities introduced by fork-specific code changes
- Infrastructure or cloud misconfiguration in downstream environments
- CI/CD compromise or insecure release process in downstream forks

Upstream maintainers may provide best-effort guidance, but do not assume operational liability for downstream forks.

## Security Best Practices

When using Arc Reactor in production:

1. **Enable all guard stages** - Don't disable injection detection or input validation
2. **Set appropriate rate limits** - Configure per your expected traffic patterns
3. **Use environment variables** - Never hardcode API keys in configuration files
4. **Monitor hook events** - Use `AfterAgentCompleteHook` for security audit logging
5. **Keep dependencies updated** - Regularly update Spring Boot and Spring AI versions
