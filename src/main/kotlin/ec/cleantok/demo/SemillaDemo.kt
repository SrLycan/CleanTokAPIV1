package ec.cleantok.demo

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.OrigenProducto
import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.Zona
import ec.cleantok.establecimiento.dominio.Establecimiento
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.establecimiento.dominio.TipoEstablecimiento
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.Ubicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.dominio.UsuarioEstablecimiento
import ec.cleantok.establecimiento.dominio.UsuarioEstablecimientoRepository
import ec.cleantok.establecimiento.servicio.GeneradorTokenQr
import ec.cleantok.identidad.dominio.Empresa
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.Usuario
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.inventario.dominio.TipoMovimiento
import ec.cleantok.monitoreo.servicio.JobAlertas
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.random.Random

/**
 * Datos ficticios para la demo (perfil `demo`). Idempotente: si ya existen, no hace nada.
 *
 * Simula 60 días (hasta ayer) con un generador de semilla fija: ingresos semanales a bodega, traslados a los
 * pisos cuando se están acabando, consumos diarios con ±20 % de variación (menos los fines de semana en
 * oficinas) y reportes de nivel ocasionales. Los movimientos se insertan en lote con sus saldos (coherentes
 * con las mismas reglas del servicio) y al final se evalúan las alertas. Tres casos quedan preparados:
 * SIN_STOCK (hospital), COBERTURA_BAJA (Torre Azul) y NIVEL_CRITICO (planta textil).
 * Después agrega, si faltan, los establecimientos de [EXTRA_BRILLO] (así una base ya sembrada también los recibe),
 * el contacto de cada empresa y un supervisor de establecimiento por empresa ([SUPERVISORES]), y la semilla de
 * abastecimiento (proveedores, solicitudes y órdenes).
 */
