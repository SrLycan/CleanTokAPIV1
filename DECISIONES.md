# Decisiones de diseño

Registro de decisiones tomadas donde el prompt no definía algo o donde se eligió la opción más simple.
Cada entrada indica la fase en que se tomó.

## Fase 1 — Base

| # | Decisión | Motivo |
|---|---|---|
| D1 | **Spring Boot 4.1.1 + springdoc-openapi 3.1.1 + Kotlin 2.3.21** (Java 21, Gradle 9.8.1). | En octubre de 2026, Boot 3.5 ya no tiene soporte OSS. springdoc 3.1.1 está compilado contra Boot 4.1. Versiones fijadas en `build.gradle.kts`. |
| D2 | Monolito modular con un paquete por dominio (`identidad`, `establecimiento`, `catalogo`, `inventario`, `monitoreo`, `ordenes`, `archivos`, `demo`) y uno `comun`. | Simple de levantar y demostrar; los límites quedan claros para separar más adelante. |
| D3 | JWT HS256 firmado con `JWT_SECRET` (mínimo 32 bytes), validado por el resource server de Spring Security. Claims: `sub` (usuario), `rol` y `emp` (empresa). | Sin servidor de identidad externo. |
| D4 | El refresh token es **opaco** (32 bytes aleatorios) y se guarda como SHA-256 en `refresh_token`. Cada uso lo rota dentro de una `familia_id`. Reusar un token rotado **revoca toda la familia**. | Rotativo y revocable, como pide el prompt, con detección de robo. |
| D5 | Desactivar un usuario o cambiar su contraseña revoca sus refresh tokens. El access token vigente (15 min como máximo) sigue siendo válido hasta expirar. | Compromiso habitual en JWT stateless. |
| D6 | **Aislamiento**: `empresa_id` se toma solo del JWT (`ContextoSeguridad.empresaId()`) y los repositorios buscan con `findByIdAndEmpresaId`. Un recurso de otra empresa responde **404**. Además de las constraints y FKs, todas las tablas de dominio llevarán `empresa_id` desnormalizado. | Filtro directo y sin joins. Deja lista la base para RLS si se activa después (no se activa ahora: complica el pool y los jobs). |
| D7 | `CoberturaAislamientoTest` falla si algún `@RestController` no tiene una clase de prueba anotada con `@VerificaAislamiento`. `AuthController` está exento porque no expone recursos de una empresa. | Cumple "pruebas de aislamiento en cada controlador" de forma verificable. |
| D8 | Códigos de error añadidos a la lista del prompt: `NO_ENCONTRADO` (404), `CONFLICTO` (409, por ejemplo email o RUC duplicado) y `ERROR_INTERNO` (500). `NO_AUTORIZADO` se usa tanto en 401 (sin sesión) como en 403 (rol sin permiso); Android los distingue por el `status`. | Android necesita un `codigo` en toda respuesta de error. |
| D9 | Los errores de validación traen `errores: [{campo, mensaje}]`. El locale es fijo en `es`. | Mensajes en español. |
| D10 | El `ADMIN_EMPRESA` crea a los encargados de su empresa (`POST /usuarios`). El `SUPER_ADMIN` crea empresas y sus admins. | El prompt no decía quién crea a los encargados. |
| D11 | El SUPER_ADMIN inicial se crea al arrancar desde `CLEANTOK_SUPERADMIN_EMAIL` y `CLEANTOK_SUPERADMIN_PASSWORD`, solo si no existe. | Hace falta un primer usuario para poder iniciar sesión. |
| D12 | Las entidades usan UUID generado en la aplicación e implementan `Persistable`, de modo que `save()` hace INSERT sin SELECT previo. | Rendimiento y claridad. |
| D13 | Paginación serializada como `PagedModel`: `{content, page:{size, number, totalElements, totalPages}}`. | Formato JSON estable que recomienda Spring Data. |
| D14 | Las órdenes reales (PDF con datos personales) están en `src/test/resources/ordenes/` e ignoradas por `.gitignore`. Las pruebas doradas se saltan si los archivos no existen. | Evita publicar datos personales. |

## Decisiones por defecto confirmadas para fases siguientes

- La regla `SIN_STOCK` solo se evalúa en productos que ya tuvieron al menos un `INGRESO`, para no alertar de golpe todo el catálogo recién confirmado (stock 0).
- `NIVEL_CRITICO` se cierra con un `TRASLADO` posterior a ese piso o con un reporte nuevo de nivel > 25 %.
- El PDF se procesa con `@Async` dentro del mismo proceso. Al arrancar se reencolan las órdenes que quedaron en `PROCESANDO`.
- `CLEANTOK_IA_MODELO` vale por defecto `claude-opus-5-5`.
- `movimiento.cantidad` se guarda en la unidad del producto (ya convertida). `cantidad_original` y `unidad_original` registran lo que envió Android.
- El umbral de "25 % del último traslado" lo calcula el servidor y lo devuelve como `sugerirReporteNivel`.
- Las fotos WEBP se leen con TwelveMonkeys y se guardan como JPEG de 1600 px como máximo.
- Un `ocurridoEn` más de 10 min en el futuro respecto al servidor da `VALIDACION`.

