# AutoPeru Taller - Microservicios Java con Monitoreo en Kubernetes

Curso: Kubernetes con Prometheus & Grafana - Valle Grande (individual)

Sistema de dos microservicios para el proyecto AutoPeru Taller (taller mecanico):

| Servicio | Rol | Que hace |
|---|---|---|
| **repuestos-service** (Servicio A) | Inventario de repuestos | Repuestos, precios y stock en memoria |
| **ordenes-service** (Servicio B) | API de ordenes | Para cada orden **consulta al servicio de repuestos por HTTP** (WebClient, con timeout) antes de confirmarla |

Monitoreo: Micrometer + Spring Boot Actuator (`/metrics`) -> Prometheus (ServiceMonitor) -> Grafana + alertas (PrometheusRule).

## Estructura

```
microservices/
  repuestos-service/    Spring Boot 3.3 / Java 17 / Maven / Dockerfile multi-stage
  ordenes-service/
k8s/
  00-namespace.yaml    namespace autoperu-taller
  repuestos/            deployment + service (NodePort 30081)
  ordenes/             deployment + service (NodePort 30082)
  monitoring/          values.yaml (Helm), servicemonitors, prometheusrule, dashboards/*.json
loadtesting/stress.sh  generador de trafico parametrizable
scripts/               setup.sh, teardown.sh, port-forward.sh, casuisticas/
docs/informe.md        informe tecnico + casuisticas
```

## 1. Instalacion de herramientas

Necesitas: Docker Desktop, Minikube, kubectl y Helm. Los scripts son Bash: en Windows ejecutalos desde **Git Bash** o **WSL2**.

### Windows (PowerShell como administrador, con winget)
```powershell
winget install -e --id Docker.DockerDesktop
winget install -e --id Kubernetes.minikube
winget install -e --id Kubernetes.kubectl
winget install -e --id Helm.Helm
winget install -e --id Git.Git      # incluye Git Bash
```
Reinicia la terminal y abre Docker Desktop una vez para que arranque el daemon.

### macOS (Homebrew)
```bash
brew install --cask docker
brew install minikube kubectl helm
```

### Linux (Ubuntu/Debian)
```bash
# Docker
curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker $USER   # cierra sesion y vuelve a entrar
# Minikube
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube
# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install kubectl /usr/local/bin/kubectl
# Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

### Verificar
```bash
docker --version && minikube version && kubectl version --client && helm version
```

## 2. Levantar todo (un solo comando)

```bash
./scripts/setup.sh
```

El script: verifica prerequisitos -> inicia Minikube (4 CPU / 6 GB) -> habilita `metrics-server` -> instala `kube-prometheus-stack` con `k8s/monitoring/values.yaml` -> apunta Docker al daemon de Minikube -> construye las dos imagenes -> aplica manifiestos, ServiceMonitors y alertas -> carga el dashboard -> muestra las URLs.

> Antes de ejecutarlo, **cambia la contrasena de Grafana** en `k8s/monitoring/values.yaml` (`grafana.adminPassword`).

### Paso a paso (equivalente manual)
```bash
minikube start --cpus=4 --memory=6144 --driver=docker
minikube addons enable metrics-server

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace -f k8s/monitoring/values.yaml --wait --timeout 15m

eval "$(minikube docker-env --shell bash)"      # construir DENTRO de Minikube
docker build -t autoperu-repuestos:1.0.0 microservices/repuestos-service
docker build -t autoperu-ordenes:1.0.0  microservices/ordenes-service

kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/repuestos/ -f k8s/ordenes/
kubectl apply -f k8s/monitoring/servicemonitors.yaml -f k8s/monitoring/prometheusrule.yaml
kubectl -n monitoring create configmap autoperu-taller-dashboard \
  --from-file=k8s/monitoring/dashboards/autoperu-taller-dashboard.json
kubectl -n monitoring label configmap autoperu-taller-dashboard grafana_dashboard=1
```

## 3. URLs

| Componente | NodePort (Linux) | Con port-forward (Docker Desktop) |
|---|---|---|
| Grafana | `http://<minikube ip>:30300` | `http://localhost:3000` |
| Prometheus | `http://<minikube ip>:30090` | `http://localhost:9090` |
| Alertmanager | `http://<minikube ip>:30093` | `http://localhost:9093` |
| Repuestos | `http://<minikube ip>:30081` | `http://localhost:8081` |
| Ordenes | `http://<minikube ip>:30082` | `http://localhost:8082` |

