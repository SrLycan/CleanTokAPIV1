package ec.cleantok.monitoreo

import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Zona
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.servicio.AccesoEstablecimiento
import ec.cleantok.inventario.api.ProductoRefDto
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.inventario.dominio.StockRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/** Suficiencia de un insumo en un piso según su consumo: cuántos días alcanza lo que hay ahí. */
enum class Suficiencia { SUFICIENTE, BAJO, AGOTADO, SIN_CONSUMO }

/** Cómo va el consumo de los últimos 7 días frente al período anterior. */
enum class Tendencia { NORMAL, ALTA, BAJA }

data class DiaAtipicoDto(
    val fecha: LocalDate,
    val cantidad: BigDecimal,
    @field:Schema(description = "Veces el promedio diario (1,8 = 80 % más de lo normal)") val vecesPromedio: BigDecimal,
)

data class ConsumoProductoPisoDto(
    val producto: ProductoRefDto,
    @field:Schema(description = "Stock que hay ahora en el piso") val stockPiso: BigDecimal,
    @field:Schema(description = "Promedio diario del período (sin contar hoy)") val promedioDiario: BigDecimal,
    @field:Schema(description = "Desviación estándar diaria") val desviacion: BigDecimal,
    val maximoDiario: BigDecimal,
    val totalPeriodo: BigDecimal,
    @field:Schema(description = "Consumo de los últimos 7 días completos") val ultimos7Dias: BigDecimal,
    @field:Schema(description = "Lo esperado para 7 días según el período anterior") val esperado7Dias: BigDecimal,
    @field:Schema(description = "Variación de los últimos 7 días frente al período anterior, en %") val variacionPct: Int?,
    val tendencia: Tendencia,
    @field:Schema(description = "Días con consumo atípico: ≥ promedio + 2 desviaciones y ≥ 1,5 × promedio") val diasAtipicos: List<DiaAtipicoDto>,
    val anomalo: Boolean,
    @field:Schema(description = "Por qué es atípico, en palabras") val motivo: String?,
    @field:Schema(description = "Días que alcanza el stock del piso al ritmo promedio") val coberturaDias: BigDecimal?,
    val suficiencia: Suficiencia,
    @field:Schema(description = "Consumo de cada día del período, del más antiguo a hoy") val serie: List<BigDecimal>,
)

data class ConsumoPisoDto(
    val ubicacion: RefDto,
    val anomalos: Int,
    @field:Schema(description = "Productos con insumos bajos o agotados en el piso") val insuficientes: Int,
    val productos: List<ConsumoProductoPisoDto>,
)

data class ResumenConsumoDto(val pisos: Int, val anomalos: Int, val insuficientes: Int, val pisosConProblemas: Int)

data class ConsumoPisosDto(
    val establecimiento: RefDto,
    val desde: LocalDate,
    val hasta: LocalDate,
    val dias: Int,
    @field:Schema(description = "Días de cobertura mínimos que debe tener cada piso") val coberturaMinimaDias: Int,
    val resumen: ResumenConsumoDto,
    val pisos: List<ConsumoPisoDto>,
)

/**
 * Monitoreo del consumo por piso (para el administrador de contrato, el supervisor y el admin):
 * - Consumos atípicos: días con consumo ≥ promedio + 2 desviaciones (y ≥ 1,5 × promedio) y tendencia de los
 *   últimos 7 días ≥ +50 % o ≤ −60 % frente al período anterior (lo segundo suele ser consumo sin registrar).
 * - Suficiencia: que lo que hay en cada piso alcance al menos [COBERTURA_MINIMA_DIAS] días al ritmo promedio.
 */
