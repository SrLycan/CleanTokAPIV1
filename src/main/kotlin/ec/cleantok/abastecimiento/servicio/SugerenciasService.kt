package ec.cleantok.abastecimiento.servicio

import ec.cleantok.abastecimiento.api.SugerenciaDto
import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.inventario.api.EstadoInventario
import ec.cleantok.inventario.api.InventarioItemDto
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.inventario.servicio.InventarioService
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Qué pedir y cuánto (RF-11), para los productos que compra la empresa de limpieza:
 * consumo diario × (días de entrega + ciclo + seguridad) − stock actual, redondeado hacia arriba.
 * Si no hay consumo reciente (por ejemplo, porque el producto se agotó y ya no se pudo consumir), se usa el consumo
 * histórico de los días en que sí hubo consumo (90 días). Sin historial, el doble del stock mínimo si está definido.
 *
 * Además de los bajos o críticos, entra todo producto con la bodega vacía que todavía se usa en los pisos o que
 * fue reportado (alerta abierta): ya no hay de dónde reponer los pisos. Si no hay forma de estimar el consumo, se
 * sugiere al menos lo que hay repartido en los pisos (mínimo 1) y el admin ajusta la cantidad.
 */
@Service
class SugerenciasService(
    private val establecimientos: EstablecimientoRepository,
    private val inventario: InventarioService,
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: CleanTokProperties,
) {
    @Transactional(readOnly = true)
    fun sugerencias(todos: Boolean): List<SugerenciaDto> {
        val empresaId = ContextoSeguridad.empresaId()
        val pedidos = yaPedidos(empresaId)
        val reportados = alertasAbiertas(empresaId)
        return establecimientos.findAllByEmpresaIdAndActivoTrueOrderByNombre(empresaId).flatMap { e ->
            val historico = consumoHistorico(e.id)
            inventario.inventario(e.id, incluirInactivos = false).items.mapNotNull { item ->
                if (item.abastece != Abastece.EMPRESA_LIMPIEZA || !item.esConsumible) return@mapNotNull null
                val bodegaVacia = item.stockBodega.signum() <= 0 && (item.stockTotal.signum() > 0 || item.producto.id in reportados)
                if (!todos && item.estado !in URGENTES && !bodegaVacia) return@mapNotNull null
                val consumo = item.consumoDiarioPromedio?.takeIf { it.signum() > 0 } ?: historico[item.producto.id]
                val calculada = recomendada(consumo, item)?.takeIf { it.signum() > 0 }
                // Con consumo conocido y cantidad 0, lo que hay alcanza para el ciclo: no se pide aunque la bodega esté vacía.
                val cantidad = calculada
                    ?: if (bodegaVacia && consumo == null && item.stockMinimo == null) item.stockTotal.max(BigDecimal.ONE).setScale(0, RoundingMode.CEILING)
                    else return@mapNotNull null
                SugerenciaDto(
                    establecimiento = RefDto(e.id, e.nombre),
                    producto = item.producto,
                    estado = item.estado,
                    stockBodega = item.stockBodega,
                    stockTotal = item.stockTotal,
                    consumoDiario = consumo,
                    diasCobertura = item.diasCobertura,
                    cantidadRecomendada = cantidad,
                    motivo = if (bodegaVacia && (item.estado !in URGENTES || calculada == null)) motivoBodegaVacia(item, estimada = calculada == null) else motivo(item),
                    yaPedido = pedidos[item.producto.id],
                )
            }
        }.sortedWith(compareBy({ it.estado != EstadoInventario.CRITICO }, { it.diasCobertura ?: BigDecimal.ZERO }, { it.producto.nombre }))
    }

    fun recomendada(consumoDiario: BigDecimal?, item: InventarioItemDto): BigDecimal? {
        val a = props.abastecimiento
        val objetivo = consumoDiario?.takeIf { it.signum() > 0 }
            ?.multiply(BigDecimal(a.diasEntrega + a.diasCiclo + a.diasSeguridad))
            ?: item.stockMinimo?.multiply(BigDecimal(2))
            ?: return null
        // Se pide en unidades enteras de la unidad del producto (litros, rollos, paquetes…).
        return objetivo.subtract(item.stockTotal).max(BigDecimal.ZERO).setScale(0, RoundingMode.CEILING)
    }

    private fun motivo(item: InventarioItemDto): String {
        val d = item.diasCobertura
        return when {
            item.stockTotal.signum() == 0 -> "Sin stock"
            d != null && item.estado != EstadoInventario.OK -> "Cobertura de ${texto(d)} días (mínimo ${props.diasCoberturaMin})"
            item.stockMinimo != null && item.stockBodega <= item.stockMinimo -> "Bodega en el stock mínimo o debajo"
            d != null -> "Cobertura de ${texto(d)} días"
            else -> "Reposición sugerida"
        }
    }

    private fun motivoBodegaVacia(item: InventarioItemDto, estimada: Boolean): String {
        val base = if (item.stockTotal.signum() > 0) "Bodega vacía · quedan ${texto(item.stockTotal)} en pisos" else "Bodega vacía y reportado en pisos"
        return if (estimada) "$base · sin historial de consumo, revisa la cantidad" else base
    }

    /** Productos con una alerta abierta (reportados desde los pisos o por el monitoreo). */
    private fun alertasAbiertas(empresaId: UUID): Set<UUID> = jdbc.query(
        "select distinct producto_id from alerta where empresa_id = :emp and estado = 'ABIERTA'", mapOf("emp" to empresaId),
    ) { rs, _ -> rs.getObject(1, UUID::class.java) }.toSet()

    /** Consumo diario de los días que sí tuvieron consumo (90 días), con al menos 3 días de datos. */
    private fun consumoHistorico(establecimientoId: UUID): Map<UUID, BigDecimal> {
        val sql = """
            select producto_id, sum(cantidad) / count(distinct (ocurrido_en at time zone 'America/Guayaquil')::date) as diario
            from movimiento
            where establecimiento_id = :est and tipo = 'CONSUMO' and ocurrido_en > now() - interval '90 days'
            group by producto_id
            having count(distinct (ocurrido_en at time zone 'America/Guayaquil')::date) >= 3
        """.trimIndent()
        return jdbc.query(sql, mapOf("est" to establecimientoId)) { rs, _ ->
            rs.getObject(1, UUID::class.java) to rs.getBigDecimal(2).setScale(3, RoundingMode.HALF_UP)
        }.toMap()
    }

    /** Productos que ya están en una solicitud abierta o en una orden que no ha llegado. */
    private fun yaPedidos(empresaId: UUID): Map<UUID, String> {
        val sql = """
            select l.producto_id, s.codigo
            from solicitud_linea l join solicitud s on s.id = l.solicitud_id
            where s.empresa_id = :emp and s.estado in ('BORRADOR', 'EN_COTIZACION')
            union all
            select ol.producto_id, o.codigo
            from orden_linea ol join orden_compra o on o.id = ol.orden_id
            where o.empresa_id = :emp and o.estado not in ('ENTREGADA', 'RECHAZADA') and ol.cantidad_recibida is null
        """.trimIndent()
        return jdbc.query(sql, mapOf("emp" to empresaId)) { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.toMap()
    }

    private fun texto(v: BigDecimal) = v.stripTrailingZeros().toPlainString().replace('.', ',')

    companion object {
        private val URGENTES = setOf(EstadoInventario.CRITICO, EstadoInventario.BAJO)
    }
}