@Component
@Profile("demo")
class SemillaDemo(
    private val empresas: EmpresaRepository,
    private val usuarios: UsuarioRepository,
    private val establecimientos: EstablecimientoRepository,
    private val ubicaciones: UbicacionRepository,
    private val asignaciones: UsuarioEstablecimientoRepository,
    private val productos: ProductoRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokensQr: GeneradorTokenQr,
    private val jdbc: JdbcTemplate,
    private val job: JobAlertas,
    private val abastecimiento: SemillaAbastecimiento,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    enum class Politica { NORMAL, AGOTAR, COBERTURA_BAJA, NIVEL_CRITICO, STOCK_BAJO }

    data class ProdDemo(
        val nombre: String,
        val categoria: Categoria,
        val unidad: Unidad,
        val presentacion: String?,
        val abastece: Abastece,
        /** Consumo diario por piso en un día laborable. */
        val tasa: Double,
        val politica: Politica = Politica.NORMAL,
        val stockMinimo: BigDecimal? = null,
    )

    data class EstDemo(
        val nombre: String,
        val tipo: TipoEstablecimiento,
        val direccion: String,
        val lat: String,
        val lng: String,
        val area: Int,
        val pisos: List<String>,
        val factor: Double,
        val finDeSemana: Double,
        val especiales: Map<String, Politica> = emptyMap(),
        val minimos: Map<String, BigDecimal> = emptyMap(),
    )

    override fun run(args: ApplicationArguments) {
        val nueva = !empresas.existsByRuc(RUC_BRILLO)
        if (nueva) tx.executeWithoutResult { cargar() }
        val agregados = tx.execute { complementar() }!!
        tx.executeWithoutResult { complementarCuentas() }
        if (nueva || agregados > 0) {
            val abiertas = job.ejecutar()
            log.info("Semilla demo cargada: {} establecimientos nuevos, {} alertas abiertas", agregados, abiertas)
        } else {
            log.info("Semilla demo ya cargada")
        }
        abastecimiento.cargar()
    }

    /** Establecimientos adicionales de Brillo Total en Quito, para que el reparto tenga varias paradas. */
    private fun complementar(): Int {
        val brillo = empresas.findByRuc(RUC_BRILLO)!!
        val admin = usuarios.buscarPorEmail("admin@brillototal.ec")!!
        // Incluye los desactivados y eliminados: lo que el admin quitó no vuelve a aparecer al reiniciar.
        val existentes = jdbc.queryForList("select nombre from establecimiento where empresa_id = ?", String::class.java, brillo.id).toSet()
        val faltan = EXTRA_BRILLO.filter { (d, _) -> d.nombre !in existentes }
        faltan.forEachIndexed { i, (d, emails) ->
            sembrar(brillo, d, admin, emails.map { usuarios.buscarPorEmail(it)!! }, Random(2026 + 10 + i))
        }
        return faltan.size
    }

    /** Contacto de las empresas demo (lo ve el proveedor) y un supervisor de establecimiento por empresa. */
    private fun complementarCuentas() {
        CONTACTOS.forEach { c ->
            val e = empresas.findByRuc(c.ruc) ?: return@forEach
            if (e.direccion != null) return@forEach
            e.telefono = c.telefono
            e.email = c.email
            e.direccion = c.direccion
            e.lat = BigDecimal(c.lat)
            e.lng = BigDecimal(c.lng)
        }
        val clave by lazy { passwordEncoder.encode(CLAVE)!! }
        SUPERVISORES.forEach { s ->
            if (usuarios.existeEmail(s.email)) return@forEach
            val e = empresas.findByRuc(s.ruc) ?: return@forEach
            val sede = jdbc.queryForList(
                "select id from establecimiento where empresa_id = ? and nombre = ? and eliminado_en is null", UUID::class.java, e.id, s.sede,
            ).firstOrNull() ?: return@forEach
            val u = usuarios.save(Usuario(e.id, s.nombre, s.email, clave, Rol.SUPERVISOR))
            asignaciones.save(UsuarioEstablecimiento(e.id, u.id, sede))
        }
    }

    private fun cargar() {
        val clave = passwordEncoder.encode(CLAVE)!!
        val rnd = Random(2026)

        val brillo = empresas.save(Empresa("Brillo Total S.A.", RUC_BRILLO))
        val andina = empresas.save(Empresa("Limpieza Andina Cía. Ltda.", RUC_ANDINA))
        fun usuario(e: Empresa, rol: Rol, nombre: String, email: String) = usuarios.save(Usuario(e.id, nombre, email, clave, rol))
        val adminBrillo = usuario(brillo, Rol.ADMIN_EMPRESA, "Carla Andrade (admin)", "admin@brillototal.ec")
        val encB1 = usuario(brillo, Rol.ENCARGADO, "Luis Pérez", "luis@brillototal.ec")
        val encB2 = usuario(brillo, Rol.ENCARGADO, "María Quishpe", "maria@brillototal.ec")
        val adminAndina = usuario(andina, Rol.ADMIN_EMPRESA, "Jorge Salazar (admin)", "admin@andina.ec")
        val encA1 = usuario(andina, Rol.ENCARGADO, "Ana Torres", "ana@andina.ec")
        val encA2 = usuario(andina, Rol.ENCARGADO, "Pedro Chalá", "pedro@andina.ec")

        val hospital = EstDemo(
            "Hospital Básico San Andrés", TipoEstablecimiento.HOSPITAL, "Av. Principal y Calle 3, Quito", "-0.180653", "-78.467834", 6900,
            listOf("Emergencia", "Hospitalización – Piso 2", "Consulta externa", "Laboratorio"), factor = 1.5, finDeSemana = 0.9,
            especiales = mapOf("Alcohol antiséptico" to Politica.AGOTAR),
        )
        val torre = EstDemo(
            "Edificio Torre Azul – Oficinas", TipoEstablecimiento.OFICINA, "Av. Amazonas N24-10, Quito", "-0.200310", "-78.493790", 2100,
            listOf("Planta baja – recepción", "Piso 3", "Piso 4"), factor = 1.0, finDeSemana = 0.15,
            especiales = mapOf("Papel higiénico jumbo" to Politica.COBERTURA_BAJA),
        )
        val municipio = EstDemo(
            "Oficinas Municipales Centro", TipoEstablecimiento.OFICINA, "Calle Bolívar 210, Cuenca", "-2.897400", "-79.004500", 1500,
            listOf("Piso 1 – atención al público", "Piso 2"), factor = 0.9, finDeSemana = 0.1,
            especiales = mapOf("Detergente" to Politica.STOCK_BAJO), minimos = mapOf("Detergente" to BigDecimal("8")),
        )
        val planta = EstDemo(
            "Planta Textil La Esperanza", TipoEstablecimiento.FABRICA, "Parque Industrial, km 5 vía a Daule, Guayaquil", "-2.104500", "-79.931200", 8800,
            listOf("Línea de producción", "Comedor", "Baños y vestidores"), factor = 1.2, finDeSemana = 0.6,
            especiales = mapOf("Jabón líquido para dispensador" to Politica.NIVEL_CRITICO),
        )

        sembrar(brillo, hospital, adminBrillo, listOf(encB1, encB2), rnd)
        sembrar(brillo, torre, adminBrillo, listOf(encB2), rnd)
        sembrar(andina, municipio, adminAndina, listOf(encA1), rnd)
        sembrar(andina, planta, adminAndina, listOf(encA2), rnd)
    }

    private fun sembrar(empresa: Empresa, d: EstDemo, admin: Usuario, encargados: List<Usuario>, rnd: Random) {
        val e = establecimientos.save(
            Establecimiento(
                empresa.id, d.nombre, d.tipo, "Entidad Contratante Demo", d.direccion,
                BigDecimal(d.lat), BigDecimal(d.lng), BigDecimal(d.area), creadoPor = admin.id,
            ),
        )
        val bodega = ubicaciones.save(Ubicacion(empresa.id, e.id, "Bodega", TipoUbicacion.BODEGA, 0, tokensQr.nuevo()))
        val pisos = d.pisos.mapIndexed { i, n -> ubicaciones.save(Ubicacion(empresa.id, e.id, n, TipoUbicacion.PISO, i + 1, tokensQr.nuevo())) }
        encargados.forEach { asignaciones.save(UsuarioEstablecimiento(empresa.id, it.id, e.id)) }

        val catalogo = CATALOGO.map { p ->
            val pol = d.especiales[p.nombre] ?: Politica.NORMAL
            p.copy(politica = pol, stockMinimo = d.minimos[p.nombre])
        }
        val prods = catalogo.associateWith { p ->
            productos.save(
                Producto(
                    empresaId = empresa.id, establecimientoId = e.id, nombre = p.nombre, categoria = p.categoria,
                    esConsumible = true, unidad = p.unidad, presentacion = p.presentacion, abastece = p.abastece,
                    stockMinimo = p.stockMinimo, origen = OrigenProducto.MANUAL,
                ),
            )
        }
        // Utensilio no consumible: solo un ingreso inicial.
        val balde = productos.save(
            Producto(empresa.id, e.id, "Balde de 12 lt.", Categoria.UTENSILIO, false, Unidad.UNIDAD, "12 lt.", Abastece.EMPRESA_LIMPIEZA),
        )
        Simulacion(e, bodega, pisos, admin, encargados, d, rnd).apply {
            ingresoUnico(balde, BigDecimal(pisos.size * 2L))
            prods.forEach { (spec, p) -> simular(spec, p) }
            guardar()
        }
    }

    /** Simulación de un establecimiento: genera movimientos y reportes con saldos, y los inserta en lote. */
    private inner class Simulacion(
        val e: Establecimiento,
        val bodega: Ubicacion,
        val pisos: List<Ubicacion>,
        val admin: Usuario,
        val encargados: List<Usuario>,
        val d: EstDemo,
        val rnd: Random,
    ) {
        private val hoy = LocalDate.now(clock.withZone(Zona.ECUADOR))
        private val inicio = hoy.minusDays(DIAS.toLong())
        private val movimientos = mutableListOf<Array<Any?>>()
        private val reportes = mutableListOf<Array<Any?>>()
        private val stock = mutableMapOf<Pair<UUID, UUID>, BigDecimal>()

        private fun instante(dia: LocalDate, hora: Int, minuto: Int = 0): Instant = dia.atTime(LocalTime.of(hora, minuto)).atZone(Zona.ECUADOR).toInstant()
        private fun uuid() = UUID(rnd.nextLong(), rnd.nextLong())
        private fun saldo(p: Producto, u: Ubicacion) = stock[p.id to u.id] ?: BigDecimal.ZERO
        private fun encargado() = encargados[rnd.nextInt(encargados.size)]

        /** Unidades discretas en medios (0,5 rollo); redondeo estocástico para no sesgar el consumo medio. */
        private fun redondear(p: Producto, x: Double): BigDecimal = when (p.unidad) {
            Unidad.ROLLO, Unidad.UNIDAD, Unidad.PAQUETE, Unidad.PAR -> {
                val medios = x * 2
                val base = kotlin.math.floor(medios)
                BigDecimal((base + if (rnd.nextDouble() < medios - base) 1 else 0) / 2.0).setScale(3)
            }
            else -> BigDecimal(x).setScale(2, RoundingMode.HALF_UP).setScale(3)
        }

        fun mover(tipo: TipoMovimiento, p: Producto, origen: Ubicacion?, destino: Ubicacion?, cantidad: BigDecimal, cuando: Instant, quien: Usuario, motivo: String? = null) {
            if (cantidad.signum() <= 0) return
            val so = origen?.let { saldo(p, it).subtract(cantidad).also { s -> check(s.signum() >= 0); stock[p.id to it.id] = s } }
            val sd = destino?.let { saldo(p, it).add(cantidad).also { s -> stock[p.id to it.id] = s } }
            movimientos += arrayOf(
                UUID.randomUUID(), e.empresaId, e.id, uuid(), tipo.name, p.id, origen?.id, destino?.id, cantidad, p.unidad.name,
                cantidad, p.unidad.name, quien.id, Timestamp.from(cuando), Timestamp.from(cuando.plusSeconds(90L + rnd.nextInt(600))),
                null, motivo, so, sd,
            )
        }

        fun ingresoUnico(p: Producto, cantidad: BigDecimal) = mover(TipoMovimiento.INGRESO, p, null, bodega, cantidad, instante(inicio, 7, 30), encargado())

        fun simular(spec: ProdDemo, p: Producto) {
            val diarioPiso = spec.tasa * d.factor
            val semanal = diarioPiso * pisos.size * (5 + 2 * d.finDeSemana)
            val ultimoTraslado = mutableMapOf<UUID, BigDecimal>()
            val consumoPorDia = mutableMapOf<LocalDate, Double>()

            for (n in 0 until DIAS) {
                val dia = inicio.plusDays(n.toLong())
                val ultimoDia = n == DIAS - 1
                val esFinDeSemana = dia.dayOfWeek == DayOfWeek.SATURDAY || dia.dayOfWeek == DayOfWeek.SUNDAY

                // Ingresos: 3 semanas de stock al inicio y uno semanal cada lunes.
                val ingresa = when (spec.politica) {
                    Politica.AGOTAR -> n < DIAS - 35
                    else -> true
                }
                if (ingresa && (n == 0 || dia.dayOfWeek == DayOfWeek.MONDAY)) {
                    val cant = if (n == 0) semanal * 3 else semanal * (1.05 + rnd.nextDouble() * 0.15)
                    mover(TipoMovimiento.INGRESO, p, null, bodega, redondear(p, cant), instante(dia, 7, 30), encargado())
                }

                pisos.forEachIndexed { i, piso ->
                    val critico = spec.politica == Politica.NIVEL_CRITICO && i == 0
                    // Traslado cuando al piso le quedan menos de 2 días (en el caso crítico, no se repone los últimos 3 días).
                    // En el caso de cobertura baja se repone poco y seguido, para que los pisos no acumulen.
                    val sinReponer = critico && n >= DIAS - 3
                    val (umbral, lote) = if (spec.politica == Politica.COBERTURA_BAJA) 1.0 to 2.0 else 2.0 to 5.0
                    if (!sinReponer && saldo(p, piso).toDouble() < diarioPiso * umbral) {
                        val cant = redondear(p, diarioPiso * lote).min(saldo(p, bodega))
                        if (cant.signum() > 0) {
                            // Hora creciente por piso: el orden cronológico coincide con el orden de los saldos de bodega.
                            mover(TipoMovimiento.TRASLADO, p, bodega, piso, cant, instante(dia, 8, i * 10 + rnd.nextInt(10)), encargado())
                            ultimoTraslado[piso.id] = cant
                        }
                    }
                    // Consumo del día con ±20 % de variación.
                    val demanda = diarioPiso * (if (esFinDeSemana) d.finDeSemana else 1.0) * (0.8 + rnd.nextDouble() * 0.4)
                    val consumo = redondear(p, demanda).min(saldo(p, piso))
                    if (consumo.signum() > 0) {
                        mover(TipoMovimiento.CONSUMO, p, piso, null, consumo, instante(dia, 10 + rnd.nextInt(6), rnd.nextInt(60)), encargado())
                        consumoPorDia.merge(dia, consumo.toDouble(), Double::plus)
                    }
                    // Reporte de nivel cuando el piso queda en 25 % o menos del último traslado.
                    val base = ultimoTraslado[piso.id]
                    val bajo = base != null && saldo(p, piso) <= base.multiply(BigDecimal("0.25"))
                    if ((bajo && n < DIAS - 2 && rnd.nextDouble() < 0.3) || (critico && ultimoDia)) {
                        val nivel = if (saldo(p, piso).signum() == 0) 0 else 25
                        reportes += arrayOf(
                            UUID.randomUUID(), e.empresaId, e.id, uuid(), p.id, piso.id, nivel, encargado().id,
                            Timestamp.from(instante(dia, 17)), Timestamp.from(instante(dia, 17, 5)),
                            if (critico && ultimoDia) "El dispensador está casi vacío" else null,
                        )
                    }
                }
            }

            // Ajustes por conteo físico para dejar preparados los casos de la demo.
            val consumoDiario = consumoPorDia.filterKeys { it.isAfter(hoy.minusDays(14)) }.values.sum() / 14
            val enPisos = pisos.fold(BigDecimal.ZERO) { acc, u -> acc.add(saldo(p, u)) }
            val objetivoBodega: BigDecimal? = when (spec.politica) {
                // ~4,5 días de cobertura total, con algo de stock en bodega.
                Politica.COBERTURA_BAJA -> redondear(p, maxOf(consumoDiario * 4.5 - enPisos.toDouble(), consumoDiario * 0.5))
                Politica.STOCK_BAJO -> spec.stockMinimo?.multiply(BigDecimal("0.6"))?.let { redondear(p, it.toDouble()) }
                else -> null
            }
            if (objetivoBodega != null) {
                val diferencia = saldo(p, bodega).subtract(objetivoBodega)
                val cuando = instante(hoy.minusDays(1), 18)
                if (diferencia.signum() > 0) {
                    mover(TipoMovimiento.AJUSTE, p, bodega, null, diferencia, cuando, admin, "Conteo físico: diferencia con el sistema")
                } else if (diferencia.signum() < 0) {
                    mover(TipoMovimiento.INGRESO, p, null, bodega, diferencia.negate(), cuando, encargado())
                }
            }
        }

        fun guardar() {
            // Lo creado con JPA debe estar en la base antes del lote JDBC que lo referencia.
            productos.flush()
            // En orden cronológico: la secuencia (bigserial) refleja el orden de aplicación de los saldos.
            movimientos.sortBy { (it[13] as Timestamp).time }
            jdbc.batchUpdate(
                """
                insert into movimiento (id, empresa_id, establecimiento_id, client_uuid, tipo, producto_id, ubicacion_origen_id,
                    ubicacion_destino_id, cantidad, unidad, cantidad_original, unidad_original, usuario_id, ocurrido_en, registrado_en,
                    nota, motivo, saldo_resultante_origen, saldo_resultante_destino)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                movimientos,
            )
            jdbc.batchUpdate(
                """
                insert into reporte_nivel (id, empresa_id, establecimiento_id, client_uuid, producto_id, ubicacion_id, nivel_pct,
                    usuario_id, ocurrido_en, registrado_en, nota)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                reportes,
            )
            jdbc.batchUpdate(
                "insert into stock (id, empresa_id, establecimiento_id, producto_id, ubicacion_id, cantidad) values (?, ?, ?, ?, ?, ?)",
                stock.map { (k, v) -> arrayOf<Any?>(UUID.randomUUID(), e.empresaId, e.id, k.first, k.second, v) },
            )
        }
    }

    companion object {
        const val CLAVE = "Demo2026!"
        const val RUC_BRILLO = "0992000000001"
        const val RUC_ANDINA = "0992000000002"

        data class ContactoDemo(val ruc: String, val telefono: String, val email: String, val direccion: String, val lat: String, val lng: String)

        val CONTACTOS = listOf(
            ContactoDemo(RUC_BRILLO, "02 245 6789", "compras@brillototal.ec", "Av. 6 de Diciembre N33-42 y Bosmediano, Quito", "-0.180120", "-78.477210"),
            ContactoDemo(RUC_ANDINA, "07 284 1122", "compras@andina.ec", "Av. Remigio Crespo 3-45, Cuenca", "-2.905700", "-79.012300"),
        )

        data class SupervisorDemo(val ruc: String, val nombre: String, val email: String, val sede: String)

        /** Supervisores de establecimiento: cada uno ve y controla solo su sede. */
        val SUPERVISORES = listOf(
            SupervisorDemo(RUC_BRILLO, "Diana Cevallos (supervisora)", "supervisor@brillototal.ec", "Hospital Básico San Andrés"),
            SupervisorDemo(RUC_ANDINA, "Marco Vélez (supervisor)", "supervisor@andina.ec", "Planta Textil La Esperanza"),
        )
        private const val DIAS = 60

        val EXTRA_BRILLO = listOf(
            EstDemo(
                "Clínica Los Valles", TipoEstablecimiento.HOSPITAL, "Av. Interoceánica km 12, Cumbayá", "-0.198500", "-78.435700", 4200,
                listOf("Emergencia", "Hospitalización", "Quirófanos"), factor = 1.2, finDeSemana = 0.85,
                especiales = mapOf("Desinfectante aromaterapia" to Politica.COBERTURA_BAJA),
            ) to listOf("luis@brillototal.ec"),
            EstDemo(
                "Edificio Plaza Norte – Oficinas", TipoEstablecimiento.OFICINA, "Av. Naciones Unidas y Shyris, Quito", "-0.175900", "-78.479200", 3100,
                listOf("Planta baja", "Piso 5", "Piso 8"), factor = 1.0, finDeSemana = 0.15,
                especiales = mapOf("Cloro líquido 5.5%" to Politica.COBERTURA_BAJA),
            ) to listOf("maria@brillototal.ec"),
            EstDemo(
                "Colegio Bilingüe Los Andes", TipoEstablecimiento.EDUCATIVO, "Av. Mariscal Sucre y Mariana de Jesús, Quito", "-0.172000", "-78.512500", 5200,
                listOf("Bloque A – aulas", "Bloque B – laboratorios", "Coliseo"), factor = 1.1, finDeSemana = 0.05,
                especiales = mapOf("Fundas de basura negras 23x28 medianas" to Politica.COBERTURA_BAJA),
            ) to listOf("luis@brillototal.ec"),
        )

        /** Productos tomados de las listas de materiales de las órdenes de ejemplo (nombres genéricos). */
        val CATALOGO = listOf(
            ProdDemo("Cloro líquido 5.5%", Categoria.QUIMICO, Unidad.L, "Galón 3.785 L", Abastece.EMPRESA_LIMPIEZA, 0.8),
            ProdDemo("Desinfectante aromaterapia", Categoria.QUIMICO, Unidad.L, "Galón", Abastece.EMPRESA_LIMPIEZA, 0.5),
            ProdDemo("Detergente", Categoria.QUIMICO, Unidad.KG, "Funda 2 kg", Abastece.EMPRESA_LIMPIEZA, 0.4),
            ProdDemo("Alcohol antiséptico", Categoria.QUIMICO, Unidad.L, "1 L", Abastece.EMPRESA_LIMPIEZA, 0.3),
            ProdDemo("Limpiavidrios", Categoria.QUIMICO, Unidad.L, "Atomizador 1 L", Abastece.EMPRESA_LIMPIEZA, 0.2),
            ProdDemo("Jabón líquido para dispensador", Categoria.QUIMICO, Unidad.L, "Galón", Abastece.ENTIDAD_CONTRATANTE, 0.6),
            ProdDemo("Papel higiénico jumbo", Categoria.PAPEL_Y_HIGIENE, Unidad.ROLLO, "250 mtrs doble hoja", Abastece.ENTIDAD_CONTRATANTE, 1.5),
            ProdDemo("Papel toalla de mano", Categoria.PAPEL_Y_HIGIENE, Unidad.ROLLO, null, Abastece.ENTIDAD_CONTRATANTE, 1.0),
            ProdDemo("Fundas de basura negras 23x28 medianas", Categoria.BOLSAS, Unidad.PAQUETE, "23x28", Abastece.EMPRESA_LIMPIEZA, 0.3),
            ProdDemo("Guantes de nitrilo", Categoria.EPP, Unidad.PAR, null, Abastece.EMPRESA_LIMPIEZA, 1.0),
        )
    }
}
