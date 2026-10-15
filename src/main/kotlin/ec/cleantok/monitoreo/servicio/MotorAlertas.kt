package ec.cleantok.monitoreo.servicio

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.inventario.dominio.MovimientoRepository
import ec.cleantok.inventario.dominio.StockRepository
import ec.cleantok.inventario.dominio.TipoMovimiento
import ec.cleantok.inventario.servicio.InventarioService
import ec.cleantok.inventario.servicio.PronosticoConsumo
import ec.cleantok.monitoreo.dominio.Alerta
import ec.cleantok.monitoreo.dominio.AlertaRepository
import ec.cleantok.monitoreo.dominio.CierreAlerta
import ec.cleantok.monitoreo.dominio.EstadoAlerta
import ec.cleantok.monitoreo.dominio.ReporteNivelRepository
import ec.cleantok.monitoreo.dominio.Severidad
import ec.cleantok.monitoreo.dominio.TipoAlerta
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Punto de extensión para notificaciones push (Firebase, correo…). Hoy solo deja constancia en el log. */
interface Notificador {
    fun alertaAbierta(alerta: Alerta, producto: Producto)
}

@Component
class NotificadorLog : Notificador {
    private val log = LoggerFactory.getLogger(javaClass)

    // Solo identificadores: nada de nombres ni datos personales en los logs.
    override fun alertaAbierta(alerta: Alerta, producto: Producto) {
        log.info("Alerta abierta tipo={} severidad={} alerta={} producto={}", alerta.tipo, alerta.severidad, alerta.id, producto.id)
    }
}

/** Condición vigente de una alerta: severidad y mensaje. null = la condición no se cumple. */
private data class Condicion(val severidad: Severidad, val mensaje: String)

/**
 * Reglas de alerta. Se evalúan tras cada movimiento o reporte de nivel (en su misma transacción)
 * y cada hora en el job. No dependen del usuario autenticado.
 *
 * - SIN_STOCK (ALTA): bodega en 0 en un consumible activo que ya tuvo algún ingreso.
 * - STOCK_BAJO (MEDIA): bodega ≤ stock mínimo (si está configurado y no aplica SIN_STOCK).
 * - COBERTURA_BAJA (MEDIA; ALTA si < 2 días): días de cobertura < CLEANTOK_DIAS_COBERTURA_MIN.
 * - NIVEL_CRITICO (por piso; ALTA si 0 %, MEDIA si 25 %): último reporte ≤ 25 % sin traslado posterior.
 *
 * Una sola alerta abierta por (tipo, producto, ubicación); se cierra sola cuando la condición desaparece.
 * Una alerta cerrada a mano no se reabre hasta que la condición haya desaparecido al menos una vez
 * (o, en NIVEL_CRITICO, hasta que llegue un reporte nuevo).
 */
