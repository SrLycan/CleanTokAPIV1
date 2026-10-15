package ec.cleantok.inventario.servicio

import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Zona
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.servicio.AccesoEstablecimiento
import ec.cleantok.inventario.api.EstadoInventario
import ec.cleantok.inventario.api.InventarioDto
import ec.cleantok.inventario.api.InventarioItemDto
import ec.cleantok.inventario.api.ProductoRefDto
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.inventario.api.StockUbicacionDto
import ec.cleantok.inventario.api.UbicacionRefDto
import ec.cleantok.inventario.dominio.StockRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Cálculos de stock y cobertura por producto. También los usa el módulo de alertas. */
data class Cobertura(
    val stockBodega: BigDecimal,
    val stockTotal: BigDecimal,
    val consumoDiario: BigDecimal?,
    val diasCobertura: BigDecimal?,
)

@Service
class InventarioService(
    private val productos: ProductoRepository,
    private val ubicaciones: UbicacionRepository,
    private val stocks: StockRepository,
    private val pronostico: PronosticoConsumo,
    private val acceso: AccesoEstablecimiento,
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: CleanTokProperties,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun inventario(establecimientoId: UUID, incluirInactivos: Boolean): InventarioDto {
        val e = acceso.visible(establecimientoId)
        val ahora = clock.instant()
        val lista = if (incluirInactivos && ContextoSeguridad.usuario().esAdmin) {
            productos.findAllByEstablecimientoIdOrderByNombre(e.id)
        } else {
            productos.findAllByEstablecimientoIdAndActivoTrueOrderByNombre(e.id)
        }
        val ubics = ubicaciones.findAllByEstablecimientoIdOrderByOrdenAscNombreAsc(e.id)
        val bodega = ubics.first { it.tipo == TipoUbicacion.BODEGA }
        val pisos = ubics.filter { it.tipo != TipoUbicacion.BODEGA }
        val saldos = stocks.findAllByEstablecimientoId(e.id).associateBy { it.productoId to it.ubicacionId }
        val pron = pronostico.pronosticar(e.id, ahora)
        val consumos = consumos(e.id, ahora)
        val conMovimientos = productosConMovimientos(e.id)

        val items = lista.map { p ->
            fun saldo(u: UUID) = saldos[p.id to u]?.cantidad ?: CERO
            val stockBodega = saldo(bodega.id)
            // Pisos activos siempre; inactivos solo si les quedó stock.
            val stockPisos = pisos.filter { it.activo || saldo(it.id).signum() > 0 }
                .map { StockUbicacionDto(UbicacionRefDto(it.id, it.nombre, it.tipo), saldo(it.id)) }
            val total = stockPisos.fold(stockBodega) { acc, s -> acc.add(s.cantidad) }
            val cob = cobertura(stockBodega, total, pron[p.id]?.consumoDiario)
            val (c7, c30) = consumos[p.id] ?: (CERO to CERO)
            InventarioItemDto(
                producto = ProductoRefDto(p.id, p.nombre, p.unidad),
                categoria = p.categoria,
                esConsumible = p.esConsumible,
                abastece = p.abastece,
                presentacion = p.presentacion,
                stockMinimo = p.stockMinimo,
                stockBodega = stockBodega,
                stockPisos = stockPisos,
                stockTotal = total,
                consumo7Dias = c7,
                consumo30Dias = c30,
                consumoDiarioPromedio = cob.consumoDiario,
                diasCobertura = cob.diasCobertura,
                estado = estado(p, cob, p.id in conMovimientos),
            )
        }
        return InventarioDto(RefDto(e.id, e.nombre), Zona.presentar(ahora), props.diasCoberturaMin, items)
    }

    fun cobertura(stockBodega: BigDecimal, stockTotal: BigDecimal, consumoDiario: BigDecimal?): Cobertura {
        val dias = consumoDiario?.takeIf { it.signum() > 0 }?.let { stockTotal.divide(it, 1, RoundingMode.HALF_UP) }
        return Cobertura(stockBodega, stockTotal, consumoDiario, dias)
    }

    /**
     * CRITICO: sin stock (con historial) o cobertura < 2 días.
     * BAJO: bodega ≤ stock mínimo o cobertura < mínimo configurado.
     * SIN_DATOS: sin consumo suficiente para estimar. OK en otro caso. Los no consumibles siempre OK.
     */
    fun estado(p: Producto, c: Cobertura, tieneMovimientos: Boolean): EstadoInventario {
        if (!p.esConsumible) return EstadoInventario.OK
        val minimo = BigDecimal.valueOf(props.diasCoberturaMin.toLong())
        return when {
            c.stockTotal.signum() == 0 && tieneMovimientos -> EstadoInventario.CRITICO
            c.diasCobertura != null && c.diasCobertura < DOS -> EstadoInventario.CRITICO
            p.stockMinimo != null && tieneMovimientos && c.stockBodega <= p.stockMinimo -> EstadoInventario.BAJO
            c.diasCobertura != null && c.diasCobertura < minimo -> EstadoInventario.BAJO
            c.diasCobertura == null -> EstadoInventario.SIN_DATOS
            else -> EstadoInventario.OK
        }
    }

    private fun consumos(establecimientoId: UUID, ahora: Instant): Map<UUID, Pair<BigDecimal, BigDecimal>> {
        val sql = """
            select producto_id,
                   coalesce(sum(cantidad) filter (where ocurrido_en > :d7), 0) as c7,
                   coalesce(sum(cantidad), 0) as c30
            from movimiento
            where establecimiento_id = :est and tipo = 'CONSUMO' and ocurrido_en > :d30 and ocurrido_en <= :ahora
            group by producto_id
        """.trimIndent()
        val params = mapOf(
            "est" to establecimientoId,
            "d7" to Timestamp.from(ahora.minus(Duration.ofDays(7))),
            "d30" to Timestamp.from(ahora.minus(Duration.ofDays(30))),
            "ahora" to Timestamp.from(ahora),
        )
        return jdbc.query(sql, params) { rs, _ ->
            rs.getObject("producto_id", UUID::class.java) to (rs.getBigDecimal("c7") to rs.getBigDecimal("c30"))
        }.toMap()
    }

    fun productosConMovimientos(establecimientoId: UUID): Set<UUID> =
        jdbc.queryForList(
            "select distinct producto_id from movimiento where establecimiento_id = :est",
            mapOf("est" to establecimientoId),
            UUID::class.java,
        ).filterNotNull().toSet()

    companion object {
        private val CERO: BigDecimal = BigDecimal.ZERO.setScale(3)
        private val DOS: BigDecimal = BigDecimal.valueOf(2)
    }
}
