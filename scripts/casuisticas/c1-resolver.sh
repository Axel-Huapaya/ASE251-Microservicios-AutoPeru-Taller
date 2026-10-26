#!/usr/bin/env bash
# Casuistica 1 - Resolucion: reaplicar el manifiesto original del servicio de repuestos.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NS=autoperu-taller

kubectl apply -f "$ROOT/k8s/repuestos/deployment.yaml"
kubectl -n "$NS" rollout status deployment/repuestos-service --timeout=300s
kubectl -n "$NS" get pods
