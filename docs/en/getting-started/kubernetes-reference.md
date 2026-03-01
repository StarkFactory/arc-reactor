# Kubernetes Deployment Reference

This guide provides a production-oriented Kubernetes baseline for Arc Reactor.

## Included Resources

See `deploy/k8s`:

- `base/deployment.yaml`: app pods, probes, resource limits
- `base/service.yaml`: internal service
- `base/hpa.yaml`: autoscaling baseline
- `base/pdb.yaml`: disruption budget
- `base/configmap.yaml`: non-secret runtime flags
- `base/secret.example.yaml`: secret template
- `optional/ingress.yaml`: optional ingress example

## Quick Apply

1. Create/update the secret `arc-reactor-secrets`
2. Apply the base stack

```bash
kubectl apply -k deploy/k8s/base
```

3. (Optional) apply ingress

```bash
kubectl apply -f deploy/k8s/optional/ingress.yaml
```

## Required Secret Keys

- `GEMINI_API_KEY` (or another provider key)
- `ARC_REACTOR_AUTH_JWT_SECRET`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Recommended Production Defaults

- `ARC_REACTOR_APPROVAL_ENABLED=true`
- `ARC_REACTOR_TOOL_POLICY_DYNAMIC_ENABLED=true`
- `ARC_REACTOR_OUTPUT_GUARD_ENABLED=true`
- `ARC_REACTOR_RAG_ENABLED=true` (if RAG is required)
- `ARC_REACTOR_AUTH_PUBLIC_ACTUATOR_HEALTH=true` (for liveness/readiness probes)
- `SPRING_FLYWAY_ENABLED=true`

## Operational Checks

```bash
kubectl -n arc-reactor get deploy,pods,svc,hpa,pdb
kubectl -n arc-reactor logs deploy/arc-reactor --tail=200
```

## Next Steps

- Replace image tag with an immutable release tag
- Integrate external secret manager (Vault/ESO/cloud secrets)
- Add ingress TLS and WAF/rate-limiting controls
