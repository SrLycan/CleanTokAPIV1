package ec.cleantok.inventario.servicio

import ec.cleantok.comun.error.ApiException
import ec.cleantok.comun.error.CodigoError
import ec.cleantok.inventario.api.ItemLoteRequest
import ec.cleantok.inventario.api.ItemLoteResultadoDto
import ec.cleantok.inventario.api.LoteRequest
import ec.cleantok.inventario.api.LoteResultadoDto
import ec.cleantok.inventario.api.ReporteNivelRequest
import ec.cleantok.inventario.api.ResultadoItemLote
import ec.cleantok.inventario.api.TipoItemLote
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/** Lo implementa el módulo de monitoreo para procesar reportes de nivel dentro de un lote. */
fun interface ProcesadorReporteNivel {
    /** Devuelve (id del reporte, duplicado). */
    fun procesar(req: ReporteNivelRequest): Pair<UUID, Boolean>
}

/**
 * Sincronización offline: procesa hasta 100 ítems en orden de `ocurridoEn`, cada uno en su propia
 * transacción. Un error en un ítem no afecta a los demás.
 */
@Service
class SincronizacionService(
    private val movimientos: MovimientoService,
    private val reportes: ObjectProvider<ProcesadorReporteNivel>,
    private val validator: Validator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun procesar(req: LoteRequest): LoteResultadoDto {
        val items = req.items!!.withIndex().sortedWith(compareBy(nullsFirst()) { ocurridoEn(it.value) })
        val resultados = items.map { (indice, item) -> procesarItem(indice, item) }.sortedBy { it.indice }
        return LoteResultadoDto(
            procesados = resultados.size,
            ok = resultados.count { it.resultado == ResultadoItemLote.OK },
            duplicados = resultados.count { it.resultado == ResultadoItemLote.DUPLICADO },
            errores = resultados.count { it.resultado == ResultadoItemLote.ERROR },
            items = resultados,
        )
    }

    private fun procesarItem(indice: Int, item: ItemLoteRequest): ItemLoteResultadoDto {
        val clientUuid = item.movimiento?.clientUuid ?: item.reporteNivel?.clientUuid
        fun error(codigo: CodigoError, mensaje: String, detalle: Map<String, Any?>? = null) =
            ItemLoteResultadoDto(indice, clientUuid, ResultadoItemLote.ERROR, codigo = codigo.name, mensaje = mensaje, detalle = detalle)

        return try {
            when (item.tipo) {
                TipoItemLote.MOVIMIENTO -> {
                    val mov = item.movimiento ?: return error(CodigoError.VALIDACION, "Falta el objeto 'movimiento'")
                    validar(mov)?.let { return error(CodigoError.VALIDACION, it) }
                    val r = movimientos.registrar(mov)
                    ItemLoteResultadoDto(
                        indice, clientUuid,
                        if (r.duplicado) ResultadoItemLote.DUPLICADO else ResultadoItemLote.OK,
                        id = r.movimiento.id,
                    )
                }
                TipoItemLote.REPORTE_NIVEL -> {
                    val rep = item.reporteNivel ?: return error(CodigoError.VALIDACION, "Falta el objeto 'reporteNivel'")
                    validar(rep)?.let { return error(CodigoError.VALIDACION, it) }
                    val procesador = reportes.getIfAvailable()
                        ?: return error(CodigoError.VALIDACION, "Los reportes de nivel aún no están disponibles")
                    val (id, duplicado) = procesador.procesar(rep)
                    ItemLoteResultadoDto(indice, clientUuid, if (duplicado) ResultadoItemLote.DUPLICADO else ResultadoItemLote.OK, id = id)
                }
                null -> error(CodigoError.VALIDACION, "El tipo de ítem es obligatorio")
            }
        } catch (e: ApiException) {
            error(e.codigo, e.message, e.extras.ifEmpty { null })
        } catch (e: Exception) {
            log.error("Error procesando ítem {} del lote: {}", indice, e.javaClass.name, e)
            error(CodigoError.ERROR_INTERNO, "Ocurrió un error inesperado con este ítem")
        }
    }

    private fun validar(objeto: Any): String? =
        validator.validate(objeto).takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { "${it.propertyPath}: ${it.message}" }

    private fun ocurridoEn(item: ItemLoteRequest): OffsetDateTime? = item.movimiento?.ocurridoEn ?: item.reporteNivel?.ocurridoEn
}
