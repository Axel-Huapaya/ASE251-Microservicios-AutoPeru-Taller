#!/usr/bin/env bash
# Casuistica 1 - El Servicio Caido: un probe de liveness mal configurado en el servicio de repuestos
# hace que Kubernetes lo mate una y otra vez hasta entrar en CrashLoopBackOff.
set -euo pipefail
NS=autoperu-taller

echo "==> Rompiendo el liveness probe del servicio de repuestos (path inexistente + umbrales agresivos)"
kubectl -n "$NS" patch deployment repuestos-service --type=json -p='[
  {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/httpGet/path","value":"/health-roto"},
  {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/initialDelaySeconds","value":5},
  {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/periodSeconds","value":5},
  {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/failureThreshold","value":2}
]'

echo
echo "Listo. Observa el pod (aparecera CrashLoopBackOff en 1-3 minutos):"
echo "  kubectl -n $NS get pods -w"
echo "Genera trafico mientras tanto:"
echo "  ./loadtesting/stress.sh -m ordenes -n 300 -c 3 -s 200"
