#!/usr/bin/env bash
# Elimina los recursos del proyecto.
# Uso:
#   ./scripts/teardown.sh             # borra microservicios, reglas y stack de monitoreo (deja el cluster)
#   ./scripts/teardown.sh --cluster   # ademas elimina el cluster de Minikube completo
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BORRAR_CLUSTER=false

for arg in "$@"; do
  case "$arg" in
    --cluster|-c) BORRAR_CLUSTER=true ;;
    -h|--help)
      echo "Uso: $0 [--cluster]"
      echo "  --cluster   elimina tambien el cluster de Minikube"
      exit 0 ;;
    *) echo "Parametro desconocido: $arg" >&2; exit 1 ;;
  esac
done

log() { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }

if $BORRAR_CLUSTER; then
  log "Eliminando el cluster de Minikube completo"
  minikube delete
  echo "Cluster eliminado."
  exit 0
fi

command -v kubectl >/dev/null 2>&1 || { echo "kubectl no esta instalado" >&2; exit 1; }

log "Eliminando microservicios, ServiceMonitors y reglas"
kubectl delete -f "$ROOT/k8s/monitoring/prometheusrule.yaml" --ignore-not-found
kubectl delete -f "$ROOT/k8s/monitoring/servicemonitors.yaml" --ignore-not-found
kubectl delete -f "$ROOT/k8s/ordenes/" --ignore-not-found
kubectl delete -f "$ROOT/k8s/repuestos/" --ignore-not-found
kubectl delete -f "$ROOT/k8s/00-namespace.yaml" --ignore-not-found

log "Eliminando el stack de monitoreo"
kubectl -n monitoring delete configmap autoperu-taller-dashboard --ignore-not-found
helm uninstall monitoring -n monitoring 2>/dev/null || true
kubectl delete namespace monitoring --ignore-not-found

echo
echo "Recursos eliminados. El cluster sigue activo (usa --cluster para borrarlo)."
