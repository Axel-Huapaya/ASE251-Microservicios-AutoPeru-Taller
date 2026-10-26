#!/usr/bin/env bash
# Casuistica 3 - Resolucion: reponer el stock de las pastillas de freno (id 1) a 30 unidades.
set -euo pipefail

URL="${REPUESTOS_URL:-}"
if [ -z "$URL" ]; then
  if curl -s -m 1 -o /dev/null http://localhost:8081/health; then
    URL=http://localhost:8081
  else
    URL="http://$(minikube ip):30081"
  fi
fi

curl -s -X PUT "$URL/api/repuestos/1/stock" -H 'Content-Type: application/json' -d '{"stock": 30}'
echo
