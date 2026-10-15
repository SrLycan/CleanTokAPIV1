package ec.cleantok.catalogo.api

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.OrigenProducto
import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.catalogo.dominio.Unidad
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

data class ProductoRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 200)
    @field:Schema(example = "Cloro líquido 5.5%")
    val nombre: String,
    @field:NotNull(message = "La categoría es obligatoria") val categoria: Categoria,
    @field:Schema(description = "Si se consume (químicos, papel). Las herramientas no son consumibles. Por defecto: true")
    val esConsumible: Boolean? = null,
    @field:NotNull(message = "La unidad es obligatoria") @field:Schema(example = "L") val unidad: Unidad,
    @field:Size(max = 100) @field:Schema(example = "Galón 3.785 L") val presentacion: String? = null,
    @field:Schema(description = "Quién repone el producto. Por defecto: EMPRESA_LIMPIEZA") val abastece: Abastece? = null,
    @field:PositiveOrZero(message = "El stock mínimo no puede ser negativo")
    @field:Digits(integer = 11, fraction = 3, message = "Máximo 3 decimales")
    @field:Schema(description = "Umbral de stock en bodega para la alerta STOCK_BAJO", example = "5")
    val stockMinimo: BigDecimal? = null,
)

data class ProductoDto(
    val id: UUID,
    val establecimientoId: UUID,
    val nombre: String,
    val categoria: Categoria,
    val esConsumible: Boolean,
    val unidad: Unidad,
    @field:Schema(description = "Unidades en que se pueden registrar movimientos de este producto")
    val unidadesCompatibles: List<Unidad>,
    val presentacion: String?,
    val abastece: Abastece,
    val stockMinimo: BigDecimal?,
    val origen: OrigenProducto,
    val materialExtraidoId: UUID?,
    val activo: Boolean,
) {
    companion object {
        fun de(p: Producto) = ProductoDto(
            p.id, p.establecimientoId, p.nombre, p.categoria, p.esConsumible, p.unidad, p.unidad.compatibles(),
            p.presentacion, p.abastece, p.stockMinimo, p.origen, p.materialExtraidoId, p.activo,
        )
    }
}
