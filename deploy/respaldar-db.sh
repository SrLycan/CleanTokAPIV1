#!/usr/bin/env bash
# Respaldo de la base en el VPS: deja respaldos/cleantok-AAAAMMDD-HHMM.sql.gz (conserva los 14 más recientes).
# Diario a las 03:00:  (crontab -l; echo "0 3 * * * bash $HOME/cleantok/deploy/respaldar-db.sh") | crontab -
# Restaurar:           gunzip -c respaldos/<archivo>.sql.gz | sudo docker compose -f docker-compose.prod.yml exec -T db psql -U cleantok cleantok
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
mkdir -p respaldos
archivo="respaldos/cleantok-$(date +%Y%m%d-%H%M).sql.gz"
sudo docker compose -f docker-compose.prod.yml exec -T db pg_dump -U "${DB_USER:-cleantok}" "${DB_NAME:-cleantok}" | gzip > "$archivo"
ls -1t respaldos/*.sql.gz | tail -n +15 | xargs -r rm --
echo "Respaldo: $archivo"
