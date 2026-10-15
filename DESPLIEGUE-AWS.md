# Desplegar CleanTok en AWS (VPS) y compartir el APK

Resultado: la API en `https://<ip-con-guiones>.sslip.io` con HTTPS automático (sin comprar dominio), datos demo cargados,
y un APK que ya apunta ahí para pasárselo al equipo.

```
Celulares (APK) ──HTTPS──▶ Caddy :443  ──┬──▶ api1 :8080 (semilla + job de alertas) ──┐
                           reverse proxy  │                                            ├──▶ PostgreSQL 16
                           Let's Encrypt  └──▶ api2 :8080 (réplica)                 ──┘
                           balanceador least_conn + health checks      (red interna de Docker, sin puertos expuestos)
```

**Servidor actual**: Lightsail `cleantok-api` (us-east-1, Ubuntu 24.04, 2 GB, $12/mes de los créditos del plan gratuito),
IP estática `44.215.16.137` → **https://44-215-16-137.sslip.io**. Llave SSH: `~/.ssh/cleantok_aws` en la PC de Dennys.

Qué deja configurado el instalador:

| Capa | Qué hace |
|---|---|
| **Reverse proxy** (Caddy) | Único punto de entrada; HTTPS automático (Let's Encrypt), HTTP→HTTPS, HTTP/3, gzip/zstd, cabeceras de seguridad (HSTS, nosniff, DENY frames), límite de 25 MB por petición. |
| **Balanceador** (Caddy) | Reparte entre 2 réplicas de la API (`least_conn`), chequeo activo de `/actuator/health` cada 10 s, saca la réplica que falle y reintenta la petición en la otra. |
| **Actualizaciones sin corte** | `instalar-vps.sh` reinicia una réplica a la vez y la API se apaga en forma ordenada (`SERVER_SHUTDOWN=graceful`). |
| **Seguridad del servidor** | Firewall de AWS y `ufw` (solo 22/80/443), `fail2ban` para SSH, SSH solo con llave, actualizaciones de seguridad automáticas, base y API sin puertos públicos, claves aleatorias en `.env` (permisos 600). |
| **Operación** | `restart: unless-stopped` (vuelve solo tras reinicios), logs rotados (5×10 MB por contenedor), swap de 2 GB, respaldos con `deploy/respaldar-db.sh`. |

> **¿Y el Load Balancer de AWS?** Lightsail tiene uno administrado (USD 18/mes) que reparte entre *varios servidores*.
> Con un solo servidor no aporta nada sobre Caddy y gastaría los créditos 2,5 veces más rápido. Cuando haga falta crecer:
> segundo servidor + Lightsail Load Balancer (o ALB en EC2) + base administrada (Lightsail Database / RDS), y Caddy
> sigue igual en cada servidor.

## 1. Crear el servidor (una vez, ~5 min en la consola de AWS)

**Opción simple: Lightsail** → *Create instance* → Linux/Unix → **Ubuntu 24.04 LTS** → plan de **2 GB RAM** (≈ USD 10–12/mes).
En *Networking* agrega las reglas **HTTP (80)** y **HTTPS (443)** (SSH 22 ya viene) y asigna una **IP estática**.
Descarga la llave SSH (`.pem`).

**Opción EC2**: *Launch instance* → Ubuntu Server 24.04 → **t3.small** (2 GB). Disco 20 GB gp3. Grupo de seguridad con
entrada **22** (tu IP), **80** y **443** (0.0.0.0/0). Crea o elige un *key pair* (`.pem`). Asocia una **Elastic IP**
para que la IP no cambie al reiniciar.

> 1 GB (t2/t3.micro de la capa gratuita) también funciona: el instalador crea 2 GB de swap, pero la primera compilación tarda más.

## Actualizar con git (forma normal)

El servidor es un clon de este repositorio (`~/cleantok`, siguiendo `origin/main`). Cada `git push` a `main` dispara
**GitHub Actions** (`.github/workflows/desplegar.yml`): corre las pruebas y, si pasan, entra por SSH y ejecuta
`deploy/actualizar.sh <commit>`, que hace `git checkout` de ese commit y reinicia una réplica a la vez.

```powershell
git commit -am "Mejora X"; git push          # despliega solo
git tag v1.1; git push --tags                # versión con nombre
```

- Volver atrás: Actions → *Pruebas y despliegue* → *Run workflow* con el commit/tag (opción "saltar pruebas").
- Los pull requests solo corren las pruebas.
- Historial en el servidor: `~/cleantok/despliegues.log`.
- Secretos del repositorio (una vez): `SSH_HOST` = `44.215.16.137` y `SSH_PRIVATE_KEY` = llave `cleantok_github_actions`
  (está en el kit de traspaso; su parte pública ya está autorizada en el servidor). Sin ellos, Actions corre las
  pruebas y omite el despliegue con un aviso.

`deploy/subir-a-aws.ps1` (subir por SCP desde la PC) queda solo para un servidor nuevo.

## 2. Subir e instalar (desde tu PC con Windows)

En PowerShell, dentro de la carpeta `CLEANTOK`:

```powershell
.\deploy\subir-a-aws.ps1 -Ip 3.85.10.20 -Llave C:\Users\denny\Downloads\cleantok.pem
```

El script empaqueta el backend, lo sube por `scp` y corre `deploy/instalar-vps.sh` en el servidor, que:

1. instala Docker si no está;
2. agrega 2 GB de swap si la máquina tiene menos de 3,5 GB de RAM;
3. crea `.env` con **claves aleatorias** (base de datos, JWT y super admin) y `DOMINIO=3-85-10-20.sslip.io`;
4. compila y levanta `docker-compose.prod.yml` (Postgres + API + Caddy), todos con `restart: unless-stopped`;
5. espera a que la API responda e imprime la URL y la **clave del super admin** (guárdala).

Para **actualizar** después de cambiar código, corre el mismo comando: conserva `.env` y los datos.

Con dominio propio (registro A apuntando a la IP): agrega `-Dominio api.midominio.com`.
Sin HTTPS (solo para probar rápido): `-Dominio :80` y usa `http://<IP>` en la app.

## 3. Generar el APK para el equipo

En PowerShell, dentro de `CLEANTOK-ANDROID`:

```powershell
.\generar-apk.ps1 -Servidor https://3-85-10-20.sslip.io
```

Deja `dist\CleanTok-0.2-demo.apk`, firmado con `cleantok-equipo.jks` (se crea la primera vez; respáldalo con
`keystore.properties`: con la misma llave el equipo instala versiones nuevas encima sin desinstalar).
Compártelo por WhatsApp, Drive o Telegram. En el celular hay que permitir "Instalar apps de fuentes desconocidas".

En el login el servidor ya viene puesto; se puede cambiar en "Servidor → Cambiar".

## Usuarios para el equipo

Perfil `demo`: todos con clave **`Demo2026!`** — `admin@brillototal.ec` (admin), `maria@brillototal.ec` (encargada),
`ventas@lahigiene.ec` (proveedor), `reparto@lahigiene.ec` (repartidor)… Lista completa en el `README.md`.
El super admin (`superadmin@cleantok.ec`) usa la clave que imprimió el instalador (`CLEANTOK_SUPERADMIN_PASSWORD` en `.env`).

> Las claves demo son públicas. Sirve para que el equipo pruebe; antes de cargar datos reales cambia a
> `SPRING_PROFILES_ACTIVE=default` en `.env` (base vacía) o borra los usuarios demo.

## Operación diaria (por SSH en el servidor)

```bash
ssh -i cleantok.pem ubuntu@3.85.10.20
cd ~/cleantok
sudo docker compose -f docker-compose.prod.yml ps               # estado
sudo docker compose -f docker-compose.prod.yml logs -f api1 api2 # logs de las API
sudo docker compose -f docker-compose.prod.yml logs -f caddy    # peticiones y balanceo
sudo docker compose -f docker-compose.prod.yml restart api2     # reiniciar una réplica (la otra sigue atendiendo)
bash deploy/respaldar-db.sh                                     # respaldo a respaldos/*.sql.gz
(crontab -l; echo "0 3 * * * bash $HOME/cleantok/deploy/respaldar-db.sh") | crontab -   # respaldo diario
```

Empezar de cero con los datos demo (borra todo): `sudo docker compose -f docker-compose.prod.yml down -v` y volver a instalar.

## Problemas comunes

| Síntoma | Causa y arreglo |
|---|---|
| `scp` o `ssh` no conecta | Puerto 22 cerrado en el grupo de seguridad / firewall de Lightsail, o usuario equivocado (`ubuntu` en Ubuntu). |
| La app dice "Sin conexión" | Faltan las reglas 80/443. Prueba `https://<dominio>/actuator/health` en el navegador del celular. |
| Error de certificado | Caddy tarda ~30 s en obtenerlo la primera vez y necesita el puerto 80 abierto. Logs: `logs caddy`. |
| La compilación se corta (`Killed`) | Falta memoria: verifica el swap con `free -h` o usa una instancia de 2 GB. |
| Los QR abren otra dirección | `CLEANTOK_URL_PUBLICA` en `.env`; después `up -d` de nuevo. Los QR ya impresos conservan la URL anterior. |

Archivos: `docker-compose.prod.yml`, `deploy/Caddyfile`, `deploy/.env.prod.example`, `deploy/instalar-vps.sh`,
`deploy/subir-a-aws.ps1`, `deploy/respaldar-db.sh`.
