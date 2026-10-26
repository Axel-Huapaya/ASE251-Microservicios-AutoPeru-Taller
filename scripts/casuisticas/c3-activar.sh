#!/usr/bin/env bash
# Casuistica 3 - Repuesto estrella agotado en plena temporada alta.
# Las pastillas de freno (id 1) tienen 30 unidades. Una avalancha de ordenes de 5 unidades las agota y
# las siguientes ordenes se rechazan por falta de stock, con todos los servicios en UP.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

"$ROOT/loadtesting/stress.sh" -m ordenes -p 1 -q 5 -n 60 -c 3 -s 300
