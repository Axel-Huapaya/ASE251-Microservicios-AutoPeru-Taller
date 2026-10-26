#!/usr/bin/env bash
# Casuistica 2 - El Servicio Lento: el servicio de repuestos responde con 5 s de retraso
# mientras ordenes solo espera 2 s (REPUESTOS_TIMEOUT_MS=2000).
# Uso: ./c2-activar.sh [delay_ms]   (por defecto 5000)
set -euo pipefail
NS=autoperu-taller
DELAY="${1:-5000}"

echo "==> Introduciendo un delay artificial de ${DELAY} ms en el servicio de repuestos"
kubectl -n "$NS" set env deployment/repuestos-service REPUESTOS_DELAY_MS="$DELAY"
kubectl -n "$NS" rollout status deployment/repuestos-service --timeout=300s

echo
echo "Genera trafico para ver los timeouts en ordenes:"
echo "  ./loadtesting/stress.sh -m ordenes -n 200 -c 4"
