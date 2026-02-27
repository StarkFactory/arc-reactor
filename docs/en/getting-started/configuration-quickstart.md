# Configuration Quickstart

If you are forking Arc Reactor, start with the smallest possible setup.

## 1) Fast path (bootstrap script)

```bash
./scripts/dev/bootstrap-local.sh --api-key your-api-key --run
```

This script copies `examples/config/application.quickstart.yml` into
`arc-core/src/main/resources/application-local.yml` (if missing), validates
`GEMINI_API_KEY`, and starts `:arc-app:bootRun`.

## 2) Required value (manual path)

Only one environment variable is required for first run:

```bash
export GEMINI_API_KEY=your-api-key
./gradlew :arc-app:bootRun
```

## 3) Optional local YAML file

When you want explicit local config, start from the quickstart sample:

- [`application.yml.example`](../../../application.yml.example)
- [`examples/config/application.quickstart.yml`](../../../examples/config/application.quickstart.yml)

Example:

```bash
cp examples/config/application.quickstart.yml arc-core/src/main/resources/application-local.yml
```

## 4) Turn on features gradually

All major features are opt-in by default (except guard/security headers):

- `arc.reactor.rag.enabled`
- `arc.reactor.auth.enabled`
- `arc.reactor.cors.enabled`
- `arc.reactor.circuit-breaker.enabled`
- `arc.reactor.cache.enabled`

## 5) Need full control?

Use these next:

- Full reference: [configuration.md](configuration.md)
- Advanced sample: [`examples/config/application.advanced.yml`](../../../examples/config/application.advanced.yml)
