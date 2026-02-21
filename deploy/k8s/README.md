# Kubernetes Deployment Reference

This directory provides a production-oriented baseline deployment for Arc Reactor.

## Structure

- `base/namespace.yaml`: dedicated namespace
- `base/configmap.yaml`: non-secret runtime flags and feature toggles
- `base/deployment.yaml`: Arc Reactor workload + probes + resources
- `base/service.yaml`: internal ClusterIP service
- `base/hpa.yaml`: autoscaling baseline
- `base/pdb.yaml`: disruption safety baseline
- `base/secret.example.yaml`: secret template (do not apply as-is)
- `base/kustomization.yaml`: kustomize entrypoint
- `optional/ingress.yaml`: ingress example

## 1) Create/Update Secret

Use your own secret manager pipeline in production. For a quick start:

```bash
kubectl create namespace arc-reactor --dry-run=client -o yaml | kubectl apply -f -

kubectl -n arc-reactor create secret generic arc-reactor-secrets \
  --from-literal=GEMINI_API_KEY='replace-me' \
  --from-literal=ARC_REACTOR_AUTH_JWT_SECRET='replace-with-long-random-secret' \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://postgres:5432/arcreactor' \
  --from-literal=SPRING_DATASOURCE_USERNAME='arc' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='replace-me' \
  --dry-run=client -o yaml | kubectl apply -f -
```

## 2) Apply Base

```bash
kubectl apply -k deploy/k8s/base
```

## 3) (Optional) Expose via Ingress

```bash
kubectl apply -f deploy/k8s/optional/ingress.yaml
```

## 4) Verify

```bash
kubectl -n arc-reactor get pods
kubectl -n arc-reactor get svc
kubectl -n arc-reactor get hpa
kubectl -n arc-reactor logs deploy/arc-reactor --tail=200
```

## Important Notes

- Keep `arc.reactor.auth.enabled=true` for production control-plane security.
- Keep `SPRING_FLYWAY_ENABLED=true` when using PostgreSQL.
- Replace image tag with a pinned release tag (avoid `latest`).
- Integrate external secret management (Vault, ESO, or cloud secret manager).
