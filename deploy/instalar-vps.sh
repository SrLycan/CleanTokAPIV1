#!/usr/bin/env bash
# Instala y levanta CleanTok en un VPS Ubuntu (AWS Lightsail o EC2). Se puede volver a correr para actualizar.
#
#   bash deploy/instalar-vps.sh                 # dominio gratis <ip>.sslip.io con HTTPS
#   DOMINIO=api.midominio.com bash deploy/...   # dominio propio (registro A apuntando a la IP)
#   DOMINIO=:80 bash deploy/instalar-vps.sh     # solo HTTP por la IP
#
# La primera vez genera .env con claves aleatorias; las siguientes lo conserva.
# Las actualizaciones reinician una réplica de la API a la vez: Caddy sigue atendiendo con la otra.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="sudo docker compose -f docker-compose.prod.yml --env-file .env"

echo "==> 1/6 Docker"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER" || true
fi

echo "==> 2/6 Seguridad del servidor (firewall, fail2ban, actualizaciones automáticas)"
if ! dpkg -s fail2ban >/dev/null 2>&1; then
  sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ufw fail2ban unattended-upgrades >/dev/null
fi
# Firewall del sistema además del de AWS: solo SSH, HTTP y HTTPS (Docker publica 80/443 por su cuenta).
sudo ufw default deny incoming >/dev/null
sudo ufw default allow outgoing >/dev/null
sudo ufw allow 22/tcp >/dev/null
sudo ufw allow 80/tcp >/dev/null
sudo ufw allow 443 >/dev/null
sudo ufw --force enable >/dev/null
# Bloquea por 1 hora las IP que fallan 5 veces el SSH.
printf '[sshd]\nenabled = true\nmaxretry = 5\nbantime = 1h\n' | sudo tee /etc/fail2ban/jail.d/cleantok.local >/dev/null
sudo systemctl enable --now fail2ban >/dev/null 2>&1 || true
sudo systemctl restart fail2ban || true
# SSH solo con llave.
if grep -qE '^#?PasswordAuthentication yes' /etc/ssh/sshd_config 2>/dev/null; then
  sudo sed -i 's/^#\?PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
  sudo systemctl reload ssh || true
fi
sudo dpkg-reconfigure -f noninteractive unattended-upgrades >/dev/null 2>&1 || true

echo "==> 3/6 Memoria de intercambio (la compilación con Gradle necesita ~2 GB)"
RAM_MB=$(free -m | awk '/Mem:/ {print $2}')
if [ "$RAM_MB" -lt 3500 ] && [ -z "$(swapon --show --noheadings)" ]; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-cleantok.conf >/dev/null
  sudo sysctl -q -p /etc/sysctl.d/99-cleantok.conf
  echo "    swap de 2 GB activado (RAM: ${RAM_MB} MB)"
fi

echo "==> 4/6 Configuración (.env)"
if [ ! -f .env ]; then
  # IP pública: metadatos de EC2 (IMDSv2) o, si no, un servicio externo.
  TOKEN=$(curl -s -m 2 -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60" || true)
  IP=$(curl -s -m 2 -H "X-aws-ec2-metadata-token: ${TOKEN}" http://169.254.169.254/latest/meta-data/public-ipv4 || true)
  [[ "$IP" =~ ^[0-9.]+$ ]] || IP=$(curl -s -m 5 https://checkip.amazonaws.com | tr -d '[:space:]')
  DOMINIO="${DOMINIO:-$(echo "$IP" | tr . -).sslip.io}"
  if [ "$DOMINIO" = ":80" ]; then URL="http://$IP"; else URL="https://$DOMINIO"; fi
  aleatorio() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "$1"; }
  sed \
    -e "s|^DOMINIO=.*|DOMINIO=$DOMINIO|" \
    -e "s|^CLEANTOK_URL_PUBLICA=.*|CLEANTOK_URL_PUBLICA=$URL|" \
    -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(aleatorio 32)|" \
    -e "s|^JWT_SECRET=.*|JWT_SECRET=$(aleatorio 64)|" \
    -e "s|^CLEANTOK_SUPERADMIN_PASSWORD=.*|CLEANTOK_SUPERADMIN_PASSWORD=Cleantok-$(aleatorio 10)!|" \
    deploy/.env.prod.example > .env
  chmod 600 .env
  echo "    .env creado para $URL"
else
  echo "    .env ya existe: se conserva"
fi
set -a; . ./.env; set +a

esperar_sano() {  # espera a que el contenedor del servicio $1 esté "healthy"
  for _ in $(seq 1 60); do
    id=$($COMPOSE ps -q "$1" || true)
    if [ -n "$id" ] && [ "$(sudo docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null)" = healthy ]; then return 0; fi
    printf '.'; sleep 5
  done
  echo; echo "$1 no quedó sano en 5 minutos. Logs:  $COMPOSE logs --tail 100 $1"; return 1
}

echo "==> 5/6 Compilar la imagen (la primera vez tarda 5–10 minutos)"
$COMPOSE build api1

echo "==> 6/6 Levantar: base, réplica 1, réplica 2 y Caddy (una réplica a la vez)"
$COMPOSE up -d db
$COMPOSE up -d --no-deps api1;  esperar_sano api1; echo " api1 lista"
$COMPOSE up -d --no-deps api2;  esperar_sano api2; echo " api2 lista"
$COMPOSE up -d caddy
# Aplica cambios del Caddyfile sin reiniciar Caddy (no corta conexiones).
$COMPOSE exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || true
sudo docker image prune -f >/dev/null

echo
echo "  CleanTok listo en: $CLEANTOK_URL_PUBLICA   (2 réplicas detrás de Caddy)"
echo "  Swagger:           $CLEANTOK_URL_PUBLICA/swagger-ui"
echo "  Super admin:       ${CLEANTOK_SUPERADMIN_EMAIL:-superadmin@cleantok.ec} / $CLEANTOK_SUPERADMIN_PASSWORD"
echo "  Usuarios demo:     admin@brillototal.ec, maria@brillototal.ec… clave Demo2026!"
echo
echo "  APK con este servidor (en tu PC, carpeta CLEANTOK-ANDROID):"
echo "    .\\generar-apk.ps1 -Servidor $CLEANTOK_URL_PUBLICA"