## Fase 2 — Establecimientos, ubicaciones y QR

| # | Decisión | Motivo |
|---|---|---|
| D15 | FKs compuestas `(establecimiento_id, empresa_id)` y `(usuario_id, empresa_id)` en `ubicacion` y `usuario_establecimiento`. | La base impide que una ubicación o una asignación cruce de empresa, aunque haya un error en el código. |
| D16 | Índice único parcial `ubicacion (establecimiento_id) WHERE tipo='BODEGA'` y `CHECK (tipo <> 'BODEGA' OR activo)`. La API no permite crear otra bodega, cambiarle el tipo ni desactivarla. | Exactamente una bodega, siempre activa. |
| D17 | `qr_token`: 18 bytes de `SecureRandom` en base64url (24 caracteres). Regenerarlo invalida el anterior. | Opaco, imposible de adivinar y sin ids internos. |
| D18 | `CLEANTOK_URL_PUBLICA` incluye el esquema (`http://192.168.x.x:8080` o `https://api.cleantok.ec`). El QR codifica `{CLEANTOK_URL_PUBLICA}/q/{token}`. | Permite la demo por LAN sin HTTPS. En producción, usar `https`. |
| D19 | `GET /qr/{token}`: un token inexistente, regenerado, inactivo, de un establecimiento inactivo **o de otra empresa** da `QR_INVALIDO` (404). Un token de la propia empresa en un establecimiento no asignado al encargado da `QR_NO_ASIGNADO` (403). | No revela la existencia de QR de otras empresas. |
| D20 | `GET /q/{token}` es una página HTML pública y genérica que **no consulta la base**. `/.well-known/assetlinks.json` se publica solo si se configuran `CLEANTOK_ANDROID_PAQUETE` y `CLEANTOK_ANDROID_SHA256` (App Links verificados). | Fallback si la app no está instalada, sin filtrar datos. |
| D21 | El encargado lista las ubicaciones activas de sus establecimientos sin `urlQr`. PNG, hoja PDF, regenerar y CRUD son solo para el admin. | El encargado no necesita reimprimir QR. |
| D22 | Hoja PDF: A4, 2 × 2, líneas de corte, nombre del establecimiento, nombre de la ubicación (la letra se reduce si no cabe) y QR de 230 pt con corrección de errores M. Usa fuentes estándar de PDFBox (WinAnsi: tildes, ñ y "–"). Otros caracteres se reemplazan por `?`. Solo incluye ubicaciones activas. | Imprimible sin fuentes embebidas. Una prueba renderiza el PDF y decodifica cada QR. |
| D23 | `GET /qr/{token}` se completa con implementaciones de la interfaz `ContenidoQr`. Los productos con stock (fase 3) y las alertas abiertas (fase 4) se agregan ahí. | Evita que el módulo de establecimientos dependa de inventario. |
| D24 | `GET /establecimientos/mios` y `/auth/me` devuelven, para el admin, todos los establecimientos activos de su empresa, y para el encargado, solo los activos asignados. | — |

## Fase 3 — Movimientos e inventario

