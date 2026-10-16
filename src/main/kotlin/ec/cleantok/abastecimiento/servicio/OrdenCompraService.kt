package ec.cleantok.abastecimiento.servicio

import ec.cleantok.abastecimiento.api.AccionesOrdenDto
import ec.cleantok.abastecimiento.api.CotizacionOrdenDto
import ec.cleantok.abastecimiento.api.ItemCotizadoDto
import ec.cleantok.abastecimiento.api.EntregaDto
import ec.cleantok.abastecimiento.api.EventoDto
import ec.cleantok.abastecimiento.api.LineaOrdenDto
import ec.cleantok.abastecimiento.api.OrdenDto
import ec.cleantok.abastecimiento.api.OrdenResumenDto
import ec.cleantok.abastecimiento.api.PasoDto
import ec.cleantok.abastecimiento.api.PuntoDto
import ec.cleantok.abastecimiento.api.RecibirRequest
import ec.cleantok.abastecimiento.api.SiguienteEstadoDto
import ec.cleantok.abastecimiento.dominio.Entrega
import ec.cleantok.abastecimiento.dominio.EntregaRepository
import ec.cleantok.abastecimiento.dominio.EstadoEntrega
import ec.cleantok.abastecimiento.dominio.EstadoOrden
import ec.cleantok.abastecimiento.dominio.EventoOrden
import ec.cleantok.abastecimiento.dominio.EventoOrdenRepository
import ec.cleantok.abastecimiento.dominio.Modalidad
import ec.cleantok.abastecimiento.dominio.OrdenCompra
import ec.cleantok.abastecimiento.dominio.OrdenCompraRepository
import ec.cleantok.abastecimiento.dominio.OfertaItemRepository
import ec.cleantok.abastecimiento.dominio.OfertaRepository
import ec.cleantok.abastecimiento.dominio.OrdenLineaRepository
import ec.cleantok.abastecimiento.dominio.SolicitudItemRepository
import ec.cleantok.abastecimiento.dominio.SolicitudLineaRepository
import ec.cleantok.abastecimiento.dominio.SolicitudRepository
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Zona
import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.dominio.UsuarioEstablecimientoRepository
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.ProveedorRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.inventario.api.MovimientoRequest
import ec.cleantok.inventario.api.ProductoRefDto
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.inventario.dominio.TipoMovimiento
import ec.cleantok.inventario.servicio.MovimientoService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID

/**
 * Órdenes de compra: quién las ve, cómo avanzan y el rastreo de cada paso.
 * - Proveedor: la orden llega aprobada y pagada, así que no se acepta: empieza a despachar (preparar), empaca,
 *   despacha (o deja listo para retiro) y asigna repartidor.
 * - Repartidor: marca "salió de bodega", "en tránsito" y cada destino entregado.
 * - Encargado, supervisor de la sede (o admin): confirma la recepción en su establecimiento; eso registra el INGRESO a bodega.
 * Cuando todos los destinos confirman, la orden pasa sola a ENTREGADA.
 */
