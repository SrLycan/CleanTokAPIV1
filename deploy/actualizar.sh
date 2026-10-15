#!/usr/bin/env bash
# Actualiza el servidor a una versión del repositorio (git) y la despliega sin cortar el servicio.
# Lo llama GitHub Actions en cada push a main; también se puede correr a mano en el servidor:
#
#   bash ~/cleantok/deploy/actualizar.sh              # última versión de main
#   bash ~/cleantok/deploy/actualizar.sh a1b2c3d      # un commit o tag concreto (por ejemplo, para volver atrás)
#
# .env, respaldos/ y los volúmenes de Docker no están en git: se conservan.
set -euo pipefail
cd "$(dirname "$0")/.."
REF="${1:-origin/main}"

git fetch --quiet --tags origin
ANTES=$(git rev-parse --short HEAD 2>/dev/null || echo "-")
git checkout --quiet --force --detach "$REF"
AHORA=$(git rev-parse --short HEAD)
echo "==> Versión: $ANTES → $AHORA  ($(git log -1 --format='%s' HEAD))"

bash deploy/instalar-vps.sh

# Historial de despliegues en el servidor.
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $AHORA $(git log -1 --format='%s' HEAD)" >> despliegues.log
