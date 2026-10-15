package ec.cleantok.establecimiento.api

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.establecimiento.dominio.Establecimiento
import ec.cleantok.establecimiento.dominio.TipoEstablecimiento
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.Ubicacion
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

data class EstablecimientoRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 200)
    @field:Schema(example = "Hospital del Sur")
    val nombre: String,
    @field:NotNull(message = "El tipo es obligatorio") val tipo: TipoEstablecimiento,
    @field:Size(max = 300) @field:Schema(example = "Ministerio de Salud Pública") val entidadContratante: String? = null,
    @field:Size(max = 500) val direccion: String? = null,
    @field:DecimalMin("-90") @field:DecimalMax("90") @field:Schema(example = "-2.170998") val lat: BigDecimal? = null,
    @field:DecimalMin("-180") @field:DecimalMax("180") @field:Schema(example = "-79.922359") val lng: BigDecimal? = null,
    @field:Positive(message = "El área debe ser mayor que 0") @field:Schema(example = "809") val areaM2: BigDecimal? = null,
)

data class EstablecimientoDto(
    val id: UUID,
    val nombre: String,
    val tipo: TipoEstablecimiento,
    val entidadContratante: String?,
    val direccion: String?,
    val lat: BigDecimal?,
    val lng: BigDecimal?,
    val areaM2: BigDecimal?,
    val activo: Boolean,
    @field:Schema(description = "Id de la bodega del establecimiento") val bodegaId: UUID?,
    @field:Schema(description = "Si quien consulta puede eliminarlo: el admin que lo creó o el administrador de la plataforma")
    val puedeEliminar: Boolean = false,
) {
    companion object {
        fun de(e: Establecimiento, bodegaId: UUID?, puedeEliminar: Boolean = false) = EstablecimientoDto(
            e.id, e.nombre, e.tipo, e.entidadContratante, e.direccion, e.lat, e.lng, e.areaM2, e.activo, bodegaId, puedeEliminar,
        )
    }
}

data class AsignarEncargadosRequest(
    @field:NotNull(message = "La lista de encargados es obligatoria")
    @field:Schema(description = "Reemplaza la lista completa de encargados asignados")
    val usuarioIds: List<UUID>,
)

data class EncargadoAsignadoDto(val id: UUID, val nombre: String, val email: String, val activo: Boolean)

data class CrearUbicacionRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 120)
    @field:Schema(example = "Piso 3")
    val nombre: String,
    @field:NotNull(message = "El tipo es obligatorio")
    @field:Schema(description = "PISO o AREA. La BODEGA se crea sola con el establecimiento.")
    val tipo: TipoUbicacion,
    @field:Schema(description = "Posición en la lista; si se omite va al final") val orden: Int? = null,
)

data class ActualizarUbicacionRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 120) val nombre: String,
    @field:Schema(description = "PISO o AREA. No aplica a la bodega.") val tipo: TipoUbicacion? = null,
)

data class OrdenarUbicacionesRequest(
    @field:NotEmpty(message = "La lista no puede estar vacía")
    @field:Schema(description = "Ids de las ubicaciones en el orden deseado")
    val ubicacionIds: List<UUID>,
)

data class UbicacionDto(
    val id: UUID,
    val establecimientoId: UUID,
    val nombre: String,
    val tipo: TipoUbicacion,
    val orden: Int,
    val activo: Boolean,
    @field:Schema(description = "URL codificada en el QR (solo para el admin)") val urlQr: String?,
) {
    companion object {
        fun de(u: Ubicacion, urlQr: String?) =
            UbicacionDto(u.id, u.establecimientoId, u.nombre, u.tipo, u.orden, u.activo, urlQr)
    }
}

data class UbicacionResumenDto(val id: UUID, val nombre: String, val tipo: TipoUbicacion)

data class EstablecimientoResumenDto(val id: UUID, val nombre: String)

data class QrResolucionDto(
    val ubicacion: UbicacionResumenDto,
    val establecimiento: EstablecimientoResumenDto,
    val bodega: UbicacionResumenDto,
    @field:Schema(description = "Productos activos del establecimiento con su stock aquí y en la bodega")
    val productos: List<ProductoEnUbicacionDto> = emptyList(),
    @field:Schema(description = "Alertas abiertas de esta ubicación (en la bodega, también las del establecimiento)")
    val alertas: List<AlertaEnQrDto> = emptyList(),
)

data class AlertaEnQrDto(val id: UUID, val tipo: String, val severidad: String, val mensaje: String, val productoId: UUID)

data class ProductoEnUbicacionDto(
    val id: UUID,
    val nombre: String,
    val categoria: Categoria,
    val esConsumible: Boolean,
    val unidad: Unidad,
    val unidadesCompatibles: List<Unidad>,
    val presentacion: String?,
    val abastece: Abastece,
    @field:Schema(description = "Stock en la ubicación escaneada") val stockUbicacion: BigDecimal,
    @field:Schema(description = "Stock en la bodega del establecimiento") val stockBodega: BigDecimal,
)
