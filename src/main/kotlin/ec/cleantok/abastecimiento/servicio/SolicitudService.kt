package ec.cleantok.abastecimiento.servicio

import ec.cleantok.abastecimiento.api.AdjudicarRequest
import ec.cleantok.abastecimiento.api.CotizacionDto
import ec.cleantok.abastecimiento.api.CrearSolicitudRequest
import ec.cleantok.abastecimiento.api.DestinoDto
import ec.cleantok.abastecimiento.api.DestinoProveedorDto
import ec.cleantok.abastecimiento.api.ItemProveedorDto
import ec.cleantok.abastecimiento.api.OfertaDto
import ec.cleantok.abastecimiento.api.OfertaItemDto
import ec.cleantok.abastecimiento.api.OfertaRequest
import ec.cleantok.abastecimiento.api.SolicitudDto
import ec.cleantok.abastecimiento.api.SolicitudItemDto
import ec.cleantok.abastecimiento.api.SolicitudProveedorDto
import ec.cleantok.abastecimiento.api.SolicitudResumenDto
import ec.cleantok.abastecimiento.dominio.EmpresaProveedorRepository
import ec.cleantok.abastecimiento.dominio.Entrega
import ec.cleantok.abastecimiento.dominio.EntregaRepository
import ec.cleantok.abastecimiento.dominio.EstadoOferta
import ec.cleantok.abastecimiento.dominio.EstadoOrden
import ec.cleantok.abastecimiento.dominio.EstadoSolicitud
import ec.cleantok.abastecimiento.dominio.EventoOrden
import ec.cleantok.abastecimiento.dominio.EventoOrdenRepository
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
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Zona
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.abastecimiento.dominio.Modalidad
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.ProveedorRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.notificacion.NotificacionService
import ec.cleantok.notificacion.TipoNotificacion
import org.springframework.data.domain.Pageable
import java.time.temporal.ChronoUnit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/** Ponderaciones de la recomendación (visibles para la empresa, como pide el flujo). */
object CriteriosOferta {
    const val ENTREGA = 0.40
    const val DISPONIBILIDAD = 0.30
    const val COSTO = 0.25
    const val PREFERIDO = 0.05
}

