#!/usr/bin/env bash
# Levanta todo el sistema desde cero: cluster, monitoreo, imagenes y microservicios.
# Uso:  ./scripts/setup.sh
# Variables opcionales: MINIKUBE_CPUS (4), MINIKUBE_MEMORY (6144), MINIKUBE_DRIVER (docker)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPUS="${MINIKUBE_CPUS:-4}"
MEMORY="${MINIKUBE_MEMORY:-6144}"
DRIVER="${MINIKUBE_DRIVER:-docker}"
NS_APP="autoperu-taller"
NS_MON="monitoring"
RELEASE="monitoring"

log()  { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------
# 1. Prerequisitos
# ---------------------------------------------------------------
log "Verificando prerequisitos"
for cmd in docker minikube kubectl helm; do
  command -v "$cmd" >/dev/null 2>&1 || fail "'$cmd' no esta instalado. Revisa la seccion de instalacion del README."
  echo "  OK  $cmd"
done
docker info >/dev/null 2>&1 || fail "Docker no esta corriendo. Abre Docker Desktop y vuelve a ejecutar."

# ---------------------------------------------------------------
# 2. Cluster
# ---------------------------------------------------------------
if minikube status >/dev/null 2>&1; then
  log "Minikube ya esta corriendo, se reutiliza"
else
  log "Iniciando Minikube (cpus=$CPUS, memoria=${MEMORY}MB, driver=$DRIVER)"
  minikube start --cpus="$CPUS" --memory="$MEMORY" --driver="$DRIVER"
fi

log "Habilitando metrics-server"
minikube addons enable metrics-server

# ---------------------------------------------------------------
# 3. Stack de monitoreo (kube-prometheus-stack)
# ---------------------------------------------------------------
log "Instalando kube-prometheus-stack con Helm (puede tardar varios minutos)"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update >/dev/null
helm upgrade --install "$RELEASE" prometheus-community/kube-prometheus-stack \
  --namespace "$NS_MON" --create-namespace \
  -f "$ROOT/k8s/monitoring/values.yaml" \
  --wait --timeout 15m

# ---------------------------------------------------------------
# 4. Imagenes (dentro del daemon de Minikube)
# ---------------------------------------------------------------
log "Apuntando Docker al daemon de Minikube"
eval "$(minikube docker-env --shell bash)"

log "Construyendo imagen autoperu-repuestos:1.0.0"
docker build -t autoperu-repuestos:1.0.0 "$ROOT/microservices/repuestos-service"
log "Construyendo imagen autoperu-ordenes:1.0.0"
docker build -t autoperu-ordenes:1.0.0 "$ROOT/microservices/ordenes-service"

# ---------------------------------------------------------------
# 5. Manifiestos
# ---------------------------------------------------------------
log "Aplicando manifiestos de Kubernetes"
kubectl apply -f "$ROOT/k8s/00-namespace.yaml"
kubectl apply -f "$ROOT/k8s/repuestos/"
kubectl apply -f "$ROOT/k8s/ordenes/"
kubectl apply -f "$ROOT/k8s/monitoring/servicemonitors.yaml"
kubectl apply -f "$ROOT/k8s/monitoring/prometheusrule.yaml"

log "Cargando el dashboard de Grafana"
kubectl -n "$NS_MON" create configmap autoperu-taller-dashboard \
  --from-file=autoperu-taller-dashboard.json="$ROOT/k8s/monitoring/dashboards/autoperu-taller-dashboard.json" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS_MON" label configmap autoperu-taller-dashboard grafana_dashboard=1 --overwrite

log "Esperando a que los pods esten listos"
kubectl -n "$NS_APP" rollout status deployment/repuestos-service --timeout=300s
kubectl -n "$NS_APP" rollout status deployment/ordenes-service --timeout=300s

# ---------------------------------------------------------------
# 6. URLs
# ---------------------------------------------------------------
IP="$(minikube ip)"
GRAFANA_PASS="$(grep -E '^\s*adminPassword:' "$ROOT/k8s/monitoring/values.yaml" | sed -E 's/.*adminPassword:\s*"?([^"]*)"?.*/\1/')"

log "Sistema listo"
cat <<EOF

  URLs por NodePort (Linux, o macOS/Windows con driver que exponga la IP de Minikube):
    Grafana       http://$IP:30300      (usuario: admin / clave: $GRAFANA_PASS)
    Prometheus    http://$IP:30090      (ver /targets y /alerts)
    Alertmanager  http://$IP:30093
    Repuestos      http://$IP:30081/api/repuestos     metrics: /metrics
    Ordenes       http://$IP:30082/api/ordenes       metrics: /metrics

  Con Docker Desktop (Windows/macOS) la IP de Minikube suele NO ser alcanzable desde el host.
  En ese caso usa:   ./scripts/port-forward.sh
    Grafana       http://localhost:3000
    Prometheus    http://localhost:9090
    Repuestos      http://localhost:8081
    Ordenes       http://localhost:8082

EOF
