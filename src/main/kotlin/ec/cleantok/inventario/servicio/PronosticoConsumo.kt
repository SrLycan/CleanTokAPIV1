package ec.cleantok.inventario.servicio

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class Pronostico(
    /** Consumo diario esperado en la unidad del producto; null si no hay datos suficientes. */
    val consumoDiario: BigDecimal?,
    val diasConDatos: Int,
)

/**
 * Punto de extensión para la predicción de consumo. Hoy: promedio de 14 días.
 * La fase 2 del hackathon puede reemplazarlo por un modelo (estacionalidad, días laborables, IA).
 */
interface PronosticoConsumo {
    fun pronosticar(establecimientoId: UUID, ahora: Instant): Map<UUID, Pronostico>
}

/**
 * Consumo diario promedio = suma de CONSUMO de los últimos 14 días ÷ 14.
 * Con menos de 3 días distintos con consumo (hora de Ecuador) se considera "sin datos suficientes".
 */
@Service
class PronosticoPromedio14Dias(private val jdbc: NamedParameterJdbcTemplate) : PronosticoConsumo {

    override fun pronosticar(establecimientoId: UUID, ahora: Instant): Map<UUID, Pronostico> {
        val sql = """
            select producto_id,
                   sum(cantidad) as total,
                   count(distinct (ocurrido_en at time zone 'America/Guayaquil')::date) as dias
            from movimiento
            where establecimiento_id = :est and tipo = 'CONSUMO' and ocurrido_en > :desde and ocurrido_en <= :hasta
            group by producto_id
        """.trimIndent()
        val params = mapOf(
            "est" to establecimientoId,
            "desde" to Timestamp.from(ahora.minus(Duration.ofDays(VENTANA_DIAS))),
            "hasta" to Timestamp.from(ahora),
        )
        return jdbc.query(sql, params) { rs, _ ->
            val dias = rs.getInt("dias")
            val diario = if (dias >= MIN_DIAS_CON_DATOS) {
                rs.getBigDecimal("total").divide(BigDecimal.valueOf(VENTANA_DIAS), 3, RoundingMode.HALF_UP)
            } else {
                null
            }
            rs.getObject("producto_id", UUID::class.java) to Pronostico(diario, dias)
        }.toMap()
    }

    companion object {
        const val VENTANA_DIAS = 14L
        const val MIN_DIAS_CON_DATOS = 3
    }
}
