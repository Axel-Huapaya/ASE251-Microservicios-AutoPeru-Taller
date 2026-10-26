#!/usr/bin/env bash
# Casuistica 2 - Resolucion: quitar el delay artificial del servicio de repuestos.
set -euo pipefail
NS=autoperu-taller

kubectl -n "$NS" set env deployment/repuestos-service REPUESTOS_DELAY_MS=0
kubectl -n "$NS" rollout status deployment/repuestos-service --timeout=300s