| # | Decisión | Motivo |
|---|---|---|
| D25 | Android envía **la ubicación escaneada** (`ubicacionId`) y el servidor deduce origen y destino según el tipo: INGRESO → bodega (solo en la bodega); TRASLADO → bodega → piso; CONSUMO → sale del piso; DEVOLUCION → piso → bodega. Usar el QR que no corresponde al tipo da `VALIDACION`. **El CONSUMO no se registra en la bodega**: se consume en los pisos. | Coincide con el flujo "Agregar/Retirar" del QR del piso y mantiene la trazabilidad bodega → piso → consumo. |
| D26 | `AJUSTE` lleva `sentido` (`ENTRADA` o `SALIDA`) y `motivo` obligatorios, en cualquier ubicación. Solo el admin; para un encargado da `NO_AUTORIZADO` (403). La cantidad siempre es positiva. | Respeta `cantidad > 0` y la forma origen/destino del movimiento. |
| D27 | `movimiento.cantidad` se guarda en la unidad del producto (redondeada a 3 decimales, HALF_UP). `cantidad_original` y `unidad_original` conservan lo que envió Android. Si una conversión redondea a 0 (por ejemplo 0,4 ml → L) se rechaza con `VALIDACION`. La unidad de un producto no se puede cambiar si ya tiene movimientos. | Kardex y saldos coherentes en una sola unidad. |
| D28 | Estados HTTP: `STOCK_INSUFICIENTE`, `PRODUCTO_INACTIVO` y `UNIDAD_INCOMPATIBLE` → **422**. `STOCK_INSUFICIENTE` trae `disponible`, `unidad`, `ubicacionId` y `productoId`. `UNIDAD_INCOMPATIBLE` trae `unidadesPermitidas`. | Android distingue una regla de negocio (422) de un error de formato (400). |
| D29 | Concurrencia: dentro de una transacción, `INSERT … ON CONFLICT DO NOTHING` crea la fila de stock si falta. Luego `SELECT … FOR UPDATE` bloquea las filas afectadas **en orden de id de ubicación**, para que traslados y devoluciones simultáneos no se bloqueen entre sí. La base tiene además `CHECK (cantidad >= 0)`. | Prueba: 40 traslados simultáneos de 3 L sobre 100 L → exactamente 33 aplicados y una cadena de saldos 97, 94 … 1. |
| D30 | Idempotencia: se busca el `clientUuid` antes de aplicar. Si dos peticiones iguales llegan a la vez, el índice único hace fallar a la segunda, que relee y devuelve el original. Respuesta: **201** si se aplicó y **200** con `duplicado: true` si se repitió. Un `clientUuid` usado por otra empresa da `CONFLICTO` sin revelar datos. | Prueba: 10 envíos simultáneos del mismo `clientUuid` → un solo movimiento. |
| D31 | Inmutabilidad: trigger `BEFORE UPDATE OR DELETE` que lanza una excepción, y la entidad es `@Immutable`. La columna `secuencia` (bigserial) da el orden estricto de aplicación; el kardex se ordena por ella, no por `ocurrido_en`. | Con sincronización offline, el orden en que se aplican los saldos no siempre coincide con el orden de los hechos. |
| D32 | `ocurridoEn` más de 10 min en el futuro respecto al servidor da `VALIDACION`. Las fechas pasadas se aceptan (sincronización offline). | — |
| D33 | Lote offline: hasta 100 ítems, ordenados por `ocurridoEn` (los que no tienen fecha van primero y fallan la validación). Cada ítem se valida y se procesa en su propia transacción. La respuesta va **en el orden original** con `indice`, `resultado` (`OK`, `DUPLICADO` o `ERROR`), `codigo`, `mensaje` y `detalle`. Los ítems `REPORTE_NIVEL` se procesan desde la fase 4. | — |
| D34 | `sugerirReporteNivel` = CONSUMO cuyo saldo en el piso queda ≤ `CLEANTOK_UMBRAL_SUGERENCIA_NIVEL` (0,25) × la cantidad del último TRASLADO a ese piso. | Lo calcula el servidor; Android solo muestra el diálogo. |
| D35 | `PronosticoConsumo` (punto de extensión) se implementa hoy con `PronosticoPromedio14Dias`: suma de CONSUMO por `ocurrido_en` en los últimos 14 días ÷ 14, y "sin datos suficientes" si hay menos de 3 días distintos (fecha de Ecuador). Días de cobertura = (bodega + pisos) ÷ consumo diario, con 1 decimal. | Tal como pide el prompt, y reemplazable sin tocar a quien lo usa. |
| D36 | Estado del inventario (se calcula directamente, no depende de las alertas): **CRITICO** si el stock total es 0 y hubo movimientos, o la cobertura es menor que 2 días; **BAJO** si la bodega está en o bajo el stock mínimo (con movimientos) o la cobertura es menor que `CLEANTOK_DIAS_COBERTURA_MIN`; **SIN_DATOS** si no hay consumo suficiente; **OK** en otro caso. Los no consumibles siempre están en OK. | Reemplaza la decisión anterior ("según la alerta abierta"): así el estado es determinista y coherente con las reglas de alerta de la fase 4. |
| D37 | Productos: `es_consumible` vale por defecto `true`, salvo en MAQUINARIA. El nombre se normaliza (minúsculas, sin tildes, espacios colapsados) y se guarda en `nombre_normalizado` con índice único por establecimiento. `DELETE` solo procede si el producto no tiene movimientos; si los tiene, da `CONFLICTO` y hay que desactivarlo. El encargado solo ve productos activos. | "No se borran productos con movimientos; solo se desactivan." |
| D38 | `GET /qr/{token}` incluye todos los productos activos con `stockUbicacion`, `stockBodega` y `unidadesCompatibles` (también los que tienen 0, para poder registrar un ingreso o traslado). | Android arma la pantalla de escaneo con una sola llamada. |
| D39 | `movimiento.foto_id` todavía no tiene FK: la tabla `archivo` llega en la fase 4 y ahí se agrega la FK y la validación. | — |