Con Docker Desktop en Windows/macOS la IP de Minikube normalmente no es accesible desde el navegador: ejecuta `./scripts/port-forward.sh` y deja esa terminal abierta.

Grafana: usuario `admin`, clave la de `values.yaml`.

## 4. API

**repuestos-service** (puerto 8080)

| Metodo | Ruta | Descripcion |
|---|---|---|
| GET | `/health` | Health check propio (sin dependencias) |
| GET | `/metrics` | Metricas Prometheus |
| GET | `/api/repuestos` | Lista repuestos |
| GET | `/api/repuestos/{id}` | Detalle |
| POST | `/api/repuestos` | Crear `{nombre,categoria,precio,stock}` |
| PUT | `/api/repuestos/{id}/stock` | Reponer stock `{stock}` |
| GET | `/api/repuestos/{id}/disponibilidad?cantidad=n` | Lo usa ordenes |
| POST | `/api/repuestos/{id}/reservar?cantidad=n` | Descuenta stock |

**ordenes-service** (puerto 8080)

| Metodo | Ruta | Descripcion |
|---|---|---|
| GET | `/health` | Health check propio (no consulta al servicio de repuestos) |
| GET | `/metrics` | Metricas Prometheus |
| POST | `/api/ordenes` | Crear orden `{repuestoId,cantidad}` (consulta al servicio de repuestos) |
| GET | `/api/ordenes` | Lista |
| GET | `/api/ordenes/{id}` | Detalle |
| PUT | `/api/ordenes/{id}/cancelar` | Cancelar |
| GET | `/api/ordenes/resumen` | Totales y ventas |

Respuestas controladas de ordenes: `201` creado, `404` repuesto inexistente, `409` sin stock, `503` repuestos no respondio a tiempo.

Variables de entorno: `REPUESTOS_DELAY_MS` (repuestos, retardo artificial), `REPUESTOS_URL` y `REPUESTOS_TIMEOUT_MS` (ordenes, por defecto 2000).

## 5. Metricas

| Servicio | Metrica | Tipo |
|---|---|---|
| repuestos | `repuestos_consultas_total`, `repuestos_reservas_total`, `repuestos_reservas_rechazadas_total` | Counter |
| repuestos | `repuestos_total`, `repuestos_agotados`, `repuestos_stock_total_unidades`, `repuestos_delay_artificial_ms` | Gauge |
| repuestos | `repuestos_consulta_seconds` | Timer |
| ordenes | `ordenes_creadas_total`, `ordenes_rechazadas_stock_total`, `ordenes_errores_repuestos_total{tipo}` | Counter |
| ordenes | `ordenes_activas`, `ordenes_ingresos_total_soles`, `ordenes_repuestos_timeout_ms` | Gauge |
| ordenes | `ordenes_repuestos_llamada_seconds` | Timer |

## 6. Casuisticas

Documentadas en `docs/informe.md` (Parte C). Scripts en `scripts/casuisticas/`:

| # | Activar | Resolver |
|---|---|---|
| 1 Servicio caido | `c1-activar.sh` | `c1-resolver.sh` |
| 2 Servicio lento | `c2-activar.sh [delay_ms]` | `c2-resolver.sh` |
| 3 Repuesto agotado | `c3-activar.sh` | `c3-resolver.sh` |

## 7. Limpieza

```bash
./scripts/teardown.sh             # borra microservicios y monitoreo
./scripts/teardown.sh --cluster   # ademas elimina el cluster de Minikube
```

## 8. Problemas frecuentes

| Sintoma | Causa probable |
|---|---|
| Pods en `ErrImageNeverPull` | Las imagenes se construyeron fuera de Minikube: `eval "$(minikube docker-env --shell bash)"` y reconstruir |
| Target no aparece en Prometheus | Labels del ServiceMonitor no coinciden con los del Service, o el puerto no se llama `http` |
| `/metrics` da 404 | Falta `management.endpoints.web.path-mapping.prometheus=metrics` (ver `application.yml`) |
| Alertas no aparecen | `PrometheusRule` sin label `release: monitoring` o selectores del `values.yaml` mal configurados |
