package ec.cleantok.monitoreo.api

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.inventario.api.ProductoRefDto
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.inventario.api.UbicacionRefDto
import ec.cleantok.monitoreo.dominio.CierreAlerta
import ec.cleantok.monitoreo.dominio.EstadoAlerta
import ec.cleantok.monitoreo.dominio.Severidad
import ec.cleantok.monitoreo.dominio.TipoAlerta
import ec.cleantok.monitoreo.dominio.VerificacionIa
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class AlertaDto(
    val id: UUID,
    val tipo: TipoAlerta,
    val severidad: Severidad,
    val estado: EstadoAlerta,
    val mensaje: String,
    val producto: ProductoRefDto,
    @field:Schema(description = "null en alertas del establecimiento (COBERTURA_BAJA)") val ubicacion: UbicacionRefDto?,
    val abastece: Abastece,
    @field:Schema(example = "Lo provee la entidad contratante.") val quienRepone: String,
    val creadaEn: OffsetDateTime,
    val atendidaEn: OffsetDateTime?,
    val atendidaPor: RefDto?,
    val cierre: CierreAlerta?,
    val notaAtencion: String?,
)

data class AtenderAlertaRequest(@field:Size(max = 500) val nota: String? = null)

data class ReporteNivelDto(
    val id: UUID,
    val clientUuid: UUID,
    val producto: ProductoRefDto,
    val ubicacion: UbicacionRefDto,
    val nivelPct: Int,
    val fotoId: UUID?,
    val usuario: RefDto,
    val ocurridoEn: OffsetDateTime,
    val registradoEn: OffsetDateTime,
    val nota: String?,
    @field:Schema(description = "Reservado para la verificación por foto con IA (fase 2)") val verificacionIa: VerificacionIa,
    val nivelIa: Int?,
)

data class ReporteNivelResultadoDto(
    val reporte: ReporteNivelDto,
    val duplicado: Boolean,
    val alertas: List<ec.cleantok.inventario.api.AlertaResumenDto>,
)
