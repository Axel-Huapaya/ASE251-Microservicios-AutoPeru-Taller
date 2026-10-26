#!/usr/bin/env bash
# Genera trafico hacia los servicios para alimentar Grafana durante las casuisticas.
#
# Uso:
#   ./loadtesting/stress.sh [-m modo] [-n total] [-c concurrencia] [-p repuestoId] [-q cantidad] [-s pausaMs]
#
# Modos:
#   repuestos   GET al servicio de repuestos (lista repuestos)
#   ordenes    POST de ordenes (repuesto -p, cantidad -q)   <- ejercita la comunicacion A <-> B
#   mixto      alterna ambos                                 (por defecto)
#
# URLs: variables REPUESTOS_URL y ORDENES_URL. Si no se definen, se detecta automaticamente:
#   localhost:8081 / localhost:8082 (port-forward) o <minikube ip>:30081 / :30082
set -uo pipefail

MODO="mixto"; TOTAL=200; CONC=5; REPUESTO=2; CANTIDAD=1; PAUSA_MS=0

while getopts "m:n:c:p:q:s:h" opt; do
  case "$opt" in
    m) MODO="$OPTARG" ;;
    n) TOTAL="$OPTARG" ;;
    c) CONC="$OPTARG" ;;
    p) REPUESTO="$OPTARG" ;;
    q) CANTIDAD="$OPTARG" ;;
    s) PAUSA_MS="$OPTARG" ;;
    h|*) sed -n '2,15p' "$0"; exit 0 ;;
  esac
done

detectar() { # $1 = puerto local, $2 = nodePort
  if curl -s -m 1 -o /dev/null "http://localhost:$1/health"; then
    echo "http://localhost:$1"
  elif command -v minikube >/dev/null 2>&1; then
    echo "http://$(minikube ip 2>/dev/null):$2"
  else
    echo "http://localhost:$1"
  fi
}

REPUESTOS_URL="${REPUESTOS_URL:-$(detectar 8081 30081)}"
ORDENES_URL="${ORDENES_URL:-$(detectar 8082 30082)}"

echo "Modo=$MODO total=$TOTAL concurrencia=$CONC repuesto=$REPUESTO cantidad=$CANTIDAD"
echo "Repuestos: $REPUESTOS_URL"
echo "Ordenes : $ORDENES_URL"

una_peticion() { # $1 = indice
  local i="$1" modo="$MODO"
  if [ "$modo" = "mixto" ]; then
    if [ $((i % 2)) -eq 0 ]; then modo="repuestos"; else modo="ordenes"; fi
  fi
  local codigo
  if [ "$modo" = "repuestos" ]; then
    codigo=$(curl -s -o /dev/null -m 10 -w '%{http_code}' "$REPUESTOS_URL/api/repuestos")
  else
    codigo=$(curl -s -o /dev/null -m 10 -w '%{http_code}' -X POST "$ORDENES_URL/api/ordenes" \
      -H 'Content-Type: application/json' \
      -d "{\"repuestoId\": $REPUESTO, \"cantidad\": $CANTIDAD}")
  fi
  [ "$PAUSA_MS" -gt 0 ] && sleep "$(awk "BEGIN{print $PAUSA_MS/1000}")"
  echo "$modo $codigo"
}
export -f una_peticion
export MODO REPUESTO CANTIDAD PAUSA_MS REPUESTOS_URL ORDENES_URL

RESULTADOS=$(seq 1 "$TOTAL" | xargs -P "$CONC" -I{} bash -c 'una_peticion {}')

echo
echo "Resumen (servicio codigo_http : cantidad):"
echo "$RESULTADOS" | sort | uniq -c | awk '{printf "  %-10s %s : %s\n", $2, $3, $1}'