@Service
class SolicitudService(
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
    private val vinculos: EmpresaProveedorRepository,
    private val proveedores: ProveedorRepository,
    private val empresas: EmpresaRepository,
    private val productos: ProductoRepository,
    private val establecimientos: EstablecimientoRepository,
    private val codigos: Codigos,
    private val ordenService: OrdenCompraService,
    private val usuarios: UsuarioRepository,
    private val notificaciones: NotificacionService,
    private val fichas: FichaEmpresa,
    private val props: CleanTokProperties,
    private val clock: Clock,
) {
    // ---------------- Empresa (admin) ----------------

    @Transactional
    fun crear(req: CrearSolicitudRequest): SolicitudDto {
        val usuario = ContextoSeguridad.usuario()
        val empresaId = ContextoSeguridad.empresaId()
        val hoy = LocalDate.now(clock.withZone(Zona.ECUADOR))
        val fecha = req.fechaRequerida ?: hoy.plusDays(props.abastecimiento.diasEntrega.toLong())
        if (fecha.isBefore(hoy)) throw ValidacionException("La fecha requerida no puede estar en el pasado")

        val pedidas = req.lineas!!
        if (pedidas.map { it.productoId }.toSet().size != pedidas.size) throw ValidacionException("Hay productos repetidos")
        val prods = productos.findAllById(pedidas.map { it.productoId!! }).filter { it.empresaId == empresaId }.associateBy { it.id }
        pedidas.forEach { l ->
            val p = prods[l.productoId] ?: throw NoEncontradoException("Producto")
            if (!p.activo) throw ValidacionException("El producto \"${p.nombre}\" está desactivado")
        }

        val codigo = codigos.siguiente("SC", empresaId) { solicitudes.countByEmpresaId(empresaId) }
        val s = solicitudes.save(Solicitud(empresaId, codigo, fecha, req.nota?.trim()?.ifBlank { null }, usuario.id))
        // Consolida por nombre normalizado y unidad: "Jabón líquido" de todos los sitios es un solo producto a cotizar.
        pedidas.groupBy { prods.getValue(it.productoId!!).let { p -> p.nombreNormalizado to p.unidad } }.forEach { (_, grupo) ->
            val primero = prods.getValue(grupo.first().productoId!!)
            val total = grupo.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.cantidad) }
            val item = items.save(SolicitudItem(s.id, primero.nombre, primero.nombreNormalizado, primero.unidad, total))
            grupo.forEach { l ->
                val p = prods.getValue(l.productoId!!)
                lineas.save(SolicitudLinea(empresaId, s.id, item.id, p.establecimientoId, p.id, l.cantidad!!, null))
            }
        }
        if (req.enviar) enviar(s)
        return detalle(s)
    }

    @Transactional(readOnly = true)
    fun listar(): List<SolicitudResumenDto> = solicitudes.findAllByEmpresaIdOrderByCreatedAtDesc(ContextoSeguridad.empresaId()).map { s ->
        val ls = lineas.findAllBySolicitudId(s.id)
        SolicitudResumenDto(
            s.id, s.codigo, s.estado, s.fechaRequerida, Zona.presentar(s.createdAt), s.enviadaEn?.let(Zona::presentar),
            productos = ls.map { it.itemId }.toSet().size,
            destinos = ls.map { it.establecimientoId }.toSet().size,
            invitados = invitaciones.findAllBySolicitudId(s.id).size,
            ofertas = ofertas.findAllBySolicitudId(s.id).size,
        )
    }

    @Transactional(readOnly = true)
    fun obtener(id: UUID): SolicitudDto = detalle(deMiEmpresa(id))

    @Transactional
    fun enviar(id: UUID): SolicitudDto {
        val s = deMiEmpresa(id)
        enviar(s)
        return detalle(s)
    }

    private fun enviar(s: Solicitud) {
        if (s.estado != EstadoSolicitud.BORRADOR) throw ConflictoException("La solicitud ya fue enviada")
        val activos = proveedores.findAllById(vinculos.findAllByEmpresaId(s.empresaId).map { it.proveedorId }).filter { it.activo }
        if (activos.isEmpty()) throw ValidacionException("La empresa no tiene proveedores vinculados")
        activos.forEach { invitaciones.save(SolicitudProveedor(s.id, it.id)) }
        s.estado = EstadoSolicitud.EN_COTIZACION
        s.enviadaEn = clock.instant()
    }

    @Transactional
    fun cancelar(id: UUID): SolicitudDto {
        val s = deMiEmpresa(id)
        if (s.estado !in setOf(EstadoSolicitud.BORRADOR, EstadoSolicitud.EN_COTIZACION)) {
            throw ConflictoException("Solo se cancela una solicitud en borrador o en cotización")
        }
        s.estado = EstadoSolicitud.CANCELADA
        return detalle(s)
    }

    /**
     * Elige un proveedor por producto (o el recomendado) y emite una orden de compra por cada proveedor elegido,
     * con una entrega por destino. Si el proveedor no tiene todo, se cubren los destinos en orden hasta lo disponible.
     */
    @Transactional
    fun adjudicar(id: UUID, req: AdjudicarRequest): SolicitudDto {
        val usuario = ContextoSeguridad.usuario()
        val s = deMiEmpresa(id)
        if (s.estado != EstadoSolicitud.EN_COTIZACION) throw ConflictoException("La solicitud no está en cotización")
        val todasOfertas = ofertas.findAllBySolicitudId(s.id).associateBy { it.id }
        if (todasOfertas.isEmpty()) throw ValidacionException("Todavía no hay ofertas")
        val porItem = ofertaItems.findAllByOfertaIdIn(todasOfertas.keys).groupBy { it.itemId }
        val listaItems = items.findAllBySolicitudIdOrderByNombre(s.id)

        val elegidas: Map<UUID, OfertaItem> = if (req.asignaciones.isNullOrEmpty()) {
            listaItems.mapNotNull { item ->
                cotizaciones(item, porItem[item.id].orEmpty(), todasOfertas, s.empresaId).firstOrNull()
                    ?.let { c -> item.id to porItem.getValue(item.id).first { it.ofertaId == c.ofertaId } }
            }.toMap()
        } else {
            req.asignaciones.associate { a ->
                if (listaItems.none { it.id == a.itemId }) throw NoEncontradoException("Producto de la solicitud")
                val oi = porItem[a.itemId].orEmpty().firstOrNull { it.ofertaId == a.ofertaId }
                    ?: throw ValidacionException("Ese proveedor no ofertó ese producto")
                a.itemId!! to oi
            }
        }
        if (elegidas.isEmpty()) throw ValidacionException("Ningún producto tiene ofertas")

        val lineasPorItem = lineas.findAllBySolicitudId(s.id).groupBy { it.itemId }
        val nombresEst = establecimientos.findAllById(lineasPorItem.values.flatten().map { it.establecimientoId }.toSet())
            .associate { it.id to it.nombre }
        val ahora = clock.instant()

        elegidas.entries.groupBy { it.value.ofertaId }.forEach { (ofertaId, asignadas) ->
            val oferta = todasOfertas.getValue(ofertaId)
            // Reparte lo disponible entre los destinos, en orden alfabético.
            val cubiertas = asignadas.flatMap { (itemId, oi) ->
                var disponible = oi.cantidadDisponible
                lineasPorItem[itemId].orEmpty().sortedBy { nombresEst[it.establecimientoId] }.mapNotNull { l ->
                    val cantidad = l.cantidad.min(disponible)
                    disponible = disponible.subtract(cantidad)
                    if (cantidad.signum() > 0) Triple(l, cantidad, oi.precioUnitario) else null
                }
            }
            if (cubiertas.isEmpty()) return@forEach
            val total = cubiertas.fold(BigDecimal.ZERO) { acc, (_, c, p) -> acc.add(c.multiply(p)) }.setScale(2, RoundingMode.HALF_UP)
            val codigo = codigos.siguiente("OC", s.empresaId) { ordenes.countByEmpresaId(s.empresaId) }
            val orden = ordenes.save(
                OrdenCompra(s.empresaId, oferta.proveedorId, s.id, oferta.id, codigo, oferta.modalidad, total, oferta.plazoDias, usuario.id),
            )
            val destinos = cubiertas.map { it.first.establecimientoId }.distinct().sortedBy { nombresEst[it] }
            val porDestino = destinos.mapIndexed { i, estId -> estId to entregas.save(Entrega(s.empresaId, orden.id, estId, i + 1)) }.toMap()
            cubiertas.forEach { (l, cantidad, precio) ->
                val unidad = items.findById(l.itemId).get().unidad
                ordenLineas.save(OrdenLinea(orden.id, porDestino.getValue(l.establecimientoId).id, l.id, l.productoId, cantidad, unidad, precio))
            }
            eventos.save(
                EventoOrden(
                    orden.id, null, EstadoOrden.APROBADA.name, OrdenCompraService.titulo(EstadoOrden.APROBADA),
                    "${destinos.size} destino(s) · total USD ${total.toPlainString()}", usuario.id, ahora,
                ),
            )
            oferta.estado = EstadoOferta.ADJUDICADA
        }
        todasOfertas.values.filter { it.estado == EstadoOferta.ENVIADA }.forEach { it.estado = EstadoOferta.NO_ADJUDICADA }
        s.estado = EstadoSolicitud.ADJUDICADA
        s.adjudicadaEn = ahora
        return detalle(s)
    }

    private fun deMiEmpresa(id: UUID): Solicitud =
        solicitudes.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Solicitud")

    private fun detalle(s: Solicitud): SolicitudDto {
        val todasOfertas = ofertas.findAllBySolicitudId(s.id).associateBy { it.id }
        val ofItems = ofertaItems.findAllByOfertaIdIn(todasOfertas.keys)
        val porItem = ofItems.groupBy { it.itemId }
        val ls = lineas.findAllBySolicitudId(s.id).groupBy { it.itemId }
        val nombresEst = establecimientos.findAllById(ls.values.flatten().map { it.establecimientoId }.toSet()).associate { it.id to it.nombre }
        val provs = proveedores.findAllById(
            invitaciones.findAllBySolicitudId(s.id).map { it.proveedorId } + todasOfertas.values.map { it.proveedorId },
        ).associateBy { it.id }
        return SolicitudDto(
            id = s.id,
            codigo = s.codigo,
            estado = s.estado,
            fechaRequerida = s.fechaRequerida,
            nota = s.nota,
            creadaEn = Zona.presentar(s.createdAt),
            enviadaEn = s.enviadaEn?.let(Zona::presentar),
            adjudicadaEn = s.adjudicadaEn?.let(Zona::presentar),
            items = items.findAllBySolicitudIdOrderByNombre(s.id).map { item ->
                SolicitudItemDto(
                    id = item.id,
                    nombre = item.nombre,
                    unidad = item.unidad,
                    cantidad = item.cantidad,
                    destinos = ls[item.id].orEmpty().sortedBy { nombresEst[it.establecimientoId] }.map { l ->
                        DestinoDto(l.id, RefDto(l.establecimientoId, nombresEst[l.establecimientoId] ?: "—"), l.productoId, l.cantidad, l.cantidadRecomendada)
                    },
                    cotizaciones = cotizaciones(item, porItem[item.id].orEmpty(), todasOfertas, s.empresaId),
                )
            },
            invitados = invitaciones.findAllBySolicitudId(s.id).mapNotNull { provs[it.proveedorId] }.sortedBy { it.nombre }.map { RefDto(it.id, it.nombre) },
            ofertas = todasOfertas.values.sortedBy { it.enviadaEn }.map { o ->
                ofertaDto(o, ofItems.filter { it.ofertaId == o.id }, provs[o.proveedorId]?.nombre ?: "—", items.findAllBySolicitudIdOrderByNombre(s.id))
            },
            ordenes = ordenService.resumenesDeSolicitud(s.id),
        )
    }

    /**
     * Puntaje 0–100 por oferta: entrega 40 % (el plazo más corto vale 1), disponibilidad 30 % (qué parte de lo pedido cubre),
     * costo 25 % (el precio unitario más bajo vale 1) y proveedor preferido 5 %. Ordenadas de mayor a menor; empate → más barata.
     */
    private fun cotizaciones(item: SolicitudItem, ofertados: List<OfertaItem>, todas: Map<UUID, Oferta>, empresaId: UUID): List<CotizacionDto> {
        if (ofertados.isEmpty()) return emptyList()
        val minPlazo = ofertados.minOf { todas.getValue(it.ofertaId).plazoDias }
        val minPrecio = ofertados.minOf { it.precioUnitario }
        val preferidos = vinculos.findAllByEmpresaId(empresaId).filter { it.preferido }.map { it.proveedorId }.toSet()
        val nombres = proveedores.findAllById(ofertados.map { todas.getValue(it.ofertaId).proveedorId }).associate { it.id to it.nombre }
        val lista = ofertados.map { oi ->
            val o = todas.getValue(oi.ofertaId)
            val cubre = oi.cantidadDisponible.min(item.cantidad)
            val entrega = (minPlazo + 1.0) / (o.plazoDias + 1.0)
            val disponibilidad = cubre.divide(item.cantidad, 4, RoundingMode.HALF_UP).toDouble()
            val costo = minPrecio.divide(oi.precioUnitario, 4, RoundingMode.HALF_UP).toDouble()
            val preferido = o.proveedorId in preferidos
            val puntaje = 100 * (
                CriteriosOferta.ENTREGA * entrega + CriteriosOferta.DISPONIBILIDAD * disponibilidad +
                    CriteriosOferta.COSTO * costo + CriteriosOferta.PREFERIDO * (if (preferido) 1.0 else 0.0)
                )
            CotizacionDto(
                ofertaId = o.id,
                proveedor = RefDto(o.proveedorId, nombres[o.proveedorId] ?: "—"),
                precioUnitario = oi.precioUnitario,
                cantidadDisponible = oi.cantidadDisponible,
                total = cubre.multiply(oi.precioUnitario).setScale(2, RoundingMode.HALF_UP),
                completo = oi.cantidadDisponible >= item.cantidad,
                plazoDias = o.plazoDias,
                modalidad = o.modalidad,
                preferido = preferido,
                puntaje = puntaje.roundToInt(),
                recomendada = false,
            )
        }.sortedWith(compareByDescending<CotizacionDto> { it.puntaje }.thenBy { it.precioUnitario })
        return lista.mapIndexed { i, c -> if (i == 0) c.copy(recomendada = true) else c }
    }

    private fun ofertaDto(o: Oferta, its: List<OfertaItem>, proveedor: String, itemsSolicitud: List<SolicitudItem>): OfertaDto {
        val cantidades = itemsSolicitud.associate { it.id to it.cantidad }
        val total = its.fold(BigDecimal.ZERO) { acc, oi ->
            acc.add(oi.cantidadDisponible.min(cantidades[oi.itemId] ?: oi.cantidadDisponible).multiply(oi.precioUnitario))
        }.setScale(2, RoundingMode.HALF_UP)
        return OfertaDto(
            o.id, RefDto(o.proveedorId, proveedor), o.modalidad, o.plazoDias, o.nota, o.estado, Zona.presentar(o.enviadaEn), total,
            its.map { OfertaItemDto(it.itemId, it.cantidadDisponible, it.precioUnitario) },
        )
    }

    // ---------------- Proveedor ----------------

    @Transactional(readOnly = true)
    fun invitadas(): List<SolicitudProveedorDto> = solicitudes.invitadas(ContextoSeguridad.proveedorId()).map { paraProveedor(it) }

    @Transactional(readOnly = true)
    fun obtenerComoProveedor(id: UUID): SolicitudProveedorDto = paraProveedor(invitada(id))

    /**
     * Crea o reemplaza mi oferta mientras la solicitud está en cotización y avisa a los administradores de la empresa.
     * El plazo no lo elige el proveedor: son los días hasta la fecha requerida que fijó la empresa.
     */
    @Transactional
    fun ofertar(id: UUID, req: OfertaRequest): SolicitudProveedorDto {
        val usuario = ContextoSeguridad.usuario()
        val proveedorId = ContextoSeguridad.proveedorId()
        val s = invitada(id)
        if (s.estado != EstadoSolicitud.EN_COTIZACION) throw ConflictoException("La solicitud ya no recibe ofertas")
        val proveedor = proveedores.findById(proveedorId).orElseThrow { NoEncontradoException("Proveedor") }
        val previa = ofertas.findBySolicitudIdAndProveedorId(s.id, proveedorId)
        val propias = proveedor.modalidades()
        val modalidad = req.modalidad ?: previa?.modalidad?.takeIf { it in propias }
            ?: Modalidad.ENVIO_DIRECTO.takeIf { it in propias } ?: propias.first()
        if (modalidad !in propias) throw ValidacionException("Tu empresa no tiene configurada esa modalidad de entrega")
        val plazo = plazoHasta(s)
        val validos = items.findAllBySolicitudIdOrderByNombre(s.id).map { it.id }.toSet()
        val ofertados = req.items!!
        if (ofertados.any { it.itemId !in validos }) throw NoEncontradoException("Producto de la solicitud")
        if (ofertados.map { it.itemId }.toSet().size != ofertados.size) throw ValidacionException("Hay productos repetidos")

        val ahora = clock.instant()
        val oferta = previa?.also {
            it.modalidad = modalidad
            it.plazoDias = plazo
            it.nota = req.nota?.trim()?.ifBlank { null }
            it.usuarioId = usuario.id
            it.enviadaEn = ahora
            ofertaItems.deleteAllByOfertaId(it.id)
            ofertaItems.flush()
        } ?: ofertas.save(Oferta(s.id, proveedorId, modalidad, plazo, req.nota?.trim()?.ifBlank { null }, usuario.id, ahora))
        val guardados = ofertados.map { ofertaItems.save(OfertaItem(oferta.id, it.itemId!!, it.cantidadDisponible!!, it.precioUnitario!!)) }
        avisarOferta(s, oferta, guardados, proveedor.nombre, nueva = previa == null)
        return paraProveedor(s)
    }

    /** Días hasta la fecha requerida (0 si ya es hoy), dentro del rango que admite una oferta. */
    private fun plazoHasta(s: Solicitud): Int =
        ChronoUnit.DAYS.between(LocalDate.now(clock.withZone(Zona.ECUADOR)), s.fechaRequerida).coerceIn(0, 60).toInt()

    /** Notificación a cada administrador activo de la empresa: "te cotizaron". */
    private fun avisarOferta(s: Solicitud, oferta: Oferta, its: List<OfertaItem>, proveedor: String, nueva: Boolean) {
        val admins = usuarios.findAllByEmpresaId(s.empresaId, Pageable.unpaged()).content.filter { it.rol == Rol.ADMIN_EMPRESA && it.activo }.map { it.id }
        val total = ofertaDto(oferta, its, proveedor, items.findAllBySolicitudIdOrderByNombre(s.id)).total
        val productos = its.size
        notificaciones.avisar(
            admins,
            TipoNotificacion.OFERTA_RECIBIDA,
            titulo = if (nueva) "$proveedor te envió una cotización" else "$proveedor actualizó su cotización",
            mensaje = "${s.codigo}: $productos producto${if (productos == 1) "" else "s"} por USD ${total.toPlainString()}. Compárala en Compras.",
            referenciaTipo = "SOLICITUD",
            referenciaId = s.id,
            referenciaTexto = s.codigo,
        )
    }

    private fun invitada(id: UUID): Solicitud {
        val s = solicitudes.findById(id).orElse(null)
        if (s == null || !invitaciones.existsBySolicitudIdAndProveedorId(id, ContextoSeguridad.proveedorId())) throw NoEncontradoException("Solicitud")
        return s
    }

    private fun paraProveedor(s: Solicitud): SolicitudProveedorDto {
        val proveedorId = ContextoSeguridad.proveedorId()
        val proveedor = proveedores.findById(proveedorId).orElseThrow { NoEncontradoException("Proveedor") }
        val ls = lineas.findAllBySolicitudId(s.id).groupBy { it.itemId }
        val ests = establecimientos.findAllById(ls.values.flatten().map { it.establecimientoId }.toSet()).associateBy { it.id }
        val listaItems = items.findAllBySolicitudIdOrderByNombre(s.id)
        val mia = ofertas.findBySolicitudIdAndProveedorId(s.id, proveedorId)
        return SolicitudProveedorDto(
            id = s.id,
            codigo = s.codigo,
            empresa = RefDto(s.empresaId, empresas.findById(s.empresaId).get().nombre),
            estado = s.estado,
            fechaRequerida = s.fechaRequerida,
            nota = s.nota,
            enviadaEn = s.enviadaEn?.let(Zona::presentar),
            items = listaItems.map { item ->
                ItemProveedorDto(
                    item.id, item.nombre, item.unidad, item.cantidad,
                    ls[item.id].orEmpty().map { l ->
                        val e = ests.getValue(l.establecimientoId)
                        DestinoProveedorDto(RefDto(e.id, e.nombre), e.direccion, l.cantidad)
                    }.sortedBy { it.establecimiento.nombre },
                )
            },
            miOferta = mia?.let { ofertaDto(it, ofertaItems.findAllByOfertaIdIn(listOf(it.id)), proveedor.nombre, listaItems) },
            modalidadesDisponibles = proveedor.modalidades(),
            puedeOfertar = s.estado == EstadoSolicitud.EN_COTIZACION,
            empresaContacto = fichas.de(s.empresaId),
            plazoDias = plazoHasta(s),
        )
    }
}
