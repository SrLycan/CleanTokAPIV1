package ec.cleantok.ordenes.api

import ec.cleantok.catalogo.api.ProductoDto
import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.ordenes.dominio.EstadoMaterial
import ec.cleantok.ordenes.dominio.EstadoOrden
import ec.cleantok.ordenes.extraccion.Confianza
import ec.cleantok.ordenes.extraccion.FormatoOrden
import ec.cleantok.ordenes.extraccion.OrigenMaterial
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class ResumenMaterialesDto(
    val total: Int,
    @field:Schema(description = "Se convertirán en productos al confirmar") val seleccionados: Int,
    val confianzaAlta: Int,
    val confianzaMedia: Int,
    val confianzaBaja: Int,
    val porSeccion: Map<SeccionOrigen, Int>,
)

data class OrdenDto(
    val id: UUID,
    val establecimientoId: UUID,
    val estado: EstadoOrden,
    val numeroOrden: String?,
    val entidadContratante: String?,
    val fechaEmision: LocalDate?,
    @field:Schema(description = "En limpieza, la cantidad del ítem son m²") val areaM2: BigDecimal?,
    val descripcionServicio: String?,
    val vigenciaMeses: Int?,
    val formatoDetectado: FormatoOrden?,
    @field:Schema(allowableValues = ["PARSER_SERCOP", "IA", "PARSER_SERCOP+IA", "MANUAL"]) val metodoExtraccion: String?,
    val tieneCapaTexto: Boolean?,
    val permitirEnvioPdfIa: Boolean,
    val avisos: List<String>,
    val errorDetalle: String?,
    val nombreArchivo: String?,
    val creadaEn: OffsetDateTime,
    val confirmadaEn: OffsetDateTime?,
    val resumen: ResumenMaterialesDto,
)

data class MaterialDto(
    val id: UUID,
    val posicion: Int,
    @field:Schema(description = "Fragmento literal del PDF") val textoOriginal: String?,
    val nombre: String,
    val categoria: Categoria,
    val unidad: Unidad,
    val presentacion: String?,
    val seccion: SeccionOrigen,
    val abastece: Abastece,
    val esConsumible: Boolean,
    val pagina: Int?,
    val origen: OrigenMaterial,
    val confianza: Confianza,
    val motivoRevision: String?,
    val seleccionadoPorDefecto: Boolean,
    @field:Schema(description = "Si se convertirá en producto al confirmar") val seleccionado: Boolean,
    val estado: EstadoMaterial,
    @field:Schema(description = "Posible duplicado: se sugiere fusionarlo con este material") val fusionarConId: UUID?,
    val productoId: UUID?,
)

data class GrupoMaterialesDto(val seccion: SeccionOrigen, val items: List<MaterialDto>)

data class EditarMaterialRequest(
    @field:Size(min = 1, max = 200) val nombre: String? = null,
    val categoria: Categoria? = null,
    val unidad: Unidad? = null,
    @field:Size(max = 100) val presentacion: String? = null,
    val abastece: Abastece? = null,
    val esConsumible: Boolean? = null,
    @field:Schema(description = "ACEPTADO, DESCARTADO o PENDIENTE. Editar cualquier campo lo deja EDITADO.")
    val estado: EstadoMaterial? = null,
)

data class FusionarMaterialesRequest(
    @field:NotNull @field:Size(min = 2, message = "Indica al menos dos materiales")
    @field:Schema(description = "El primero se conserva; los demás quedan DESCARTADOS")
    val materialIds: List<UUID>?,
    @field:Size(max = 200) val nombre: String? = null,
)

data class MaterialManualRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 200) val nombre: String,
    @field:NotNull val categoria: Categoria,
    @field:NotNull val unidad: Unidad,
    @field:Size(max = 100) val presentacion: String? = null,
    val abastece: Abastece? = null,
    val esConsumible: Boolean? = null,
)

data class ReintentarIaRequest(
    @field:Schema(description = "Autoriza enviar el PDF completo a la IA (solo se usa si el PDF no tiene texto)")
    val permitirEnvioPdfIa: Boolean? = null,
)

data class ConfirmacionDto(
    val ordenId: UUID,
    val estado: EstadoOrden,
    @field:Schema(description = "true si la orden ya estaba confirmada (no se creó nada nuevo)") val yaConfirmada: Boolean,
    val creados: Int,
    @field:Schema(description = "Materiales enlazados a un producto que ya existía con el mismo nombre") val vinculados: Int,
    val productos: List<ProductoDto>,
)

data class TextoOrdenDto(val texto: String?)
