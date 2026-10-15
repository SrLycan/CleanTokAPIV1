package ec.cleantok.demo

import ec.cleantok.abastecimiento.dominio.EmpresaProveedor
import ec.cleantok.abastecimiento.dominio.EmpresaProveedorRepository
import ec.cleantok.abastecimiento.dominio.Entrega
import ec.cleantok.abastecimiento.dominio.EntregaRepository
import ec.cleantok.abastecimiento.dominio.EstadoEntrega
import ec.cleantok.abastecimiento.dominio.EstadoOferta
import ec.cleantok.abastecimiento.dominio.EstadoOrden
import ec.cleantok.abastecimiento.dominio.EstadoSolicitud
import ec.cleantok.abastecimiento.dominio.EventoOrden
import ec.cleantok.abastecimiento.dominio.EventoOrdenRepository
import ec.cleantok.abastecimiento.dominio.Modalidad
import ec.cleantok.abastecimiento.dominio.Oferta
import ec.cleantok.abastecimiento.dominio.OfertaItem
import ec.cleantok.abastecimiento.dominio.OfertaItemRepository
import ec.cleantok.abastecimiento.dominio.OfertaRepository
import ec.cleantok.abastecimiento.dominio.OrdenCompra
import ec.cleantok.abastecimiento.dominio.OrdenCompraRepository
import ec.cleantok.abastecimiento.dominio.OrdenLinea
import ec.cleantok.abastecimiento.dominio.OrdenLineaRepository
import ec.cleantok.abastecimiento.dominio.Solicitud
import ec.cleantok.abastecimiento.dominio.SolicitudItem
import ec.cleantok.abastecimiento.dominio.SolicitudItemRepository
import ec.cleantok.abastecimiento.dominio.SolicitudLinea
import ec.cleantok.abastecimiento.dominio.SolicitudLineaRepository
import ec.cleantok.abastecimiento.dominio.SolicitudProveedor
import ec.cleantok.abastecimiento.dominio.SolicitudProveedorRepository
import ec.cleantok.abastecimiento.dominio.SolicitudRepository
import ec.cleantok.abastecimiento.servicio.OrdenCompraService
import ec.cleantok.abastecimiento.servicio.RutaService
import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Zona
import ec.cleantok.comun.seguridad.AutenticacionJwt
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.identidad.dominio.Empresa
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.Proveedor
import ec.cleantok.identidad.dominio.ProveedorRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.Usuario
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.inventario.api.MovimientoRequest
import ec.cleantok.inventario.dominio.TipoMovimiento
import ec.cleantok.inventario.servicio.MovimientoService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Cadena de abastecimiento de la demo: 4 proveedores (uno compartido por las dos empresas), sus usuarios y repartidores,
 * y pedidos en distintos momentos para mostrar cada rol sin cargar datos en vivo:
 * - Brillo SC-001 → OC-001 entregada (rastreo completo, hace 12 días).
 * - Brillo SC-002 → OC-002 en tránsito con 4 destinos (uno recibido, uno entregado por el repartidor, dos pendientes)
 *   y OC-003 recién aprobada, esperando que el proveedor la acepte. Una solicitud puede terminar en varias órdenes.
 * - Brillo SC-003 en cotización con tres ofertas para comparar y adjudicar.
 * - Andina SC-001 → OC-001 lista para retiro en el local del proveedor.
 * Las fechas de los eventos se fijan aquí; la recepción reciente pasa por el servicio de movimientos para que
 * el INGRESO quede en el kardex enlazado a la orden.
 */
