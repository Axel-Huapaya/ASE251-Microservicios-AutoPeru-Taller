#!/usr/bin/env bash
# Expone los servicios en localhost (util con Docker Desktop, donde la IP de Minikube no es alcanzable).
# Deja el script corriendo; Ctrl+C cierra todos los tuneles.
set -uo pipefail

trap 'kill 0' EXIT

kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80 &
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090 &
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-alertmanager 9093:9093 &
kubectl -n autoperu-taller port-forward svc/repuestos-service 8081:8080 &
kubectl -n autoperu-taller port-forward svc/ordenes-service 8082:8080 &

cat <<'EOF'

  Grafana       http://localhost:3000
  Prometheus    http://localhost:9090
  Alertmanager  http://localhost:9093
  Repuestos      http://localhost:8081/api/repuestos
  Ordenes       http://localhost:8082/api/ordenes

  Ctrl+C para cerrar.
EOF
wait
