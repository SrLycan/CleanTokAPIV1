# CleanTok API

Backend de inventario y trazabilidad de insumos de limpieza para el **Hackathon Expo Clean Ecuador 2026, Reto 1: Optimización y trazabilidad logística para el abastecimiento de insumos**.

Una empresa de limpieza sube la orden de compra de un establecimiento en PDF. CleanTok propone la lista de materiales y el admin la revisa y confirma. Después, los encargados escanean el QR de cada piso desde la app Android para registrar ingresos, traslados y consumos con cantidades reales. Con eso el sistema calcula stock, consumo y días de cobertura, y alerta antes de que falte un insumo, indicando **quién debe reponerlo**: la empresa de limpieza o la entidad contratante.

Lo que compra la empresa sigue la cadena completa: CleanTok sugiere qué pedir y cuánto, arma una **solicitud consolidada**
de todos los sitios, los **proveedores** cotizan por producto (precio, disponibilidad, plazo y si envían o se retira),
la empresa compara con criterios visibles y aprueba, y se emite **una orden por proveedor**. El proveedor la acepta,
prepara y empaca; el **repartidor** sigue una **ruta optimizada** (km, tiempo y CO₂e frente a la ruta base) y el
**encargado confirma la recepción** en cada destino, lo que registra el ingreso a la bodega en el kardex. Cada paso queda
en un rastreo inalterable, como el de un courier.

---

## Levantar todo en menos de 5 minutos

Requisitos: **Docker Desktop**. Nada más.

```bash
docker compose up --build
```

La primera compilación tarda 2–3 minutos. Cuando el log diga `Semilla demo cargada`:

| Qué | Dónde |
|---|---|
| API | http://localhost:8080/api/v1 |
| Swagger UI (contrato para Android) | http://localhost:8080/swagger-ui |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Salud | http://localhost:8080/actuator/health |

El perfil `demo` (por defecto) carga 2 empresas, 4 establecimientos y **60 días de movimientos simulados**. Para empezar sin datos, usa `SPRING_PROFILES_ACTIVE=default` en `.env` (copia de `.env.example`).

### Credenciales de la demo

Contraseña de todos: **`Demo2026!`**

| Usuario | Rol | Empresa | Establecimientos |
|---|---|---|---|
| `admin@brillototal.ec` | ADMIN_EMPRESA | Brillo Total S.A. | Hospital Básico San Andrés, Edificio Torre Azul |
| `luis@brillototal.ec` | ENCARGADO | Brillo Total S.A. | Hospital |
| `maria@brillototal.ec` | ENCARGADO | Brillo Total S.A. | Hospital, Torre Azul |
| `admin@andina.ec` | ADMIN_EMPRESA | Limpieza Andina Cía. Ltda. | Oficinas Municipales Centro, Planta Textil La Esperanza |
| `ana@andina.ec` | ENCARGADO | Limpieza Andina | Oficinas Municipales |
| `pedro@andina.ec` | ENCARGADO | Limpieza Andina | Planta Textil |
| `superadmin@cleantok.ec` / `SuperAdmin2026!` | SUPER_ADMIN | — | crea empresas y admins |

Brillo Total tiene además **Clínica Los Valles**, **Edificio Plaza Norte** y **Colegio Bilingüe Los Andes** (Quito), para que
el reparto tenga varias paradas.

Proveedores (misma contraseña `Demo2026!`):

| Usuario | Rol | Proveedor | Entrega | Atiende a |
|---|---|---|---|---|
| `ventas@quimicospichincha.ec` | PROVEEDOR | Químicos del Pichincha | envío, retiro en local y en bodega | Brillo (preferido) |
| `reparto@quimicospichincha.ec` | REPARTIDOR | Químicos del Pichincha | | |
| `ventas@lahigiene.ec` | PROVEEDOR | Distribuidora La Higiene | envío y retiro en local | Brillo y Andina (compartido) |
| `reparto@lahigiene.ec` | REPARTIDOR | Distribuidora La Higiene | | |
| `ventas@proclean.ec` | PROVEEDOR | ProClean Ecuador | envío y retiro en bodega central | Brillo y Andina |
| `reparto@proclean.ec` | REPARTIDOR | ProClean Ecuador | | |
| `ventas@papeleraandina.ec` | PROVEEDOR | Papelera Andina del Austro | envío y retiro en local | Andina |

Pedidos que deja preparados la semilla:

| Empresa | Qué | Para mostrar |
|---|---|---|
| Brillo | SC-003 en cotización con 3 ofertas | comparar ofertas y aprobar la compra (admin) |
| Brillo | OC-003 recién aprobada (ProClean) | aceptar, preparar, empacar y asignar repartidor (proveedor) |
| Brillo | OC-002 en tránsito con 4 destinos (La Higiene, repartidor Diego) | ruta optimizada (repartidor) y confirmar recepción (María, Luis) |
| Brillo | OC-001 entregada | rastreo completo |
| Andina | OC-001 lista para retiro en el local del proveedor | modalidad de retiro |