@Component
@Profile("demo")
class SemillaAbastecimiento(
    private val empresas: EmpresaRepository,
    private val proveedores: ProveedorRepository,
    private val usuarios: UsuarioRepository,
    private val vinculos: EmpresaProveedorRepository,
    private val establecimientos: EstablecimientoRepository,
    private val ubicaciones: UbicacionRepository,
    private val productos: ProductoRepository,
    private val solicitudes: SolicitudRepository,
    private val items: SolicitudItemRepository,
    private val lineas: SolicitudLineaRepository,
    private val invitaciones: SolicitudProveedorRepository,
    private val ofertas: OfertaRepository,
    private val ofertaItems: OfertaItemRepository,
    private val ordenes: OrdenCompraRepository,
    private val entregas: EntregaRepository,
    private val ordenLineas: OrdenLineaRepository,
    private val eventos: EventoOrdenRepository,
    private val movimientos: MovimientoService,
    private val rutas: RutaService,
    private val passwordEncoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    fun cargar() {
        if (proveedores.existsByRuc(RUC_QUIMICOS)) return
        tx.executeWithoutResult { sembrar() }
        log.info("Semilla de abastecimiento cargada: 4 proveedores, 3 repartidores y 4 órdenes de compra")
    }

    private fun sembrar() {
        val clave = passwordEncoder.encode(SemillaDemo.CLAVE)!!
        val brillo = empresas.findByRuc(SemillaDemo.RUC_BRILLO)!!
        val andina = empresas.findByRuc(SemillaDemo.RUC_ANDINA)!!

        val quimicos = proveedores.save(
            Proveedor(
                "Químicos del Pichincha S.A.", RUC_QUIMICOS, "02 265 4410", "Av. Maldonado S12-45, Quito", BigDecimal("-0.263500"), BigDecimal("-78.528500"),
                bodegaDireccion = "Parque Industrial Turubamba, galpón 7, Quito", bodegaLat = BigDecimal("-0.301000"), bodegaLng = BigDecimal("-78.541000"),
                envioDirecto = true, retiroLocal = true, retiroBodega = true,
            ),
        )
        val higiene = proveedores.save(
            Proveedor(
                "Distribuidora La Higiene Cía. Ltda.", "1790000000002", "02 245 9900", "Av. 6 de Diciembre y El Inca, Quito",
                BigDecimal("-0.153000"), BigDecimal("-78.472000"), envioDirecto = true, retiroLocal = true,
            ),
        )
        val proclean = proveedores.save(
            Proveedor(
                "ProClean Ecuador S.A.", "0990000000003", "04 268 1200", "Av. Juan Tanca Marengo km 3, Guayaquil", BigDecimal("-2.150000"), BigDecimal("-79.900000"),
                bodegaDireccion = "Centro de distribución Carapungo, Quito", bodegaLat = BigDecimal("-0.095000"), bodegaLng = BigDecimal("-78.450000"),
                envioDirecto = true, retiroBodega = true,
            ),
        )
        val papelera = proveedores.save(
            Proveedor(
                "Papelera Andina del Austro", "0190000000004", "07 282 3300", "Av. España 14-20, Cuenca", BigDecimal("-2.889000"), BigDecimal("-78.985000"),
                envioDirecto = true, retiroLocal = true,
            ),
        )
        fun usuario(p: Proveedor, rol: Rol, nombre: String, email: String) =
            usuarios.save(Usuario(null, nombre, email, clave, rol, proveedorId = p.id))
        val ventasQuimicos = usuario(quimicos, Rol.PROVEEDOR, "Rosa Guamán (ventas)", "ventas@quimicospichincha.ec")
        val ventasHigiene = usuario(higiene, Rol.PROVEEDOR, "Esteban Ruiz (ventas)", "ventas@lahigiene.ec")
        val ventasProclean = usuario(proclean, Rol.PROVEEDOR, "Mónica Paredes (ventas)", "ventas@proclean.ec")
        val ventasPapelera = usuario(papelera, Rol.PROVEEDOR, "Fernando Ochoa (ventas)", "ventas@papeleraandina.ec")
        val kevin = usuario(quimicos, Rol.REPARTIDOR, "Kevin Tapia", "reparto@quimicospichincha.ec")
        val diego = usuario(higiene, Rol.REPARTIDOR, "Diego Morales", "reparto@lahigiene.ec")
        usuario(proclean, Rol.REPARTIDOR, "Andrés Vera", "reparto@proclean.ec")

        vinculos.save(EmpresaProveedor(brillo.id, quimicos.id, preferido = true))
        vinculos.save(EmpresaProveedor(brillo.id, higiene.id))
        vinculos.save(EmpresaProveedor(brillo.id, proclean.id))
        vinculos.save(EmpresaProveedor(andina.id, higiene.id, preferido = true))
        vinculos.save(EmpresaProveedor(andina.id, proclean.id))
        vinculos.save(EmpresaProveedor(andina.id, papelera.id))

        val adminB = usuarios.buscarPorEmail("admin@brillototal.ec")!!
        val luis = usuarios.buscarPorEmail("luis@brillototal.ec")!!
        val maria = usuarios.buscarPorEmail("maria@brillototal.ec")!!
        val adminA = usuarios.buscarPorEmail("admin@andina.ec")!!

        val hospital = "Hospital Básico San Andrés"
        val torre = "Edificio Torre Azul – Oficinas"
        val clinica = "Clínica Los Valles"
        val plaza = "Edificio Plaza Norte – Oficinas"
        val colegio = "Colegio Bilingüe Los Andes"
        val municipio = "Oficinas Municipales Centro"

        // ---- Brillo SC-001 → OC-001: entregada hace 10 días (rastreo completo) ----
        val sc1 = solicitud(
            brillo, "SC-001", adminB, dia(14, 9), EstadoSolicitud.ADJUDICADA,
            listOf(Triple(hospital, "Cloro líquido 5.5%", "30"), Triple(torre, "Cloro líquido 5.5%", "20"), Triple(hospital, "Detergente", "10"), Triple(torre, "Detergente", "6")),
            invitados = listOf(quimicos, higiene, proclean), enviada = dia(14, 9, 30), adjudicada = dia(13, 16),
        )
        val of1 = oferta(sc1, quimicos, ventasQuimicos, Modalidad.ENVIO_DIRECTO, 2, dia(14, 15), mapOf("Cloro líquido 5.5%" to ("50" to "1.85"), "Detergente" to ("16" to "2.40")), EstadoOferta.ADJUDICADA)
        oferta(sc1, higiene, ventasHigiene, Modalidad.ENVIO_DIRECTO, 3, dia(14, 17), mapOf("Cloro líquido 5.5%" to ("40" to "1.70")), EstadoOferta.NO_ADJUDICADA)
        val oc1 = orden(sc1, of1, "OC-001", adminB, dia(13, 16), null)
        hitos(
            oc1, listOf(
                Hito(EstadoOrden.APROBADA, adminB, dia(13, 16), "2 destino(s) · total USD ${oc1.total.toPlainString()}"),
                Hito(EstadoOrden.ACEPTADA, ventasQuimicos, dia(13, 17, 10)),
                Hito(EstadoOrden.EN_PREPARACION, ventasQuimicos, dia(12, 8, 20)),
                Hito(EstadoOrden.EMPACADA, ventasQuimicos, dia(12, 11, 45)),
            ),
        )
        oc1.repartidorId = kevin.id
        evento(oc1, null, "REPARTIDOR", "Repartidor asignado", kevin.nombre, ventasQuimicos, dia(12, 12))
        hitos(oc1, listOf(Hito(EstadoOrden.DESPACHADA, kevin, dia(11, 7, 40)), Hito(EstadoOrden.EN_TRANSITO, kevin, dia(11, 8, 5))))
        ordenarParadas(oc1)
        entregas.findAllByOrdenIdOrderByParada(oc1.id).forEachIndexed { i, e ->
            val receptor = if (nombreEst(e) == hospital) luis else maria
            entregar(oc1, e, kevin, dia(11, 9 + i, 15))
            recibirHistorico(oc1, e, receptor, dia(11, 9 + i, 40))
        }
        hitos(oc1, listOf(Hito(EstadoOrden.ENTREGADA, maria, dia(11, 10, 41), "Todos los destinos confirmaron la recepción")))

        // ---- Brillo SC-002 → OC-002 (en tránsito, 4 destinos) y OC-003 (recién aprobada) ----
        val sc2 = solicitud(
            brillo, "SC-002", adminB, dia(4, 10), EstadoSolicitud.ADJUDICADA,
            listOf(
                Triple(hospital, "Guantes de nitrilo", "60"), Triple(clinica, "Guantes de nitrilo", "40"),
                Triple(torre, "Guantes de nitrilo", "20"), Triple(plaza, "Guantes de nitrilo", "20"),
                Triple(hospital, "Limpiavidrios", "8"), Triple(torre, "Limpiavidrios", "6"), Triple(plaza, "Limpiavidrios", "6"),
                Triple(colegio, "Fundas de basura negras 23x28 medianas", "30"), Triple(torre, "Fundas de basura negras 23x28 medianas", "12"),
            ),
            invitados = listOf(quimicos, higiene, proclean), enviada = dia(4, 10, 20), adjudicada = dia(2, 15, 30),
        )
        val of2 = oferta(
            sc2, higiene, ventasHigiene, Modalidad.ENVIO_DIRECTO, 1, dia(3, 11),
            mapOf("Guantes de nitrilo" to ("200" to "0.18"), "Limpiavidrios" to ("20" to "2.10"), "Fundas de basura negras 23x28 medianas" to ("20" to "1.95")),
            EstadoOferta.ADJUDICADA,
        )
        val of3 = oferta(
            sc2, proclean, ventasProclean, Modalidad.ENVIO_DIRECTO, 2, dia(3, 16),
            mapOf("Guantes de nitrilo" to ("150" to "0.21"), "Fundas de basura negras 23x28 medianas" to ("60" to "1.80")),
            EstadoOferta.ADJUDICADA,
        )
        oferta(sc2, quimicos, ventasQuimicos, Modalidad.RETIRO_BODEGA, 4, dia(3, 9), mapOf("Limpiavidrios" to ("20" to "2.30")), EstadoOferta.NO_ADJUDICADA)
        val oc2 = orden(sc2, of2, "OC-002", adminB, dia(2, 15, 30), setOf("Guantes de nitrilo", "Limpiavidrios"))
        hitos(
            oc2, listOf(
                Hito(EstadoOrden.APROBADA, adminB, dia(2, 15, 30), "4 destino(s) · total USD ${oc2.total.toPlainString()}"),
                Hito(EstadoOrden.ACEPTADA, ventasHigiene, dia(2, 16, 5)),
                Hito(EstadoOrden.EN_PREPARACION, ventasHigiene, dia(1, 8, 30)),
                Hito(EstadoOrden.EMPACADA, ventasHigiene, dia(1, 14, 10)),
            ),
        )
        oc2.repartidorId = diego.id
        evento(oc2, null, "REPARTIDOR", "Repartidor asignado", diego.nombre, ventasHigiene, dia(1, 14, 20))
        hitos(oc2, listOf(Hito(EstadoOrden.DESPACHADA, diego, hoy(7, 30)), Hito(EstadoOrden.EN_TRANSITO, diego, hoy(7, 50))))
        // Diego sigue la ruta optimizada: la primera parada ya confirmó la recepción y la segunda espera confirmarla.
        ordenarParadas(oc2)
        val receptores = mapOf(hospital to luis, clinica to luis, torre to maria, plaza to maria)
        val (primera, segunda) = entregas.findAllByOrdenIdOrderByParada(oc2.id)
        entregar(oc2, primera, diego, hoy(8, 25))
        recibirConKardex(oc2, primera, receptores.getValue(nombreEst(primera)), hoy(8, 40))
        entregar(oc2, segunda, diego, hoy(9, 20))

        val oc3 = orden(sc2, of3, "OC-003", adminB, dia(2, 15, 31), setOf("Fundas de basura negras 23x28 medianas"))
        hitos(oc3, listOf(Hito(EstadoOrden.APROBADA, adminB, dia(2, 15, 31), "2 destino(s) · total USD ${oc3.total.toPlainString()}")))

        // ---- Brillo SC-003 en cotización: tres ofertas para comparar ----
        val sc3 = solicitud(
            brillo, "SC-003", adminB, hoy(8), EstadoSolicitud.EN_COTIZACION,
            listOf(Triple(hospital, "Alcohol antiséptico", "25"), Triple(clinica, "Desinfectante aromaterapia", "18"), Triple(plaza, "Cloro líquido 5.5%", "22")),
            invitados = listOf(quimicos, higiene, proclean), enviada = hoy(8, 15), adjudicada = null,
        )
        oferta(
            sc3, quimicos, ventasQuimicos, Modalidad.ENVIO_DIRECTO, 2, hoy(9, 10),
            mapOf("Alcohol antiséptico" to ("25" to "3.20"), "Desinfectante aromaterapia" to ("18" to "2.60"), "Cloro líquido 5.5%" to ("22" to "1.85")),
            EstadoOferta.ENVIADA,
        )
        oferta(
            sc3, higiene, ventasHigiene, Modalidad.ENVIO_DIRECTO, 1, hoy(9, 40),
            mapOf("Alcohol antiséptico" to ("15" to "2.90"), "Desinfectante aromaterapia" to ("18" to "2.75"), "Cloro líquido 5.5%" to ("22" to "1.70")),
            EstadoOferta.ENVIADA,
        )
        oferta(
            sc3, proclean, ventasProclean, Modalidad.RETIRO_BODEGA, 1, hoy(10, 5),
            mapOf("Alcohol antiséptico" to ("25" to "3.05"), "Cloro líquido 5.5%" to ("22" to "1.75")),
            EstadoOferta.ENVIADA,
        )

        // ---- Andina SC-001 → OC-001: lista para retiro en el local del proveedor ----
        val sa1 = solicitud(
            andina, "SC-001", adminA, dia(3, 9), EstadoSolicitud.ADJUDICADA,
            listOf(Triple(municipio, "Detergente", "16"), Triple(municipio, "Fundas de basura negras 23x28 medianas", "10")),
            invitados = listOf(higiene, proclean, papelera), enviada = dia(3, 9, 15), adjudicada = dia(2, 12),
        )
        val ofA = oferta(
            sa1, papelera, ventasPapelera, Modalidad.RETIRO_LOCAL, 1, dia(3, 14),
            mapOf("Detergente" to ("16" to "2.25"), "Fundas de basura negras 23x28 medianas" to ("10" to "1.70")), EstadoOferta.ADJUDICADA,
        )
        oferta(sa1, higiene, ventasHigiene, Modalidad.ENVIO_DIRECTO, 3, dia(3, 16), mapOf("Detergente" to ("16" to "2.50")), EstadoOferta.NO_ADJUDICADA)
        val oa1 = orden(sa1, ofA, "OC-001", adminA, dia(2, 12), null)
        hitos(
            oa1, listOf(
                Hito(EstadoOrden.APROBADA, adminA, dia(2, 12), "1 destino(s) · total USD ${oa1.total.toPlainString()}"),
                Hito(EstadoOrden.ACEPTADA, ventasPapelera, dia(2, 13)),
                Hito(EstadoOrden.EN_PREPARACION, ventasPapelera, dia(1, 9)),
                Hito(EstadoOrden.EMPACADA, ventasPapelera, dia(1, 11, 30)),
                Hito(EstadoOrden.LISTA_PARA_RETIRO, ventasPapelera, dia(1, 11, 35), "Retirar en Av. España 14-20, de 8:00 a 17:00"),
            ),
        )
    }

    // ---------------- Ayudantes ----------------

    private data class Hito(val estado: EstadoOrden, val usuario: Usuario, val cuando: Instant, val detalle: String? = null)

    private val hoyEc: LocalDate get() = LocalDate.now(clock.withZone(Zona.ECUADOR))

    /** Instante [dias] atrás a la hora indicada (hora de Ecuador). */
    private fun dia(dias: Long, hora: Int, minuto: Int = 0): Instant =
        hoyEc.minusDays(dias).atTime(LocalTime.of(hora, minuto)).atZone(Zona.ECUADOR).toInstant()

    /**
     * Hoy a esa hora. Los eventos de hoy terminan a las 11:00; si la base se siembra antes, todos se corren
     * para terminar justo ahora (mismo orden y separación), así nada queda en el futuro.
     */
    private fun hoy(hora: Int, minuto: Int = 0): Instant {
        val ahora = clock.instant()
        if (dia(0, FIN_EVENTOS_HOY / 60, FIN_EVENTOS_HOY % 60).isBefore(ahora)) return dia(0, hora, minuto)
        return ahora.minus(Duration.ofMinutes((FIN_EVENTOS_HOY - (hora * 60 + minuto)).toLong()))
    }

    private fun producto(est: String, nombre: String, empresa: Empresa): Producto {
        val e = establecimientos.findAllByEmpresaIdAndActivoTrueOrderByNombre(empresa.id).first { it.nombre == est }
        return productos.findAllByEstablecimientoIdOrderByNombre(e.id).first { it.nombre == nombre }
    }

    private fun nombreEst(e: Entrega) = establecimientos.findById(e.establecimientoId).get().nombre

    private fun solicitud(
        empresa: Empresa,
        codigo: String,
        admin: Usuario,
        creada: Instant,
        estado: EstadoSolicitud,
        pedidos: List<Triple<String, String, String>>,
        invitados: List<Proveedor>,
        enviada: Instant?,
        adjudicada: Instant?,
    ): Solicitud {
        val s = solicitudes.save(
            Solicitud(empresa.id, codigo, creada.atZone(Zona.ECUADOR).toLocalDate().plusDays(3), null, admin.id).apply {
                this.estado = estado
                enviadaEn = enviada
                adjudicadaEn = adjudicada
            },
        )
        pedidos.groupBy { it.second }.forEach { (nombre, grupo) ->
            val prods = grupo.map { producto(it.first, nombre, empresa) to BigDecimal(it.third) }
            val total = prods.fold(BigDecimal.ZERO) { acc, (_, c) -> acc.add(c) }
            val primero = prods.first().first
            val item = items.save(SolicitudItem(s.id, primero.nombre, primero.nombreNormalizado, primero.unidad, total))
            prods.forEach { (p, c) -> lineas.save(SolicitudLinea(empresa.id, s.id, item.id, p.establecimientoId, p.id, c, c)) }
        }
        invitados.forEach { invitaciones.save(SolicitudProveedor(s.id, it.id)) }
        solicitudes.flush()
        jdbc.update("update solicitud set created_at = ? where id = ?", Timestamp.from(creada), s.id)
        return s
    }

    private fun oferta(
        s: Solicitud,
        p: Proveedor,
        autor: Usuario,
        modalidad: Modalidad,
        plazo: Int,
        cuando: Instant,
        precios: Map<String, Pair<String, String>>,
        estado: EstadoOferta,
    ): Oferta {
        val o = ofertas.save(Oferta(s.id, p.id, modalidad, plazo, null, autor.id, cuando).apply { this.estado = estado })
        val porNombre = items.findAllBySolicitudIdOrderByNombre(s.id).associateBy { it.nombre }
        precios.forEach { (nombre, dp) -> ofertaItems.save(OfertaItem(o.id, porNombre.getValue(nombre).id, BigDecimal(dp.first), BigDecimal(dp.second))) }
        return o
    }

    /** Orden con los productos [soloItems] (o todos los de la oferta), una entrega por destino. */
    private fun orden(s: Solicitud, o: Oferta, codigo: String, admin: Usuario, cuando: Instant, soloItems: Set<String>?): OrdenCompra {
        val porId = items.findAllBySolicitudIdOrderByNombre(s.id).associateBy { it.id }
        val precios = ofertaItems.findAllByOfertaIdIn(listOf(o.id)).associate { it.itemId to it.precioUnitario }
        val elegidas = lineas.findAllBySolicitudId(s.id).filter { l ->
            l.itemId in precios && (soloItems == null || porId.getValue(l.itemId).nombre in soloItems)
        }
        val total = elegidas.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.cantidad.multiply(precios.getValue(l.itemId))) }.setScale(2, RoundingMode.HALF_UP)
        val oc = ordenes.save(OrdenCompra(s.empresaId, o.proveedorId, s.id, o.id, codigo, o.modalidad, total, o.plazoDias, admin.id))
        val nombres = establecimientos.findAllById(elegidas.map { it.establecimientoId }.toSet()).associate { it.id to it.nombre }
        val destinos = elegidas.map { it.establecimientoId }.distinct().sortedBy { nombres[it] }
        val porDestino = destinos.mapIndexed { i, id -> id to entregas.save(Entrega(s.empresaId, oc.id, id, i + 1)) }.toMap()
        elegidas.forEach { l ->
            ordenLineas.save(OrdenLinea(oc.id, porDestino.getValue(l.establecimientoId).id, l.id, l.productoId, l.cantidad, porId.getValue(l.itemId).unidad, precios.getValue(l.itemId)))
        }
        ordenes.flush()
        jdbc.update("update orden_compra set created_at = ? where id = ?", Timestamp.from(cuando), oc.id)
        return oc
    }

    /** Como al despachar en la app: las paradas quedan en el orden de la ruta optimizada. */
    private fun ordenarParadas(o: OrdenCompra) {
        entregas.flush()
        val orden = rutas.ordenOptimizado(o)
        entregas.findAllByOrdenIdOrderByParada(o.id).forEach { e -> orden[e.id]?.let { e.parada = it } }
        entregas.flush()
    }

    private fun hitos(o: OrdenCompra, lista: List<Hito>) = lista.forEach { h ->
        o.estado = h.estado
        evento(o, null, h.estado.name, OrdenCompraService.titulo(h.estado), h.detalle, h.usuario, h.cuando)
    }

    private fun evento(o: OrdenCompra, e: Entrega?, estado: String, titulo: String, detalle: String?, u: Usuario, cuando: Instant) {
        eventos.save(EventoOrden(o.id, e?.id, estado, titulo, detalle, u.id, cuando))
        eventos.flush()
    }

    private fun entregar(o: OrdenCompra, e: Entrega, repartidor: Usuario, cuando: Instant) {
        e.estado = EstadoEntrega.ENTREGADA
        e.entregadaEn = cuando
        evento(o, e, "ENTREGA", "Entregado en ${nombreEst(e)}", "Pendiente de confirmación del destino", repartidor, cuando)
    }

    /** Recepción antigua: sin INGRESO nuevo (los ingresos semanales simulados ya representan esa reposición). */
    private fun recibirHistorico(o: OrdenCompra, e: Entrega, receptor: Usuario, cuando: Instant) {
        ordenLineas.findAllByEntregaId(e.id).forEach { it.cantidadRecibida = it.cantidad }
        marcarRecibida(o, e, receptor, cuando)
    }

    /** Recepción reciente: registra el INGRESO por el servicio (como lo haría la app) y lo enlaza a la línea. */
    private fun recibirConKardex(o: OrdenCompra, e: Entrega, receptor: Usuario, cuando: Instant) {
        val bodega = ubicaciones.findByEstablecimientoIdAndTipo(e.establecimientoId, TipoUbicacion.BODEGA)!!
        val proveedor = proveedores.findById(o.proveedorId).get().nombre
        comoUsuario(receptor) {
            ordenLineas.findAllByEntregaId(e.id).forEach { l ->
                val r = movimientos.registrar(
                    MovimientoRequest(
                        clientUuid = UUID.nameUUIDFromBytes("recepcion:${l.id}".toByteArray()),
                        tipo = TipoMovimiento.INGRESO, productoId = l.productoId, ubicacionId = bodega.id,
                        cantidad = l.cantidad, unidad = l.unidad, ocurridoEn = Zona.presentar(cuando),
                        nota = "Recepción ${o.codigo} · $proveedor",
                    ),
                    esRecepcion = true,
                )
                l.cantidadRecibida = l.cantidad.setScale(3)
                l.movimientoId = r.movimiento.id
            }
        }
        marcarRecibida(o, e, receptor, cuando)
    }

    private fun marcarRecibida(o: OrdenCompra, e: Entrega, receptor: Usuario, cuando: Instant) {
        e.estado = EstadoEntrega.RECIBIDA
        e.recibidaEn = cuando
        e.recibidaPor = receptor.id
        e.completa = true
        evento(o, e, "RECEPCION", "Recepción confirmada en ${nombreEst(e)}", "Llegó completo", receptor, cuando)
    }

    private fun comoUsuario(u: Usuario, bloque: () -> Unit) {
        val jwt = Jwt.withTokenValue("semilla").header("alg", "none").subject(u.id.toString()).build()
        SecurityContextHolder.getContext().authentication = AutenticacionJwt(UsuarioAutenticado(u.id, u.empresaId, u.rol, u.proveedorId), jwt)
        try {
            bloque()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    companion object {
        const val RUC_QUIMICOS = "1790000000001"

        /** Minuto del día (11:00) en que termina la secuencia de eventos de hoy. */
        private const val FIN_EVENTOS_HOY = 11 * 60
    }
}