## Fase 4 — Nivel, fotos y alertas

| # | Decisión | Motivo |
|---|---|---|
| D40 | `AlmacenamientoArchivos` con dos implementaciones: **local** (por defecto, bajo `ALMACENAMIENTO_RUTA`, sin poder escapar con `..`) y **Supabase Storage** por su API REST, sin SDK (`ALMACENAMIENTO_TIPO=supabase`, `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_BUCKET`). La base guarda la ruta relativa. Si la transacción se revierte, se borra el binario. | La implementación de Supabase **no está probada contra un servicio real**; la local, sí. |
| D41 | Fotos: el formato se reconoce por los *magic bytes*, no por la extensión ni por el MIME declarado. Se reescala a 1600 px como máximo y se guarda siempre como JPEG de calidad 0,85, **sin EXIF** (sin GPS). Las transparencias quedan sobre blanco. WEBP se lee con TwelveMonkeys. | Privacidad y peso para la red móvil. ImageIO no puede escribir WEBP, así que no hay prueba automática de WEBP. |
| D42 | El `fotoId` de movimientos y reportes debe ser una FOTO de la misma empresa: se valida en el servidor y lo refuerza una FK compuesta `(foto_id, empresa_id)`. | Aislamiento. |
| D43 | Reporte de nivel solo en pisos o áreas (en la bodega da `VALIDACION`). Es idempotente por `clientUuid` (201 nuevo, 200 duplicado) y entra en el lote offline. `verificacion_ia = NO_APLICA` y `nivel_ia` quedan como ganchos. | — |
| D44 | Reglas: `SIN_STOCK` solo si el producto ya tuvo algún INGRESO. `STOCK_BAJO` no se abre si ya aplica `SIN_STOCK`. `COBERTURA_BAJA` es del establecimiento (`ubicacion_id = null`). `NIVEL_CRITICO` es por piso: abre con el último reporte ≤ 25 % (ALTA si es 0 %) y se cierra con un TRASLADO o un AJUSTE de entrada a ese piso **posterior al reporte** (comparando `ocurrido_en`), o con un reporte nuevo > 25 %. Solo se evalúan productos activos y consumibles. | Evita alertas de golpe al confirmar el catálogo y evita duplicar la misma causa. |
| D45 | Si la condición cambia de grado, la alerta abierta **se actualiza** (severidad y mensaje) en lugar de abrir otra. | "Una alerta abierta por (tipo, producto, ubicación)", garantizado además con un índice único parcial. |
| D46 | Cierre manual (`PATCH /alertas/{id}/atender`, admin o encargado asignado): la alerta no se reabre mientras la condición siga igual. Se "rearma" cuando la condición desaparece, o en `NIVEL_CRITICO` cuando llega un reporte nuevo. | Sin esto, una alerta atendida reaparecería con el siguiente movimiento o con el job de la hora. |
| D47 | La evaluación corre en la misma transacción del movimiento o reporte, serializada por producto con `pg_advisory_xact_lock`. La respuesta trae las alertas **recién abiertas**. Job `@Scheduled` cada hora (`CLEANTOK_ALERTAS_CRON`, hora de Ecuador; se desactiva con `CLEANTOK_ALERTAS_JOB=false`), con una transacción por producto. `POST /establecimientos/{id}/alertas/reevaluar` (admin) hace lo mismo bajo demanda. | Dos consumos simultáneos no abren la misma alerta dos veces. |
| D48 | El mensaje de la alerta termina con quién repone: "Lo provee la entidad contratante." o "Lo compra la empresa de limpieza.". | Pedido explícito del prompt. |
| D49 | `GET /qr/{token}` incluye las alertas abiertas de esa ubicación; en la bodega, también las del establecimiento (COBERTURA_BAJA). | El encargado ve en la bodega lo que hay que pedir. |
| D50 | `Notificador` (punto de extensión para push o correo) se implementa hoy como `NotificadorLog`, que solo registra ids y tipo. | Sin datos personales en los logs. |

## Fase 5 — Lectura de PDF