@Service
class MotorAlertas(
    private val alertas: AlertaRepository,
    private val stocks: StockRepository,
    private val ubicaciones: UbicacionRepository,
    private val movimientos: MovimientoRepository,
    private val reportes: ReporteNivelRepository,
    private val inventario: InventarioService,
    private val pronostico: PronosticoConsumo,
    private val notificador: Notificador,
    private val jdbc: JdbcTemplate,
    private val props: CleanTokProperties,
) {
    /** Evalúa todas las reglas del producto y devuelve las alertas que se abrieron ahora. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun evaluar(p: Producto, ahora: Instant): List<Alerta> {
        // Serializa la evaluación por producto: dos movimientos simultáneos no abren la misma alerta dos veces.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { _ -> }, p.id.toString())
        if (!p.activo || !p.esConsumible) return emptyList()

        val bodega = ubicaciones.findByEstablecimientoIdAndTipo(p.establecimientoId, TipoUbicacion.BODEGA)!!
        val saldos = stocks.findAllByProductoId(p.id)
        val stockBodega = saldos.firstOrNull { it.ubicacionId == bodega.id }?.cantidad ?: BigDecimal.ZERO
        val stockTotal = saldos.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.cantidad) }
        val tuvoIngreso = movimientos.existsByProductoIdAndTipo(p.id, TipoMovimiento.INGRESO)
        val quien = quienRepone(p.abastece)
        val nuevas = mutableListOf<Alerta>()

        val sinStock = tuvoIngreso && stockBodega.signum() == 0
        aplicar(TipoAlerta.SIN_STOCK, p, bodega.id, ahora, nuevas) {
            if (sinStock) Condicion(Severidad.ALTA, "Sin stock de ${p.nombre} en bodega. $quien") else null
        }

        aplicar(TipoAlerta.STOCK_BAJO, p, bodega.id, ahora, nuevas) {
            val minimo = p.stockMinimo
            if (!sinStock && tuvoIngreso && minimo != null && stockBodega <= minimo) {
                Condicion(Severidad.MEDIA, "Stock bajo de ${p.nombre} en bodega: quedan ${cantidad(stockBodega)} ${p.unidad} (mínimo ${cantidad(minimo)}). $quien")
            } else {
                null
            }
        }

        val diario = pronostico.pronosticar(p.establecimientoId, ahora)[p.id]?.consumoDiario
        val dias = inventario.cobertura(stockBodega, stockTotal, diario).diasCobertura
        val minimoDias = BigDecimal.valueOf(props.diasCoberturaMin.toLong())
        aplicar(TipoAlerta.COBERTURA_BAJA, p, null, ahora, nuevas) {
            if (dias != null && dias < minimoDias) {
                val sev = if (dias < BigDecimal.valueOf(2)) Severidad.ALTA else Severidad.MEDIA
                Condicion(sev, "${p.nombre}: el stock alcanza para unos ${cantidad(dias)} días (mínimo ${props.diasCoberturaMin}). $quien")
            } else {
                null
            }
        }

        val pisos = (reportes.ubicacionesConReportes(p.id) + alertas.ubicacionesConAbiertas(p.id, TipoAlerta.NIVEL_CRITICO)).toSet()
        pisos.forEach { pisoId ->
            val ultimo = reportes.findFirstByProductoIdAndUbicacionIdOrderByOcurridoEnDescRegistradoEnDesc(p.id, pisoId)
            val repuesto = ultimo != null && movimientos.existsByProductoIdAndUbicacionDestinoIdAndTipoInAndOcurridoEnAfter(
                p.id, pisoId, listOf(TipoMovimiento.TRASLADO, TipoMovimiento.AJUSTE), ultimo.ocurridoEn,
            )
            aplicar(TipoAlerta.NIVEL_CRITICO, p, pisoId, ahora, nuevas, eventoPosterior = ultimo?.registradoEn) {
                if (ultimo != null && ultimo.nivelPct <= 25 && !repuesto) {
                    val piso = ubicaciones.findById(pisoId).map { it.nombre }.orElse("piso")
                    val sev = if (ultimo.nivelPct == 0) Severidad.ALTA else Severidad.MEDIA
                    Condicion(sev, "${p.nombre} en $piso: nivel reportado ${ultimo.nivelPct} %. Trasladar desde bodega. $quien")
                } else {
                    null
                }
            }
        }
        return nuevas
    }

    private fun aplicar(
        tipo: TipoAlerta,
        p: Producto,
        ubicacionId: UUID?,
        ahora: Instant,
        nuevas: MutableList<Alerta>,
        eventoPosterior: Instant? = null,
        condicion: () -> Condicion?,
    ) {
        val abierta = if (ubicacionId == null) {
            alertas.findByTipoAndProductoIdAndUbicacionIdIsNullAndEstado(tipo, p.id, EstadoAlerta.ABIERTA)
        } else {
            alertas.findByTipoAndProductoIdAndUbicacionIdAndEstado(tipo, p.id, ubicacionId, EstadoAlerta.ABIERTA)
        }
        val c = condicion()
        if (c == null) {
            abierta?.cerrar(CierreAlerta.AUTOMATICO, ahora)
            if (ubicacionId == null) alertas.rearmarSinUbicacion(tipo, p.id) else alertas.rearmar(tipo, p.id, ubicacionId)
            return
        }
        if (abierta != null) {
            abierta.severidad = c.severidad
            abierta.mensaje = c.mensaje
            return
        }
        val ultima = if (ubicacionId == null) {
            alertas.findFirstByTipoAndProductoIdAndUbicacionIdIsNullOrderByCreadaEnDesc(tipo, p.id)
        } else {
            alertas.findFirstByTipoAndProductoIdAndUbicacionIdOrderByCreadaEnDesc(tipo, p.id, ubicacionId)
        }
        val silenciada = ultima != null && ultima.cierre == CierreAlerta.MANUAL && !ultima.rearmada &&
            !(eventoPosterior != null && ultima.atendidaEn != null && eventoPosterior.isAfter(ultima.atendidaEn))
        if (silenciada) return
        val a = alertas.save(Alerta(p.empresaId, p.establecimientoId, p.id, ubicacionId, tipo, c.severidad, c.mensaje, ahora))
        nuevas += a
        notificador.alertaAbierta(a, p)
    }

    companion object {
        fun quienRepone(a: Abastece): String = when (a) {
            Abastece.ENTIDAD_CONTRATANTE -> "Lo provee la entidad contratante."
            Abastece.EMPRESA_LIMPIEZA -> "Lo compra la empresa de limpieza."
        }

        private fun cantidad(b: BigDecimal): String = b.stripTrailingZeros().toPlainString().replace('.', ',')
    }
}