Alertas que deja preparadas la semilla:

| Establecimiento | Producto | Alerta |
|---|---|---|
| Hospital Básico San Andrés | Alcohol antiséptico | **SIN_STOCK** (ALTA) |
| Edificio Torre Azul | Papel higiénico jumbo | **COBERTURA_BAJA** (lo provee la entidad) |
| Planta Textil La Esperanza | Jabón líquido para dispensador | **NIVEL_CRITICO** en la línea de producción |
| Oficinas Municipales Centro | Detergente | **STOCK_BAJO** |

### Escanear los QR con un celular

El QR contiene `{CLEANTOK_URL_PUBLICA}/q/{token}`. Para probarlo en la red local:

```bash
# .env
CLEANTOK_URL_PUBLICA=http://192.168.1.50:8080   # la IP de tu PC
```

Descarga la hoja imprimible (`GET /establecimientos/{id}/qr-hoja.pdf`) y escanéala: el celular abre la URL. Android intercepta esa URL como *deep link* y llama a `GET /api/v1/qr/{token}`. Para *App Links* verificados, define `CLEANTOK_ANDROID_PAQUETE` y `CLEANTOK_ANDROID_SHA256`. Si no carga desde el celular, revisa que el firewall de Windows permita el puerto 8080.

---

## Flujo de demo en 3 minutos

Todo está en Swagger UI (botón *Authorize* con el `accessToken`) o en [`docs/cleantok.http`](docs/cleantok.http).

1. **Orden en PDF → catálogo (60 s).** Entra como `admin@brillototal.ec`, crea un establecimiento y sube [`demo/orden-demo-sercop.pdf`](demo/orden-demo-sercop.pdf) en `POST /establecimientos/{id}/ordenes`. En un par de segundos `GET /ordenes/{id}` pasa a `PENDIENTE_REVISION`. `GET /ordenes/{id}/materiales` muestra:
   - los materiales con su **texto literal** y su página;
   - la **fusión** de "aromatizante, ambiental";
   - la **separación** de "…al 10% Insecticida";
   - el **posible duplicado** de insecticida;
   - los productos que **provee la entidad** (de la cláusula "Reposición de material…");
   - las herramientas como sugerencias no seleccionadas.

   Edita uno y confirma: se crean los productos con stock 0.
2. **QR y movimientos (60 s).** Entra como `maria@brillototal.ec`. Escanea (o llama) el QR de un piso de Torre Azul. Registra un **traslado** de 2 galones (se convierten a 7,57 L) y luego un **consumo** de 500 ml. Intenta consumir más de lo que hay: `STOCK_INSUFICIENTE` responde con lo disponible. Repite el mismo `clientUuid`: no se duplica.
3. **Alertas y trazabilidad (60 s).** `GET /establecimientos/{id}/alertas?estado=ABIERTA` muestra las alertas preparadas con quién repone. `GET /establecimientos/{id}/inventario` muestra stock por piso, consumo de 7 y 30 días, días de cobertura y estado. `GET /productos/{id}/kardex` muestra la trazabilidad bodega → piso → consumo, inalterable.

---

## Extracción con IA (opcional)

La demo **no depende de la IA**: el parser SERCOP lee las órdenes del portal de compras públicas sin conexión. Para otros formatos o PDF difíciles:

```bash
# .env
CLEANTOK_IA_HABILITADA=true
ANTHROPIC_API_KEY=sk-ant-...
CLEANTOK_IA_MODELO=claude-opus-5-5
```

A la IA solo se envían las secciones relevantes del texto, con correos, RUC, teléfonos y números de cuenta enmascarados. El PDF completo solo se envía si no tiene capa de texto y el admin lo autoriza (`permitirEnvioPdfIa=true`). Todo lo que propone la IA se **verifica literalmente** contra el texto del PDF: lo que no aparece queda en confianza BAJA y sin seleccionar. Nunca se crea un producto sin la confirmación del admin.

---

## Desarrollo

Requisitos: JDK 21 y Docker (las pruebas usan Testcontainers con PostgreSQL 16).

```bash
./gradlew test                 # todas las pruebas
docker compose up db           # solo Postgres
./gradlew bootRun --args='--spring.profiles.active=demo'
```

- **Pruebas doradas de las órdenes reales:** copia los PDF a `src/test/resources/ordenes/` (`orden-a-ucuenca-2021.pdf`, `orden-b-cnig-2019.pdf`, `orden-c-mintel-2026.pdf`). Contienen datos personales y están en `.gitignore`; sin ellos, esas pruebas se saltan.
- **Prueba con la IA real:** `OrdenAConIaRealTest` solo corre si existe `ANTHROPIC_API_KEY`.

### Arquitectura

Monolito modular en Kotlin + Spring Boot 4, con un paquete por dominio:

```
ec.cleantok
├── comun            errores RFC 7807, seguridad JWT, configuración
├── identidad        empresas, usuarios, login y refresh tokens
├── establecimiento  establecimientos, asignaciones, ubicaciones y QR
├── catalogo         productos y unidades
├── inventario       saldos, movimientos, kardex, lote offline y pronóstico
├── monitoreo        reportes de nivel, alertas, job horario y notificador
├── archivos         almacenamiento (local / Supabase) y fotos
├── ordenes          lectura del PDF (parser SERCOP, IA y verificación) y revisión
└── demo             semilla del perfil demo
```

Garantías en la base de datos, no solo en el código:
- `CHECK (cantidad >= 0)` en los saldos.
- Movimientos inmutables por *trigger*.
- Claves foráneas compuestas que impiden cruzar datos entre empresas.
- Una sola bodega por establecimiento.
- Una sola alerta abierta por (tipo, producto, ubicación).

### Abastecimiento (fase 7)

| Rol | Endpoints |
|---|---|
| ADMIN_EMPRESA | `GET /abastecimiento/sugerencias`, `GET /proveedores`, `POST/GET /solicitudes`, `POST /solicitudes/{id}/enviar · cancelar · adjudicar` |
| PROVEEDOR | `GET /proveedor/perfil · solicitudes · repartidores`, `PUT /proveedor/solicitudes/{id}/oferta`, `POST /ordenes-compra/{id}/repartidor` |
| Todos los de la cadena | `GET /ordenes-compra`, `GET /ordenes-compra/{id}` (rastreo y acciones), `POST /ordenes-compra/{id}/estado`, `GET /ordenes-compra/{id}/ruta` |
| REPARTIDOR | `GET /rutas/mia`, `POST /ordenes-compra/{id}/entregas/{entregaId}/entregar` |
| ENCARGADO / ADMIN | `POST /ordenes-compra/{id}/entregas/{entregaId}/recibir` (registra el INGRESO a bodega) |

Cada rol ve solo lo suyo: la empresa sus solicitudes y órdenes, el encargado las órdenes con destino en sus sitios,
el proveedor las solicitudes a las que lo invitaron y sus órdenes (nunca las ofertas de otros), y el repartidor las
órdenes que tiene asignadas (sin precios).

### Cuentas y plataforma (fase 8)

| Rol | Gestiona | Endpoints |
|---|---|---|
| SUPER_ADMIN (`superadmin@cleantok.ec`) | Empresas con su primer admin, proveedores con su primer usuario de ventas | `GET /plataforma/resumen · empresas · proveedores`, `POST /empresas`, `POST /empresas/{id}/admins`, `PATCH /plataforma/empresas/{id}`, `POST/PATCH /plataforma/proveedores`, `POST /plataforma/usuarios/{id}/restablecer-clave` |
| ADMIN_EMPRESA | Encargados y otros admins, sus sitios, proveedores con los que trabaja, establecimientos y pisos | `POST /usuarios`, `POST /usuarios/{id}/restablecer-clave`, `PUT /establecimientos/asignaciones/{usuarioId}`, `GET /proveedores/directorio`, `PUT/DELETE /proveedores/{id}/vinculo` |
| PROVEEDOR | Repartidores y vendedores, su perfil de entrega | `GET/POST /proveedor/equipo`, `PATCH /proveedor/equipo/{id}`, `POST /proveedor/equipo/{id}/restablecer-clave`, `PUT /proveedor/perfil` |

Las cuentas nuevas reciben una **clave temporal** (se muestra una vez) y deben cambiarla al entrar (`POST /auth/cambiar-clave`).

### Códigos de error

Todas las respuestas de error son `application/problem+json` con un `codigo` estable:

`STOCK_INSUFICIENTE`, `QR_INVALIDO`, `QR_NO_ASIGNADO`, `PRODUCTO_INACTIVO`, `UNIDAD_INCOMPATIBLE`, `PDF_INVALIDO`, `PDF_DUPLICADO`, `EXTRACCION_FALLIDA`, `VALIDACION`, `NO_AUTORIZADO`, `NO_ENCONTRADO`, `CONFLICTO`, `ERROR_INTERNO`.

### Documentos

- [`DECISIONES.md`](DECISIONES.md): decisiones de diseño y puntos de extensión.
- [`THIRD_PARTY.md`](THIRD_PARTY.md): dependencias y licencias.
- [`.env.example`](.env.example): todas las variables de entorno.
- [`docs/cleantok.http`](docs/cleantok.http): colección de requests.

### Puntos de extensión (fase 2 del reto)

| Qué | Dónde |
|---|---|
| Predicción de consumo | `PronosticoConsumo` (hoy, promedio de 14 días) |
| Verificación del nivel por foto con IA | `reporte_nivel.verificacion_ia` y `nivel_ia` |
| Notificaciones push | `Notificador` (hoy, log) |
| Optimización de rutas (OR-Tools, ventanas horarias, capacidad del vehículo) | `OptimizadorRutas` (hoy, vecino más cercano + 2-opt sobre la matriz por calles de OSRM) |
| Enrutador propio | `CLEANTOK_OSRM_URL` (hoy, el servidor público de demos de OSRM; vacío = línea recta) |