| # | Decisión | Motivo |
|---|---|---|
| D51 | **Salida estructurada con esquema JSON** (`output_config.format`) en lugar de *tool use* forzado. | En los modelos actuales de Claude (`claude-opus-5-5`, el valor por defecto de `CLEANTOK_IA_MODELO`), `tool_choice` forzado devuelve 400. La salida estructurada garantiza igualmente un JSON válido con el esquema `items: [{texto_original, nombre, categoria, unidad_sugerida, presentacion, seccion_origen, abastece, pagina}]`. |
| D52 | Llamada a Claude con timeout de 60 s (`CLEANTOK_IA_TIMEOUT_SEG`), 1 reintento y *fallbacks* del lado del servidor (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`): si el modelo rechaza la petición, la API reintenta con otro modelo. Una respuesta `refusal` o truncada se trata como fallo de la IA. | — |
| D53 | **Privacidad**: a la IA solo se envían las secciones relevantes (materiales, herramientas, maquinaria, equipamiento y las oraciones con cláusulas "no incluye / proporcionado / suministrado"), con correos, RUC, cédulas, teléfonos y cualquier número de 7 dígitos o más enmascarados. El PDF completo solo se envía si no tiene capa de texto **y** se subió con `permitirEnvioPdfIa=true`. Nada del PDF ni de la respuesta va a los logs. | La prueba `ExtraccionIaTest` captura lo enviado y verifica que no contenga datos personales. |
| D54 | Se usa la IA cuando el formato no es SERCOP, el parser no encuentra materiales, más del 20 % de los materiales quedan en BAJA, o el admin pide `reintentar-ia` (que **reemplaza** las propuestas actuales). Al combinar: lo que está en ambos queda del parser; lo que solo encontró la IA entra en MEDIA con "solo lo detectó la IA"; lo que la IA no vio se marca "la IA no lo detectó". | "Deduplica y marca las diferencias." |
| D55 | Verificación literal para **todos** los ítems (parser e IA): `texto_original` normalizado debe aparecer en el texto normalizado del PDF. Si no aparece: BAJA, "no verificado en el texto" y no seleccionado. Sin capa de texto, todo queda en BAJA con aviso. | Anti-alucinación. |
| D56 | Limpieza de ruido: encabezados y pies del portal, URL, "N de M", fecha de impresión y guiones de corte. Los fragmentos "(12" / "meses)" de la columna Cant se limpian **solo desde la tabla DETALLE** (antes de ella, un "(7" al final de línea puede ser texto legítimo). De ahí se toma `vigencia_meses`. | Encontrado al probar la orden B. |
| D57 | Separación de ítems: comas y punto y coma (no entre dígitos), "•" y "*" como viñeta, **sin cortar dentro de paréntesis**. Con viñetas "•", el texto previo a la primera es un preámbulo y la lista termina en ". SUBTÍTULO EN MAYÚSCULAS". En herramientas también separan ":", " - " y ". Mayúscula", y se quita "1 por cada N m²". | Orden A (viñetas mezcladas con áreas) y orden C (herramientas con cantidades). |
| D58 | Léxico configurable en `resources/ordenes/` (`lexico.tsv` sustantivo→categoría/unidad, `areas.txt` lista negra, `tildes.tsv`). Un fragmento sin sustantivo se fusiona con el anterior ("posible fusión"). Un paréntesis con un área se quita del nombre ("Cloro líquido (baños)"). Si un alias de una palabra aclara un nombre de varias ("servilletas (papel toalla)"), se usa el de varias. | — |
| D59 | "Una palabra en minúscula y nada más" queda en BAJA **solo si no es un sustantivo del léxico**. "atomizador" o "detergente" sueltos son legítimos en las órdenes reales. | Aplicar la regla al pie de la letra dejaba en BAJA materiales válidos de B y C. |
| D60 | Duplicados: se compara el nombre sin medidas ni conectores ("Insecticida" ≈ "Insecticida de 600 ml"). El segundo queda en MEDIA con `fusionar_con_id` apuntando al primero; ninguno se descarta. | — |
| D61 | Cláusulas de la entidad: "no incluye … como: …", "Reposición de material proporcionado por la entidad (…)" (con paréntesis anidados) y "todos los materiales … suministrados por la entidad" (tolera la errata "Contrante"). Si el producto ya está en MATERIALES, se marca ENTIDAD en lugar de duplicarlo. | — |
| D62 | Herramientas, maquinaria y equipamiento: solo se proponen ítems con un sustantivo del léxico (se omiten "Uniforme", "Credencial"…), con `es_consumible=false` y no seleccionados. | El foco son los materiales. |
| D63 | Procesamiento con `@Async` (pool de 2 hilos) disparado **después del commit**. La IA se llama fuera de cualquier transacción. Al arrancar se reprocesan las órdenes que quedaron en PROCESANDO. | — |
| D64 | Carga: PDF de 15 MB como máximo (el límite multipart es 20 MB para responder `PDF_INVALIDO` y no un 413 genérico), MIME pdf u octet-stream, *magic bytes* `%PDF-`, y se rechazan los PDF cifrados o con contraseña. Duplicado por SHA-256 en el mismo establecimiento → `PDF_DUPLICADO` (409) con `ordenId`. | — |
| D65 | Revisión: un material está "seleccionado" si fue ACEPTADO o EDITADO, o si está PENDIENTE y venía seleccionado por defecto. Editar cualquier campo lo deja EDITADO. Fusionar conserva el primero y descarta los demás. Los ítems manuales (`seccion = MANUAL`, `origen = MANUAL`, sin `texto_original`) también sacan de ERROR a una orden (`metodo = MANUAL`). Después de confirmar, la revisión queda cerrada (409) y los productos se editan en `/productos`. | — |
| D66 | Confirmar es idempotente (bloquea la orden con FOR UPDATE). Si ya existe un producto con el mismo nombre normalizado, se **enlaza** en lugar de duplicarlo. Los productos nacen con stock 0 y `origen = PDF`. | — |
| D67 | `formato_detectado`: SERCOP si el texto contiene "ORDEN DE COMPRA POR CATÁLOGO ELECTRÓNICO" o "compraspublicas.gob.ec". "Sin capa de texto" significa menos de 50 letras extraíbles. | — |

## Fase 6 — Semilla demo y entrega

| # | Decisión | Motivo |
|---|---|---|
| D68 | Perfil `demo` (por defecto en `docker-compose.yml`): 2 empresas, 1 admin y 2 encargados por empresa, y 4 establecimientos (hospital, dos oficinas y fábrica) con sus pisos. Todo ficticio. | — |
| D69 | Los 60 días (hasta ayer) se simulan en memoria con `Random(2026)` y se insertan en lote por JDBC, con saldos y secuencia cronológica. Pasar por el servicio movimiento por movimiento tardaría minutos. Una prueba verifica que cada saldo coincide con el kardex. Al final corre el job de alertas. | "Levantar en menos de 5 minutos." |
| D70 | Casos preparados: **SIN_STOCK** (alcohol en el hospital: se cortan los ingresos), **COBERTURA_BAJA** (papel higiénico en Torre Azul), **NIVEL_CRITICO** (jabón en la línea de producción, con un reporte de ayer y sin traslado posterior) y **STOCK_BAJO** (detergente en el municipio). Los dos últimos quedan fijados con un AJUSTE "Conteo físico" del admin. | Pedido de la sección 11. |
| D71 | `demo/orden-demo-sercop.pdf`: orden ficticia con formato SERCOP para la demo en vivo. La genera y valida `PdfDemoTest`. Las órdenes reales no se versionan. | Datos personales. |

## Fase 7 — Proveedores, compras, entregas y rutas

| # | Decisión | Motivo |
|---|---|---|
| D72 | Roles nuevos `PROVEEDOR` y `REPARTIDOR`, que pertenecen a un `proveedor` (no a una empresa). `usuario_pertenencia_ck` exige empresa, proveedor o ninguno (solo el SUPER_ADMIN). El JWT lleva el claim `prov`. | Un proveedor atiende a varias empresas: no puede colgar de una sola. |
| D73 | `empresa_proveedor` (N:M) con `preferido`. Al enviar una solicitud se invita a todos los proveedores vinculados y activos. | "El Proveedor 2 abastece a las dos empresas". |
| D74 | Modalidades de entrega declaradas por el proveedor: `ENVIO_DIRECTO` (a cada establecimiento), `RETIRO_LOCAL` y `RETIRO_BODEGA` (su bodega centralizada). Cada oferta elige una de las que el proveedor tiene. | Pedido del equipo: el proveedor indica si envía al local, si se retira en su local o en su bodega. |
| D75 | La solicitud consolida por nombre normalizado y unidad: el mismo producto de varios sitios es un solo ítem a cotizar; las líneas guardan el destino y el producto de cada establecimiento. | Los productos son por establecimiento (fase 3); la recepción debe entrar a la bodega correcta. |
| D76 | Cantidad recomendada = consumo diario × (entrega 3 + ciclo 14 + seguridad 3 días) − stock total, redondeada hacia arriba. Si no hay consumo reciente (producto agotado), se usa el consumo de los días con consumo de los últimos 90; sin historial, 2 × stock mínimo. Solo productos que compra la empresa (`abastece = EMPRESA_LIMPIEZA`). | RF-11. Un producto agotado deja de consumirse y el promedio de 14 días lo dejaba fuera. |
| D77 | Puntaje de ofertas por producto: entrega 40 % (plazo mínimo + 1 ÷ plazo + 1), disponibilidad 30 % (lo que cubre de lo pedido), costo 25 % (precio mínimo ÷ precio) y preferido 5 %. Empate: la más barata. El admin puede elegir otra. | Ponderaciones del deck del flujo integral, visibles para la empresa. |
| D78 | Adjudicar emite una orden por proveedor elegido. Si la oferta es parcial, se cubren los destinos en orden alfabético hasta lo disponible; lo no cubierto queda para otra solicitud. | "Solicitud consolidada ≠ una sola orden de compra". |
| D79 | Estados: envío APROBADA → ACEPTADA → EN_PREPARACION → EMPACADA → DESPACHADA ("salió de bodega") → EN_TRANSITO → ENTREGADA; retiro … → EMPACADA → LISTA_PARA_RETIRO → RETIRADA → ENTREGADA; RECHAZADA desde APROBADA. Quién puede cada transición está en `OrdenCompraService.siguientes` y se expone como `acciones` en el DTO. ENTREGADA llega sola cuando todos los destinos confirman. | Los 5 estados del documento más "salió de bodega" (rastreo tipo courier). La orden no se cierra hasta que el destino confirma. |
| D80 | `evento_orden` es inmutable (trigger, como el kardex) y guarda quién, cuándo y en qué destino. | Trazabilidad auditable. |
| D81 | La recepción la confirma el encargado asignado (o el admin), con cantidades por línea, nota y foto de la guía. Cada línea recibida genera un INGRESO a la bodega con `clientUuid = UUID(nombre "recepcion:{lineaId}")` y queda enlazada en `orden_linea.movimiento_id`. | Une la compra con el kardex: del proveedor al piso sin cortes, sin duplicar ingresos. |
| D82 | Rutas: salida desde la bodega del proveedor (o su local). Distancias y tiempos de manejo **por calles** con la matriz de OSRM (`/table`, OpenStreetMap; `CLEANTOK_OSRM_URL`, por defecto el servidor público de demos). Si no responde en 8 s, se cae a Haversine × 1,3 a 25 km/h y el DTO lo indica (`fuente = ESTIMADA`). 10 min por parada; CO₂e = km × 0,25 kg/km (furgoneta diésel, orden de magnitud de los factores DEFRA; configurable). Base = destinos en el orden del pedido (alfabético, no `parada`); optimizada = vecino más cercano + 2-opt sobre la matriz (admite calles de un sentido). Al despachar, las paradas quedan en el orden optimizado. La app dibuja el trazado con OSRM `/route` sobre tiles de OpenStreetMap (osmdroid). | RF-15/16 con un mapa real, sin API key ni pago, explicable y con plan B sin internet. En producción conviene un OSRM propio con el extracto de Ecuador: el público es solo para demos. |
| D83 | El repartidor no ve precios (los DTO los devuelven en 0); un proveedor nunca ve las ofertas de otros. | "No compartir precios confidenciales entre proveedores". |
| D85 | Las pruebas corren con `cleantok.abastecimiento.osrm-url` vacío: no dependen de internet. | Pruebas deterministas. |
| D84 | Semilla de abastecimiento en `SemillaAbastecimiento`, idempotente por el RUC del primer proveedor. Las fechas de los eventos se fijan para que el rastreo se vea real; la recepción antigua no genera INGRESO (los ingresos simulados ya la representan) y la reciente pasa por el servicio. | RNF-04: la demo no depende de cargar datos en vivo. |

## Fase 8 — Gestión de cuentas y plataforma

| # | Decisión | Motivo |
|---|---|---|
| D86 | Cada organización administra a su propia gente. El SUPER_ADMIN ("administración del sistema") da de alta empresas (con su primer admin) y proveedores (con su primer usuario de ventas), activa o desactiva proveedores y solo puede restablecer la clave de esos responsables. La empresa crea encargados u otros admins y les asigna sitios; el proveedor crea repartidores o vendedores. | Delegación como en cualquier plataforma multiempresa: el dueño del sistema no crea ni conoce las cuentas operativas. |
| D87 | Cuentas creadas o restablecidas por un admin: **clave temporal** de 10 caracteres sin ambiguos (`KX4TQ-9MHPA`), devuelta una sola vez en `claveTemporal`, y `debe_cambiar_clave = true`. `POST /auth/cambiar-clave` la reemplaza. La app no deja usar nada hasta cambiarla (el servidor informa `debeCambiarClave` en el login y en `/auth/me`). `password` sigue siendo opcional en las altas por compatibilidad. | Pedido del equipo: ningún admin conoce la clave definitiva de su gente; restablecer funciona igual. |
| D88 | Ninguna empresa se queda sin admin activo ni ningún proveedor sin usuario de ventas activo (409). Desactivar a alguien cierra sus sesiones (D5). | Evita organizaciones huérfanas. |
| D89 | Los proveedores los crea la plataforma; cada empresa elige con cuáles trabaja desde el directorio (`/proveedores/directorio`, `PUT/DELETE /proveedores/{id}/vinculo`) y marca preferidos. Desvincular no afecta órdenes ya emitidas. | Un proveedor compartido no se duplica (decisión del equipo). |
| D90 | Asignación de sitios por persona: `PUT /establecimientos/asignaciones/{usuarioId}` reemplaza la lista (la de por establecimiento sigue disponible). | La pantalla "Equipo" piensa en personas, no en sitios. |
| D91 | Empresas y proveedores se pueden desactivar desde la plataforma (`PATCH /plataforma/empresas/{id}`, `PATCH /plataforma/proveedores/{id}`). Desactivados: nadie de esa organización puede iniciar sesión ni renovarla, y se cierran sus sesiones; los datos se conservan. | Antes, desactivar un proveedor solo cerraba sesiones y su gente podía volver a entrar. |

## Fase 9 — Panel del admin, bodega y desabasto (app)

| # | Decisión | Motivo |
|---|---|---|
| D92 | Conteo físico `POST /ubicaciones/{id}/conteo`: por cada producto contado registra un AJUSTE (motivo "Conteo físico") por la diferencia con el saldo, en una sola transacción y bloqueando el saldo antes de calcularla. Lo pueden hacer el admin y el encargado asignado; el AJUSTE libre sigue siendo solo del admin. | Pedido del equipo: el PDF de la orden no trae cantidades; el encargado registra lo que hay en cada piso (y, opcional, el total en bodega) sin poder inventar movimientos. |
| D93 | En la app, la recepción de pedidos y su seguimiento quedan en el admin: el encargado ya no ve "Pedidos" y en su lugar tiene "Desabasto" (reporte de nivel de consumibles en un piso, con foto opcional, que abre la alerta NIVEL_CRITICO). El endpoint de recepción sigue aceptando ENCARGADO por compatibilidad. | Pedido del equipo: el admin gestiona y acepta la recepción. |


## Fase 10 — Stock de bodega, sedes y eliminar establecimientos

| # | Decisión | Motivo |
|---|---|---|
| D94 | El stock de la bodega solo lo cambia el admin: INGRESO y conteo de bodega responden 403 al encargado (la recepción de un pedido sigue registrando su INGRESO, acotado por la orden). En el conteo de un piso, lo que sube es un TRASLADO desde la bodega (falla con STOCK_INSUFICIENTE si no alcanza y no se aplica nada) y lo que baja es un CONSUMO. Reemplaza el AJUSTE por diferencia de D92 en los pisos. | Pedido del usuario: un piso sumaba material aunque la bodega estuviera vacía. |
| D95 | Eliminar establecimiento `DELETE /establecimientos/{id}`: solo el admin que lo creó (`creado_por`) o el SUPER_ADMIN; otro admin de la empresa recibe 403. Es lógico (`eliminado_en`): sale de listas y acceso, sus QR dejan de funcionar y se quitan sus encargados, pero pedidos y movimientos conservan su nombre. Con entregas sin recibir responde 409. Los existentes se atribuyen al primer admin de su empresa. El SUPER_ADMIN los ve en `GET /plataforma/empresas/{id}/establecimientos`. | Pedido del usuario; la trazabilidad con proveedores no se pierde. |
| D96 | App del admin: pestaña "Sedes" en la barra inferior con sus establecimientos (antes al final del Panel). El mapa del establecimiento es fijo. "Bodega" pasa a "Materiales"; en Gestión: Personal, Proveedores, Establecimientos. | Pedido del usuario. |

## Fase 11 — Fecha de compra, pago demo y despliegue en AWS

| # | Decisión | Motivo |
|---|---|---|
| D97 | Antes de enviar a cotizar, la app pregunta "¿Para cuándo lo necesitas?" y manda `fechaRequerida`. Recomendada = un día antes de que se agote el producto más urgente (stock ÷ consumo, mínimo mañana); además urgente (mañana), plazo habitual (3 días) y una semana, o cualquier fecha del calendario. Avisa si la fecha elegida llega después de agotarse. Se calcula en la app con `diasCobertura`: el backend no cambia. | Pedido del usuario: el admin elige la fecha de compra con recomendaciones. |
| D98 | "Aprobar y pagar": antes de adjudicar se abre un pago **de demostración** (tarjeta con validación Luhn/vencimiento/CVV y tarjetas de prueba aprobada/rechazada, o transferencia con datos bancarios de ejemplo por proveedor y número de comprobante). No hay pasarela ni se envía nada; el comprobante se guarda solo en el teléfono y se muestra en la solicitud aprobada. | Pedido del usuario: mostrar el paso de pago sin integrar una API. |
| D99 | Producción en un VPS con `docker-compose.prod.yml`: Caddy (HTTPS automático) delante de la API; Postgres y la API sin puertos expuestos; dominio gratis `<ip>.sslip.io`; `.env` con claves aleatorias generado por `deploy/instalar-vps.sh`. El APK lleva el servidor por defecto (`-Pcleantok.servidor`) y se firma con una llave del equipo para poder actualizarlo. Guía en `DESPLIEGUE-AWS.md`. | Pedido del usuario: subir el backend a AWS y compartir el APK con el equipo. |