@Service
class ConsumoPisosService(
    private val acceso: AccesoEstablecimiento,
    private val ubicaciones: UbicacionRepository,
    private val productos: ProductoRepository,
    private val stocks: StockRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun analizar(establecimientoId: UUID, diasPedidos: Int): ConsumoPisosDto {
        val e = acceso.visible(establecimientoId)
        val dias = diasPedidos.coerceIn(14, 90)
        val hoy = LocalDate.now(clock.withZone(Zona.ECUADOR))
        val desde = hoy.minusDays(dias - 1L)
        val pisos = ubicaciones.findAllByEstablecimientoIdOrderByOrdenAscNombreAsc(e.id).filter { it.activo && !it.esBodega }

        // Consumo por piso, producto y día (hora de Ecuador).
        val consumos = jdbc.query(
            """
            select ubicacion_origen_id as u, producto_id as p, (ocurrido_en at time zone 'America/Guayaquil')::date as d, sum(cantidad) as c
            from movimiento
            where establecimiento_id = :est and tipo = 'CONSUMO' and ocurrido_en >= :desde
            group by 1, 2, 3
            """.trimIndent(),
            mapOf("est" to e.id, "desde" to Timestamp.from(desde.atStartOfDay(Zona.ECUADOR).toInstant())),
        ) { rs, _ ->
            Triple(rs.getObject("u", UUID::class.java), rs.getObject("p", UUID::class.java), rs.getObject("d", LocalDate::class.java)) to
                rs.getBigDecimal("c").toDouble()
        }.toMap()
        val stockPiso = stocks.findAllByEstablecimientoId(e.id).associate { (it.ubicacionId to it.productoId) to it.cantidad }
        val prods = productos.findAllById((consumos.keys.map { it.second } + stockPiso.keys.map { it.second }).toSet()).associateBy { it.id }

        val resultado = pisos.map { piso ->
            val ids = (consumos.keys.filter { it.first == piso.id }.map { it.second } +
                stockPiso.filter { (k, v) -> k.first == piso.id && v.signum() > 0 }.keys.map { it.second }).toSet()
            val filas = ids.mapNotNull { pid ->
                val p = prods[pid] ?: return@mapNotNull null
                if (!p.activo || !p.esConsumible) return@mapNotNull null
                val serie = (0 until dias).map { i -> consumos[Triple(piso.id, pid, desde.plusDays(i.toLong()))] ?: 0.0 }
                analizarProducto(ProductoRefDto(p.id, p.nombre, p.unidad), serie, desde, stockPiso[piso.id to pid] ?: BigDecimal.ZERO)
            }.sortedWith(compareByDescending<ConsumoProductoPisoDto> { it.anomalo }.thenBy { PRIORIDAD.getValue(it.suficiencia) }.thenBy { it.producto.nombre })
            ConsumoPisoDto(
                RefDto(piso.id, piso.nombre),
                anomalos = filas.count { it.anomalo },
                insuficientes = filas.count { it.suficiencia == Suficiencia.BAJO || it.suficiencia == Suficiencia.AGOTADO },
                productos = filas,
            )
        }
        return ConsumoPisosDto(
            establecimiento = RefDto(e.id, e.nombre),
            desde = desde,
            hasta = hoy,
            dias = dias,
            coberturaMinimaDias = COBERTURA_MINIMA_DIAS,
            resumen = ResumenConsumoDto(
                pisos = resultado.size,
                anomalos = resultado.sumOf { it.anomalos },
                insuficientes = resultado.sumOf { it.insuficientes },
                pisosConProblemas = resultado.count { it.anomalos > 0 || it.insuficientes > 0 },
            ),
            pisos = resultado,
        )
    }

    /** [serie]: un valor por día desde [desde]; el último es hoy (día incompleto: no entra en el promedio). */
    private fun analizarProducto(producto: ProductoRefDto, serie: List<Double>, desde: LocalDate, stock: BigDecimal): ConsumoProductoPisoDto {
        val completos = serie.dropLast(1)
        val promedio = completos.average().takeIf { !it.isNaN() } ?: 0.0
        val desviacion = sqrt(completos.sumOf { (it - promedio) * (it - promedio) } / max(1, completos.size))
        val umbral = max(promedio + 2 * desviacion, promedio * 1.5)
        val atipicos = if (promedio <= 0.0) emptyList() else serie.mapIndexedNotNull { i, c ->
            if (c > 0 && c >= umbral) DiaAtipicoDto(desde.plusDays(i.toLong()), dec(c), dec(c / promedio, 1)) else null
        }

        // Últimos 7 días completos frente a los anteriores.
        val ultimos = completos.takeLast(7)
        val anteriores = completos.dropLast(7)
        val base = anteriores.average().takeIf { !it.isNaN() } ?: 0.0
        val ultimos7 = ultimos.sum()
        val variacion = if (base > 0 && anteriores.size >= 7) ((ultimos7 / 7 - base) / base * 100).toInt() else null
        val tendencia = when {
            variacion == null -> Tendencia.NORMAL
            variacion >= 50 -> Tendencia.ALTA
            variacion <= -60 -> Tendencia.BAJA
            else -> Tendencia.NORMAL
        }
        val recientes = atipicos.filter { it.fecha >= desde.plusDays(serie.size - 14L) }
        val motivo = listOfNotNull(
            if (recientes.isNotEmpty()) "${recientes.size} día${if (recientes.size == 1) "" else "s"} con consumo atípico en las últimas 2 semanas " +
                "(hasta ${recientes.maxOf { it.vecesPromedio }.toPlainString().replace('.', ',')} × el promedio)" else null,
            when (tendencia) {
                Tendencia.ALTA -> "Últimos 7 días: +$variacion % frente a lo habitual"
                Tendencia.BAJA -> "Últimos 7 días: $variacion % frente a lo habitual (¿consumos sin registrar?)"
                Tendencia.NORMAL -> null
            },
        ).joinToString(". ").ifBlank { null }

        val cobertura = if (promedio > 0) stock.toDouble() / promedio else null
        val suficiencia = when {
            promedio <= 0.0 -> Suficiencia.SIN_CONSUMO
            stock.signum() == 0 -> Suficiencia.AGOTADO
            cobertura!! < COBERTURA_MINIMA_DIAS -> Suficiencia.BAJO
            else -> Suficiencia.SUFICIENTE
        }
        return ConsumoProductoPisoDto(
            producto = producto,
            stockPiso = stock.setScale(3, RoundingMode.HALF_UP),
            promedioDiario = dec(promedio),
            desviacion = dec(desviacion),
            maximoDiario = dec(serie.maxOrNull() ?: 0.0),
            totalPeriodo = dec(serie.sum()),
            ultimos7Dias = dec(ultimos7),
            esperado7Dias = dec(base * 7),
            variacionPct = variacion,
            tendencia = tendencia,
            diasAtipicos = atipicos,
            anomalo = motivo != null,
            motivo = motivo,
            coberturaDias = cobertura?.let { dec(it, 1) },
            suficiencia = suficiencia,
            serie = serie.map { dec(it) },
        )
    }

    private fun dec(v: Double, escala: Int = 3): BigDecimal = BigDecimal.valueOf(v).setScale(escala, RoundingMode.HALF_UP)

    companion object {
        /** Cada piso debe tener al menos para 2 días de consumo (lo demás se repone desde la bodega). */
        const val COBERTURA_MINIMA_DIAS = 2

        /** Primero lo agotado y lo bajo. */
        private val PRIORIDAD = mapOf(Suficiencia.AGOTADO to 0, Suficiencia.BAJO to 1, Suficiencia.SUFICIENTE to 2, Suficiencia.SIN_CONSUMO to 3)
    }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Nivel y alertas")
class ConsumoPisosController(private val servicio: ConsumoPisosService) {

    @GetMapping("/establecimientos/{id}/consumo-pisos")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ADMIN_CONTRATO')")
    @Operation(
        summary = "Consumo por piso: consumos atípicos, estadísticas y si cada piso tiene insumos suficientes",
        description = "Atípico: día ≥ promedio + 2σ (y ≥ 1,5 × promedio), o últimos 7 días ≥ +50 % / ≤ −60 % frente al período anterior. " +
            "Suficiencia: el stock del piso alcanza al menos `coberturaMinimaDias` al ritmo promedio. `dias` entre 14 y 90 (30 por defecto).",
    )
    fun consumo(@PathVariable id: UUID, @RequestParam(defaultValue = "30") dias: Int): ConsumoPisosDto = servicio.analizar(id, dias)
}
