package ec.cleantok.inventario.servicio

import ec.cleantok.archivos.servicio.ArchivoService
import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.comun.error.ApiException
import ec.cleantok.comun.error.CodigoError
import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.Ubicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.servicio.AccesoEstablecimiento
import ec.cleantok.inventario.api.AlertaResumenDto
import ec.cleantok.inventario.api.ConteoRequest
import ec.cleantok.inventario.api.ConteoResultadoDto
import ec.cleantok.inventario.api.MovimientoRequest
import ec.cleantok.inventario.api.MovimientoResultadoDto
import ec.cleantok.inventario.api.SaldoDto
import ec.cleantok.inventario.dominio.Movimiento
import ec.cleantok.inventario.dominio.MovimientoRepository
import ec.cleantok.inventario.dominio.SentidoAjuste
import ec.cleantok.inventario.dominio.Stock
import ec.cleantok.inventario.dominio.StockRepository
import ec.cleantok.inventario.dominio.TipoMovimiento
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Lo implementa el módulo de monitoreo: evalúa alertas dentro de la misma transacción del movimiento
 * y devuelve las que abrió.
 */
fun interface ReaccionMovimiento {
    fun alRegistrar(movimiento: Movimiento, producto: Producto): List<AlertaResumenDto>
}

@Service
class MovimientoService(
    private val productos: ProductoRepository,
    private val ubicaciones: UbicacionRepository,
    private val stocks: StockRepository,
    private val movimientos: MovimientoRepository,
    private val acceso: AccesoEstablecimiento,
    private val mapper: MovimientoMapper,
    private val archivos: ArchivoService,
    private val reacciones: List<ReaccionMovimiento>,
    private val props: CleanTokProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val txLectura = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /**
     * Registra un movimiento de forma idempotente: si el clientUuid ya existe devuelve el resultado
     * original con `duplicado = true`, sin volver a aplicarlo (también si dos peticiones iguales llegan a la vez).
     * [esRecepcion]: INGRESO que viene de recibir un pedido (la cantidad la acota la orden), permitido a quien recibe.
     */
    fun registrar(req: MovimientoRequest, esRecepcion: Boolean = false): MovimientoResultadoDto {
        val usuario = ContextoSeguridad.usuario()
        val clientUuid = req.clientUuid!!
        txLectura.execute { existente(clientUuid, usuario) }?.let { return it }
        return try {
            tx.execute { aplicar(req, usuario, esRecepcion = esRecepcion) }!!
        } catch (e: DataIntegrityViolationException) {
            // Carrera con otra petición del mismo clientUuid: la otra ganó.
            txLectura.execute { existente(clientUuid, usuario) } ?: throw e
        }
    }

    fun obtener(id: UUID): MovimientoResultadoDto = txLectura.execute {
        val m = movimientos.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Movimiento")
        acceso.visible(m.establecimientoId)
        resultado(m, duplicado = false, sugerir = false, alertas = emptyList())
    }!!

    private fun existente(clientUuid: UUID, usuario: UsuarioAutenticado): MovimientoResultadoDto? {
        val m = movimientos.findByClientUuid(clientUuid) ?: return null
        if (m.empresaId != usuario.empresaId) throw ConflictoException("El clientUuid ya fue usado")
        return resultado(m, duplicado = true, sugerir = false, alertas = emptyList())
    }

    /**
     * Conteo físico de una ubicación. Se bloquea el saldo antes de calcular la diferencia y todo va en una
     * transacción, así un movimiento simultáneo no lo descuadra.
     * - Bodega (solo el admin): un AJUSTE por la diferencia.
     * - Piso: lo que sube sale de la bodega (TRASLADO, falla con STOCK_INSUFICIENTE si no alcanza) y lo que
     *   baja se registra como CONSUMO. Así un piso nunca suma material que no salió de la bodega.
     */
    fun contar(ubicacionId: UUID, req: ConteoRequest): ConteoResultadoDto {
        val usuario = ContextoSeguridad.usuario()
        val ocurridoEn = OffsetDateTime.now(clock)
        return tx.execute {
            val ubicacion = acceso.ubicacionVisible(ubicacionId)
            if (ubicacion.esBodega && !usuario.gestionaSede) throw soloAdminEnBodega()
            var sinCambio = 0
            val ajustes = req.items!!.distinctBy { it.productoId }.mapNotNull { item ->
                val producto = productos.findByIdAndEmpresaId(item.productoId!!, usuario.empresaId!!)
                    ?.takeIf { it.establecimientoId == ubicacion.establecimientoId }
                    ?: throw NoEncontradoException("Producto")
                stocks.asegurar(UUID.randomUUID(), producto.empresaId, producto.establecimientoId, producto.id, ubicacion.id)
                val actual = stocks.bloquear(producto.id, ubicacion.id).cantidad
                val diferencia = item.cantidad!!.setScale(3, RoundingMode.HALF_UP).subtract(actual)
                if (diferencia.signum() == 0) {
                    sinCambio++
                    return@mapNotNull null
                }
                val sube = diferencia.signum() > 0
                val mov = MovimientoRequest(
                    clientUuid = UUID.randomUUID(),
                    tipo = when {
                        ubicacion.esBodega -> TipoMovimiento.AJUSTE
                        sube -> TipoMovimiento.TRASLADO
                        else -> TipoMovimiento.CONSUMO
                    },
                    productoId = producto.id,
                    ubicacionId = ubicacion.id,
                    cantidad = diferencia.abs(),
                    unidad = producto.unidad,
                    ocurridoEn = ocurridoEn,
                    nota = req.nota?.trim()?.ifBlank { null } ?: MOTIVO_CONTEO,
                    motivo = MOTIVO_CONTEO,
                    sentido = if (sube) SentidoAjuste.ENTRADA else SentidoAjuste.SALIDA,
                )
                try {
                    aplicar(mov, usuario, esConteo = true)
                } catch (e: ApiException) {
                    if (e.codigo != CodigoError.STOCK_INSUFICIENTE) throw e
                    // Que el mensaje diga qué producto no alcanza, no solo el saldo de la bodega.
                    throw ApiException(
                        e.status, e.codigo,
                        "No hay suficiente \"${producto.nombre}\" en bodega para dejar ${item.cantidad!!.stripTrailingZeros().toPlainString()} " +
                            "${producto.unidad} en ${ubicacion.nombre}: en bodega hay ${(e.extras["disponible"] as java.math.BigDecimal).stripTrailingZeros().toPlainString()}",
                        e.extras,
                    )
                }
            }
            ConteoResultadoDto(ajustes.size, sinCambio, ajustes.map { it.movimiento }, ajustes.flatMap { it.alertas })
        }!!
    }

    private fun aplicar(req: MovimientoRequest, usuario: UsuarioAutenticado, esConteo: Boolean = false, esRecepcion: Boolean = false): MovimientoResultadoDto {
        val tipo = req.tipo!!
        if (tipo == TipoMovimiento.AJUSTE && !usuario.gestionaSede && !esConteo) {
            throw ApiException(HttpStatus.FORBIDDEN, CodigoError.NO_AUTORIZADO, "Solo el administrador o el supervisor de la sede puede registrar ajustes")
        }
        val ubicacion = acceso.ubicacionVisible(req.ubicacionId!!)
        if (!ubicacion.activo) throw ValidacionException("La ubicación está desactivada")
        val producto = productos.findByIdAndEmpresaId(req.productoId!!, usuario.empresaId!!)
            ?.takeIf { it.establecimientoId == ubicacion.establecimientoId }
            ?: throw NoEncontradoException("Producto")
        if (!producto.activo) {
            throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, CodigoError.PRODUCTO_INACTIVO, "El producto \"${producto.nombre}\" está desactivado")
        }
        // El stock de la bodega solo lo carga el admin (orden de compra, recepción o ingreso). El encargado
        // mueve entre bodega y pisos (traslado, devolución) y registra consumos.
        if (tipo == TipoMovimiento.INGRESO && !usuario.gestionaSede && !esRecepcion) throw soloAdminEnBodega()

        val ahora = clock.instant()
        val ocurridoEn = req.ocurridoEn!!.toInstant()
        if (ocurridoEn.isAfter(ahora.plus(TOLERANCIA_FUTURO))) {
            throw ValidacionException("La fecha del movimiento está en el futuro; revisa la hora del dispositivo")
        }

        val unidadOriginal = req.unidad ?: producto.unidad
        val cantidad = unidadOriginal.convertir(req.cantidad!!, producto.unidad)
        if (cantidad.signum() <= 0) {
            throw ValidacionException("La cantidad convertida a ${producto.unidad} es menor que 0,001")
        }

        val (origen, destino) = rutas(tipo, ubicacion, req)
        archivos.exigirFoto(req.fotoId)

        // Bloqueo en orden fijo (por id) para que traslados y devoluciones simultáneos no se bloqueen entre sí.
        val afectadas = listOfNotNull(origen, destino).sortedBy { it.id }
        val saldos: Map<UUID, Stock> = afectadas.associate { u ->
            stocks.asegurar(UUID.randomUUID(), producto.empresaId, producto.establecimientoId, producto.id, u.id)
            u.id to stocks.bloquear(producto.id, u.id)
        }

        origen?.let { u ->
            val s = saldos.getValue(u.id)
            if (s.cantidad < cantidad) {
                throw ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    CodigoError.STOCK_INSUFICIENTE,
                    "Stock insuficiente en ${u.nombre}: hay ${s.cantidad.stripTrailingZeros().toPlainString()} ${producto.unidad}",
                    mapOf("disponible" to s.cantidad, "unidad" to producto.unidad.name, "ubicacionId" to u.id, "productoId" to producto.id),
                )
            }
            s.cantidad = s.cantidad.subtract(cantidad)
        }
        destino?.let { u -> saldos.getValue(u.id).let { it.cantidad = it.cantidad.add(cantidad) } }

        val m = movimientos.saveAndFlush(
            Movimiento(
                empresaId = producto.empresaId,
                establecimientoId = producto.establecimientoId,
                clientUuid = req.clientUuid!!,
                tipo = tipo,
                productoId = producto.id,
                ubicacionOrigenId = origen?.id,
                ubicacionDestinoId = destino?.id,
                cantidad = cantidad,
                unidad = producto.unidad,
                cantidadOriginal = req.cantidad!!.setScale(3),
                unidadOriginal = unidadOriginal,
                usuarioId = usuario.id,
                ocurridoEn = ocurridoEn,
                registradoEn = ahora,
                nota = req.nota?.trim()?.ifBlank { null },
                motivo = req.motivo?.trim()?.ifBlank { null },
                fotoId = req.fotoId,
                saldoResultanteOrigen = origen?.let { saldos.getValue(it.id).cantidad },
                saldoResultanteDestino = destino?.let { saldos.getValue(it.id).cantidad },
            ),
        )
        val alertas = reacciones.flatMap { it.alRegistrar(m, producto) }
        return resultado(m, duplicado = false, sugerir = sugerirReporteNivel(m), alertas = alertas)
    }

    /** Traduce (tipo, ubicación escaneada) a (origen, destino). */
    private fun rutas(tipo: TipoMovimiento, u: Ubicacion, req: MovimientoRequest): Pair<Ubicacion?, Ubicacion?> {
        fun bodega() = ubicaciones.findByEstablecimientoIdAndTipo(u.establecimientoId, TipoUbicacion.BODEGA)!!
        return when (tipo) {
            TipoMovimiento.INGRESO -> {
                if (!u.esBodega) throw ValidacionException("Los ingresos se registran con el QR de la bodega")
                null to u
            }
            TipoMovimiento.TRASLADO -> {
                if (u.esBodega) throw ValidacionException("El traslado se registra con el QR del piso que recibe el producto")
                bodega() to u
            }
            TipoMovimiento.CONSUMO -> {
                if (u.esBodega) throw ValidacionException("El consumo se registra con el QR del piso donde se usó")
                u to null
            }
            TipoMovimiento.DEVOLUCION -> {
                if (u.esBodega) throw ValidacionException("La devolución se registra con el QR del piso que devuelve")
                u to bodega()
            }
            TipoMovimiento.AJUSTE -> {
                if (req.motivo.isNullOrBlank()) throw ValidacionException("El motivo es obligatorio en un ajuste")
                when (req.sentido ?: throw ValidacionException("Indica el sentido del ajuste: ENTRADA o SALIDA")) {
                    SentidoAjuste.ENTRADA -> null to u
                    SentidoAjuste.SALIDA -> u to null
                }
            }
        }
    }

    /** Tras un CONSUMO: el saldo del piso quedó en el umbral (25 % por defecto) del último traslado o menos. */
    private fun sugerirReporteNivel(m: Movimiento): Boolean {
        if (m.tipo != TipoMovimiento.CONSUMO) return false
        val ultimo = movimientos.findFirstByProductoIdAndUbicacionDestinoIdAndTipoOrderBySecuenciaDesc(
            m.productoId, m.ubicacionOrigenId!!, TipoMovimiento.TRASLADO,
        ) ?: return false
        return m.saldoResultanteOrigen!! <= ultimo.cantidad.multiply(props.nivel.umbralSugerencia)
    }

    private fun resultado(m: Movimiento, duplicado: Boolean, sugerir: Boolean, alertas: List<AlertaResumenDto>): MovimientoResultadoDto {
        val dto = mapper.dto(m)
        val saldos = listOfNotNull(
            dto.origen?.let { SaldoDto(it, m.saldoResultanteOrigen!!) },
            dto.destino?.let { SaldoDto(it, m.saldoResultanteDestino!!) },
        )
        return MovimientoResultadoDto(dto, saldos, duplicado, sugerir, alertas)
    }

    private fun soloAdminEnBodega() =
        ApiException(HttpStatus.FORBIDDEN, CodigoError.NO_AUTORIZADO, "Solo administración puede cambiar el stock de la bodega")

    companion object {
        private val TOLERANCIA_FUTURO: Duration = Duration.ofMinutes(10)
        const val MOTIVO_CONTEO = "Conteo físico"
    }
}