@Service
class OrdenCompraService(
    private val ordenes: OrdenCompraRepository,
    private val entregas: EntregaRepository,
    private val lineas: OrdenLineaRepository,
    private val eventos: EventoOrdenRepository,
    private val solicitudes: SolicitudRepository,
    private val solicitudItems: SolicitudItemRepository,
    private val solicitudLineas: SolicitudLineaRepository,
    private val ofertas: OfertaRepository,
    private val ofertaItems: OfertaItemRepository,
    private val fichas: FichaEmpresa,
    private val empresas: EmpresaRepository,
    private val proveedores: ProveedorRepository,
    private val usuarios: UsuarioRepository,
    private val establecimientos: EstablecimientoRepository,
    private val ubicaciones: UbicacionRepository,
    private val asignaciones: UsuarioEstablecimientoRepository,
    private val productos: ProductoRepository,
    private val movimientos: MovimientoService,
    private val rutas: RutaService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun listar(): List<OrdenResumenDto> {
        val u = ContextoSeguridad.usuario()
        val lista = when (u.rol) {
            Rol.ADMIN_EMPRESA -> ordenes.findAllByEmpresaIdOrderByCreatedAtDesc(u.empresaId!!)
            Rol.SUPERVISOR, Rol.ENCARGADO -> ordenes.conEntregasPara(u.id)
            Rol.PROVEEDOR -> ordenes.findAllByProveedorIdOrderByCreatedAtDesc(u.proveedorId!!)
            Rol.REPARTIDOR -> ordenes.findAllByRepartidorIdOrderByCreatedAtDesc(u.id)
            Rol.SUPER_ADMIN -> emptyList()
        }
        return lista.map(::resumen)
    }

    fun resumenesDeSolicitud(solicitudId: UUID): List<OrdenResumenDto> = ordenes.findAllBySolicitudId(solicitudId).map(::resumen)

    @Transactional(readOnly = true)
    fun obtener(id: UUID): OrdenDto = detalle(visible(id))

    @Transactional
    fun cambiarEstado(id: UUID, nuevo: EstadoOrden, nota: String?): OrdenDto {
        val u = ContextoSeguridad.usuario()
        val o = visible(id)
        if (nuevo !in siguientes(o, u)) {
            throw ConflictoException("No se puede pasar de ${titulo(o.estado).lowercase()} a ${titulo(nuevo).lowercase()}")
        }
        avanzar(o, nuevo, nota?.trim()?.ifBlank { null }, u)
        if (nuevo == EstadoOrden.DESPACHADA) ordenarParadas(o)
        return detalle(o)
    }

    @Transactional
    fun asignarRepartidor(id: UUID, repartidorId: UUID): OrdenDto {
        val u = ContextoSeguridad.usuario()
        val o = visible(id)
        if (!puedeAsignar(o, u)) throw ConflictoException("No se puede asignar repartidor en este estado")
        val r = usuarios.findById(repartidorId).orElse(null)
            ?.takeIf { it.rol == Rol.REPARTIDOR && it.proveedorId == o.proveedorId && it.activo }
            ?: throw NoEncontradoException("Repartidor")
        o.repartidorId = r.id
        registrar(o, null, "REPARTIDOR", "Repartidor asignado", r.nombre, u)
        return detalle(o)
    }

    /** El repartidor dejó el pedido en un destino. Si la orden seguía en "salió de bodega", pasa a "en tránsito". */
    @Transactional
    fun marcarEntregada(id: UUID, entregaId: UUID, nota: String?): OrdenDto {
        val u = ContextoSeguridad.usuario()
        val o = visible(id)
        val e = entregaDe(o, entregaId)
        if (e.id !in marcables(o, u)) throw ConflictoException("Esta entrega no se puede marcar como entregada")
        if (o.estado == EstadoOrden.DESPACHADA) avanzar(o, EstadoOrden.EN_TRANSITO, null, u)
        e.estado = EstadoEntrega.ENTREGADA
        e.entregadaEn = clock.instant()
        registrar(o, e, "ENTREGA", "Entregado en ${nombreEst(e.establecimientoId)}", nota?.trim()?.ifBlank { null } ?: "Pendiente de confirmación del destino", u)
        return detalle(o)
    }

    /**
     * El destino confirma lo que llegó. Cada línea recibida se registra como INGRESO en la bodega del establecimiento
     * (con un clientUuid derivado de la línea, así no se duplica) y queda enlazada al movimiento del kardex.
     */
    @Transactional
    fun recibir(id: UUID, entregaId: UUID, req: RecibirRequest): OrdenDto {
        val u = ContextoSeguridad.usuario()
        val o = visible(id)
        val e = entregaDe(o, entregaId)
        if (e.id !in recibibles(o, u)) {
            if (e.estado == EstadoEntrega.RECIBIDA) throw ConflictoException("Esta entrega ya fue recibida")
            throw ConflictoException("El pedido todavía no ha salido hacia este destino")
        }
        val ls = lineas.findAllByEntregaId(e.id)
        val pedidas = req.lineas?.associate { it.lineaId!! to it.cantidadRecibida!! } ?: emptyMap()
        if (pedidas.keys.any { k -> ls.none { it.id == k } }) throw NoEncontradoException("Línea de la entrega")
        val bodega = ubicaciones.findByEstablecimientoIdAndTipo(e.establecimientoId, TipoUbicacion.BODEGA)!!
        val proveedor = proveedores.findById(o.proveedorId).orElseThrow { NoEncontradoException("Proveedor") }.nombre
        val ahora = clock.instant()

        ls.forEach { l ->
            val recibida = pedidas[l.id] ?: l.cantidad
            if (recibida > l.cantidad) throw ValidacionException("No puedes recibir más de lo pedido (${l.cantidad.stripTrailingZeros().toPlainString()} ${l.unidad})")
            l.cantidadRecibida = recibida.setScale(3)
            if (recibida.signum() > 0) {
                val r = movimientos.registrar(
                    MovimientoRequest(
                        clientUuid = UUID.nameUUIDFromBytes("recepcion:${l.id}".toByteArray()),
                        tipo = TipoMovimiento.INGRESO,
                        productoId = l.productoId,
                        ubicacionId = bodega.id,
                        cantidad = recibida,
                        unidad = l.unidad,
                        ocurridoEn = Zona.presentar(ahora),
                        nota = "Recepción ${o.codigo} · $proveedor",
                        fotoId = req.fotoId,
                    ),
                    esRecepcion = true,
                )
                l.movimientoId = r.movimiento.id
            }
        }
        val completa = ls.all { it.cantidadRecibida!!.compareTo(it.cantidad) == 0 }
        e.estado = EstadoEntrega.RECIBIDA
        e.recibidaEn = ahora
        e.recibidaPor = u.id
        e.completa = completa
        e.nota = req.nota?.trim()?.ifBlank { null }
        e.fotoId = req.fotoId
        val detalle = listOfNotNull(if (completa) "Llegó completo" else "Llegó incompleto", e.nota).joinToString(" · ")
        registrar(o, e, "RECEPCION", "Recepción confirmada en ${nombreEst(e.establecimientoId)}", detalle, u)

        if (entregas.findAllByOrdenIdOrderByParada(o.id).all { it.estado == EstadoEntrega.RECIBIDA }) {
            avanzar(o, EstadoOrden.ENTREGADA, "Todos los destinos confirmaron la recepción", u)
        }
        return detalle(o)
    }

    // ---------------- Reglas ----------------

    /** Orden visible para el usuario actual; si no, 404. */
    fun visible(id: UUID): OrdenCompra {
        val u = ContextoSeguridad.usuario()
        val o = ordenes.findById(id).orElse(null)
        val ve = o != null && when (u.rol) {
            Rol.ADMIN_EMPRESA -> o.empresaId == u.empresaId
            Rol.SUPERVISOR, Rol.ENCARGADO -> o.empresaId == u.empresaId &&
                entregas.findAllByOrdenIdOrderByParada(o.id).any { asignaciones.existsByUsuarioIdAndEstablecimientoId(u.id, it.establecimientoId) }
            Rol.PROVEEDOR -> o.proveedorId == u.proveedorId
            Rol.REPARTIDOR -> o.repartidorId == u.id
            Rol.SUPER_ADMIN -> false
        }
        if (!ve) throw NoEncontradoException("Orden de compra")
        return o!!
    }

    private fun siguientes(o: OrdenCompra, u: UsuarioAutenticado): List<EstadoOrden> {
        val proveedor = u.esProveedor && o.proveedorId == u.proveedorId
        val repartidor = u.esRepartidor && o.repartidorId == u.id
        val admin = u.esAdmin && o.empresaId == u.empresaId
        return when (o.estado) {
            // La empresa ya pagó al aprobar: el proveedor no la acepta ni la rechaza, empieza a despachar.
            // ACEPTADA solo queda en órdenes anteriores a este cambio.
            EstadoOrden.APROBADA, EstadoOrden.ACEPTADA -> if (proveedor) listOf(EstadoOrden.EN_PREPARACION) else emptyList()
            EstadoOrden.EN_PREPARACION -> if (proveedor) listOf(EstadoOrden.EMPACADA) else emptyList()
            EstadoOrden.EMPACADA -> when {
                o.esEnvio && (proveedor || repartidor) -> listOf(EstadoOrden.DESPACHADA)
                !o.esEnvio && proveedor -> listOf(EstadoOrden.LISTA_PARA_RETIRO)
                else -> emptyList()
            }
            EstadoOrden.DESPACHADA -> if (proveedor || repartidor) listOf(EstadoOrden.EN_TRANSITO) else emptyList()
            EstadoOrden.LISTA_PARA_RETIRO -> if (proveedor || admin) listOf(EstadoOrden.RETIRADA) else emptyList()
            else -> emptyList()
        }
    }

    private fun puedeAsignar(o: OrdenCompra, u: UsuarioAutenticado) =
        u.esProveedor && o.proveedorId == u.proveedorId && o.esEnvio &&
            o.estado in setOf(EstadoOrden.APROBADA, EstadoOrden.ACEPTADA, EstadoOrden.EN_PREPARACION, EstadoOrden.EMPACADA, EstadoOrden.DESPACHADA)

    private fun marcables(o: OrdenCompra, u: UsuarioAutenticado): List<UUID> {
        val actor = (u.esProveedor && o.proveedorId == u.proveedorId) || (u.esRepartidor && o.repartidorId == u.id)
        if (!actor || !o.esEnvio || o.estado !in EN_CAMINO) return emptyList()
        return entregas.findAllByOrdenIdOrderByParada(o.id).filter { it.estado == EstadoEntrega.PENDIENTE }.map { it.id }
    }

    private fun recibibles(o: OrdenCompra, u: UsuarioAutenticado): List<UUID> {
        if (o.estado !in EN_CAMINO + EstadoOrden.RETIRADA || o.empresaId != u.empresaId) return emptyList()
        return entregas.findAllByOrdenIdOrderByParada(o.id).filter { e ->
            e.estado != EstadoEntrega.RECIBIDA &&
                (u.esAdmin || ((u.esEncargado || u.esSupervisor) && asignaciones.existsByUsuarioIdAndEstablecimientoId(u.id, e.establecimientoId)))
        }.map { it.id }
    }

    private fun avanzar(o: OrdenCompra, nuevo: EstadoOrden, detalle: String?, u: UsuarioAutenticado) {
        o.estado = nuevo
        registrar(o, null, nuevo.name, titulo(nuevo), detalle, u)
    }

    private fun registrar(o: OrdenCompra, e: Entrega?, estado: String, titulo: String, detalle: String?, u: UsuarioAutenticado) {
        eventos.save(EventoOrden(o.id, e?.id, estado, titulo, detalle, u.id, clock.instant()))
    }

    /** Al despachar, las paradas quedan en el orden de la ruta optimizada. */
    private fun ordenarParadas(o: OrdenCompra) {
        val orden = rutas.ordenOptimizado(o)
        entregas.findAllByOrdenIdOrderByParada(o.id).forEach { e -> orden[e.id]?.let { e.parada = it } }
    }

    private fun entregaDe(o: OrdenCompra, entregaId: UUID): Entrega =
        entregas.findById(entregaId).orElse(null)?.takeIf { it.ordenId == o.id } ?: throw NoEncontradoException("Entrega")

    private fun nombreEst(id: UUID) = establecimientos.findById(id).map { it.nombre }.orElse("—")

    // ---------------- DTOs ----------------

    private fun resumen(o: OrdenCompra): OrdenResumenDto {
        val es = entregas.findAllByOrdenIdOrderByParada(o.id)
        val ultimo = eventos.findAllByOrdenIdOrderBySecuencia(o.id).lastOrNull()
        return OrdenResumenDto(
            id = o.id,
            codigo = o.codigo,
            estado = o.estado,
            modalidad = o.modalidad,
            empresa = RefDto(o.empresaId, empresas.findById(o.empresaId).get().nombre),
            proveedor = RefDto(o.proveedorId, proveedores.findById(o.proveedorId).orElseThrow { NoEncontradoException("Proveedor") }.nombre),
            total = o.total,
            creadaEn = Zona.presentar(o.createdAt),
            destinos = es.size,
            recibidas = es.count { it.estado == EstadoEntrega.RECIBIDA },
            repartidor = o.repartidorId?.let { r -> usuarios.findById(r).map { RefDto(it.id, it.nombre) }.orElse(null) },
            ultimoEvento = ultimo?.let { eventoDto(it, nombresEst(es)) },
        )
    }

    private fun detalle(o: OrdenCompra): OrdenDto {
        val u = ContextoSeguridad.usuario()
        val es = entregas.findAllByOrdenIdOrderByParada(o.id)
        val ests = establecimientos.findAllById(es.map { it.establecimientoId }).associateBy { it.id }
        val nombres = nombresEst(es)
        val ls = lineas.findAllByOrdenId(o.id).groupBy { it.entregaId }
        val prods = productos.findAllById(ls.values.flatten().map { it.productoId }).associateBy { it.id }
        val evs = eventos.findAllByOrdenIdOrderBySecuencia(o.id)
        val proveedor = proveedores.findById(o.proveedorId).orElseThrow { NoEncontradoException("Proveedor") }
        val ocultarPrecios = u.esRepartidor
        val recibiblesIds = recibibles(o, u)
        return OrdenDto(
            id = o.id,
            codigo = o.codigo,
            estado = o.estado,
            modalidad = o.modalidad,
            empresa = RefDto(o.empresaId, empresas.findById(o.empresaId).get().nombre),
            proveedor = RefDto(proveedor.id, proveedor.nombre),
            solicitud = RefDto(o.solicitudId, solicitudes.findById(o.solicitudId).get().codigo),
            total = if (ocultarPrecios) java.math.BigDecimal.ZERO else o.total,
            plazoDias = o.plazoDias,
            creadaEn = Zona.presentar(o.createdAt),
            repartidor = o.repartidorId?.let { r -> usuarios.findById(r).map { RefDto(it.id, it.nombre) }.orElse(null) },
            puntoRetiro = when (o.modalidad) {
                Modalidad.RETIRO_LOCAL -> PuntoDto("Local de ${proveedor.nombre}", proveedor.direccion, proveedor.lat, proveedor.lng)
                Modalidad.RETIRO_BODEGA -> PuntoDto("Bodega de ${proveedor.nombre}", proveedor.bodegaDireccion, proveedor.bodegaLat, proveedor.bodegaLng)
                Modalidad.ENVIO_DIRECTO -> null
            },
            pasos = pasos(o, evs),
            eventos = evs.reversed().map { eventoDto(it, nombres) },
            entregas = es.map { e ->
                val est = ests.getValue(e.establecimientoId)
                EntregaDto(
                    id = e.id,
                    parada = e.parada,
                    establecimiento = RefDto(est.id, est.nombre),
                    direccion = est.direccion,
                    lat = est.lat,
                    lng = est.lng,
                    estado = e.estado,
                    entregadaEn = e.entregadaEn?.let(Zona::presentar),
                    recibidaEn = e.recibidaEn?.let(Zona::presentar),
                    recibidaPor = e.recibidaPor?.let { r -> usuarios.findById(r).map { RefDto(it.id, it.nombre) }.orElse(null) },
                    completa = e.completa,
                    nota = e.nota,
                    fotoId = e.fotoId,
                    lineas = ls[e.id].orEmpty().map { l ->
                        val p = prods.getValue(l.productoId)
                        LineaOrdenDto(
                            l.id, ProductoRefDto(p.id, p.nombre, p.unidad), l.cantidad, l.unidad,
                            if (ocultarPrecios) java.math.BigDecimal.ZERO else l.precioUnitario,
                            if (ocultarPrecios) java.math.BigDecimal.ZERO else l.cantidad.multiply(l.precioUnitario).setScale(2, RoundingMode.HALF_UP),
                            l.cantidadRecibida,
                        )
                    }.sortedBy { it.producto.nombre },
                )
            },
            acciones = AccionesOrdenDto(
                siguientes = siguientes(o, u).map { SiguienteEstadoDto(it, titulo(it)) },
                asignarRepartidor = puedeAsignar(o, u),
                marcarEntregada = marcables(o, u),
                recibir = recibiblesIds,
            ),
            cotizacion = if (ocultarPrecios) null else cotizacion(o),
            empresaContacto = if (u.esProveedor) fichas.de(o.empresaId) else null,
        )
    }

    /** Solicitud y oferta que originaron la orden: lo que se pidió, lo que el proveedor cotizó y lo que quedó aquí. */
    private fun cotizacion(o: OrdenCompra): CotizacionOrdenDto? {
        val oferta = ofertas.findById(o.ofertaId).orElse(null) ?: return null
        val s = solicitudes.findById(o.solicitudId).orElse(null) ?: return null
        val cotizados = ofertaItems.findAllByOfertaIdIn(listOf(oferta.id)).associateBy { it.itemId }
        val enOrden = solicitudLineas.findAllById(lineas.findAllByOrdenId(o.id).map { it.solicitudLineaId }).map { it.itemId }.toSet()
        val items = solicitudItems.findAllBySolicitudIdOrderByNombre(s.id).mapNotNull { item ->
            cotizados[item.id]?.let { oi ->
                ItemCotizadoDto(item.nombre, item.unidad, item.cantidad, oi.cantidadDisponible, oi.precioUnitario, item.id in enOrden)
            }
        }
        val total = items.fold(java.math.BigDecimal.ZERO) { acc, i -> acc.add(i.cantidadOfertada.min(i.cantidadSolicitada).multiply(i.precioUnitario)) }
            .setScale(2, RoundingMode.HALF_UP)
        return CotizacionOrdenDto(
            solicitud = RefDto(s.id, s.codigo),
            fechaRequerida = s.fechaRequerida,
            notaSolicitud = s.nota,
            solicitadaEn = s.enviadaEn?.let(Zona::presentar),
            ofertaEnviadaEn = Zona.presentar(oferta.enviadaEn),
            ofertadaPor = usuarios.findById(oferta.usuarioId).map { RefDto(it.id, it.nombre) }.orElse(null),
            modalidad = oferta.modalidad,
            plazoDias = oferta.plazoDias,
            notaOferta = oferta.nota,
            totalOfertado = total,
            items = items,
        )
    }

    /** Línea de tiempo canónica según la modalidad, con la fecha en que se alcanzó cada paso. */
    private fun pasos(o: OrdenCompra, evs: List<EventoOrden>): List<PasoDto> {
        val camino = when {
            o.estado == EstadoOrden.RECHAZADA -> listOf(EstadoOrden.APROBADA, EstadoOrden.RECHAZADA)
            o.esEnvio -> CAMINO_ENVIO
            else -> CAMINO_RETIRO
        }
        // Órdenes anteriores al cambio que quedaron "aceptadas": van en el paso de aprobada y pagada.
        val actual = camino.indexOf(if (o.estado == EstadoOrden.ACEPTADA) EstadoOrden.APROBADA else o.estado)
        return camino.mapIndexed { i, estado ->
            val fecha = evs.firstOrNull { it.estado == estado.name }?.ocurridoEn?.let(Zona::presentar)
            PasoDto(estado, titulo(estado), fecha, hecho = i <= actual, actual = i == actual)
        }
    }

    private fun nombresEst(es: List<Entrega>): Map<UUID, Pair<UUID, String>> {
        val ests = establecimientos.findAllById(es.map { it.establecimientoId }).associateBy { it.id }
        return es.associate { it.id to (it.establecimientoId to (ests[it.establecimientoId]?.nombre ?: "—")) }
    }

    private fun eventoDto(ev: EventoOrden, destinos: Map<UUID, Pair<UUID, String>>): EventoDto {
        val autor = usuarios.findById(ev.usuarioId).get()
        return EventoDto(
            estado = ev.estado,
            titulo = ev.titulo,
            detalle = ev.detalle,
            usuario = RefDto(autor.id, autor.nombre),
            rol = autor.rol,
            ocurridoEn = Zona.presentar(ev.ocurridoEn),
            destino = ev.entregaId?.let { destinos[it] }?.let { (id, nombre) -> RefDto(id, nombre) },
        )
    }

    companion object {
        private val EN_CAMINO = setOf(EstadoOrden.DESPACHADA, EstadoOrden.EN_TRANSITO)
        // Sin "aceptado": la orden llega pagada y el proveedor empieza a despacharla.
        private val CAMINO_ENVIO = listOf(
            EstadoOrden.APROBADA, EstadoOrden.EN_PREPARACION, EstadoOrden.EMPACADA,
            EstadoOrden.DESPACHADA, EstadoOrden.EN_TRANSITO, EstadoOrden.ENTREGADA,
        )
        private val CAMINO_RETIRO = listOf(
            EstadoOrden.APROBADA, EstadoOrden.EN_PREPARACION, EstadoOrden.EMPACADA,
            EstadoOrden.LISTA_PARA_RETIRO, EstadoOrden.RETIRADA, EstadoOrden.ENTREGADA,
        )

        fun titulo(e: EstadoOrden): String = when (e) {
            EstadoOrden.APROBADA -> "Orden aprobada y pagada"
            EstadoOrden.ACEPTADA -> "Pedido aceptado por el proveedor"
            EstadoOrden.RECHAZADA -> "Pedido rechazado por el proveedor"
            EstadoOrden.EN_PREPARACION -> "Despacho en preparación"
            EstadoOrden.EMPACADA -> "Empacado"
            EstadoOrden.LISTA_PARA_RETIRO -> "Listo para retiro"
            EstadoOrden.RETIRADA -> "Retirado por la empresa"
            EstadoOrden.DESPACHADA -> "Salió de bodega"
            EstadoOrden.EN_TRANSITO -> "En tránsito"
            EstadoOrden.ENTREGADA -> "Entregado"
        }
    }
}
