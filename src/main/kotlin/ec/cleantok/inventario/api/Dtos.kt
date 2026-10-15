package ec.cleantok.inventario.api

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.inventario.dominio.SentidoAjuste
import ec.cleantok.inventario.dominio.TipoMovimiento
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Schema(
    description = """Movimiento registrado desde un QR. `ubicacionId` es la ubicación escaneada:
- INGRESO: la bodega (suma en bodega).
- TRASLADO ("Agregar" en un piso): el piso (resta en bodega, suma en el piso).
- CONSUMO ("Retirar" en un piso): el piso (resta en el piso).
- DEVOLUCION: el piso (resta en el piso, suma en bodega).
- AJUSTE (solo admin): cualquier ubicación, con `sentido` y `motivo` obligatorios.""",
)
data class MovimientoRequest(
    @field:NotNull(message = "clientUuid es obligatorio")
    @field:Schema(description = "Generado por Android; garantiza idempotencia", example = "3f1c2a4e-8b7d-4c1e-9f0a-1b2c3d4e5f60")
    val clientUuid: UUID?,
    @field:NotNull(message = "El tipo es obligatorio") val tipo: TipoMovimiento?,
    @field:NotNull(message = "El producto es obligatorio") val productoId: UUID?,
    @field:NotNull(message = "La ubicación es obligatoria") val ubicacionId: UUID?,
    @field:NotNull(message = "La cantidad es obligatoria")
    @field:Positive(message = "La cantidad debe ser mayor que 0")
    @field:Digits(integer = 11, fraction = 3, message = "La cantidad admite máximo 3 decimales")
    @field:DecimalMax(value = "99999999999.999", message = "La cantidad es demasiado grande")
    @field:Schema(example = "3.5")
    val cantidad: BigDecimal?,
    @field:Schema(description = "Unidad en que se expresa la cantidad. Por defecto, la del producto.", example = "L")
    val unidad: Unidad? = null,
    @field:NotNull(message = "ocurridoEn es obligatorio")
    @field:Schema(description = "Hora del dispositivo, con zona", example = "2026-10-16T15:20:00-05:00")
    val ocurridoEn: OffsetDateTime?,
    @field:Size(max = 500) val nota: String? = null,
    @field:Size(max = 500) @field:Schema(description = "Obligatorio en AJUSTE") val motivo: String? = null,
    @field:Schema(description = "Solo AJUSTE: ENTRADA suma, SALIDA resta") val sentido: SentidoAjuste? = null,
    @field:Schema(description = "Id devuelto por POST /fotos") val fotoId: UUID? = null,
)

data class RefDto(val id: UUID, val nombre: String)

data class UbicacionRefDto(val id: UUID, val nombre: String, val tipo: TipoUbicacion)

data class ProductoRefDto(val id: UUID, val nombre: String, val unidad: Unidad)

data class MovimientoDto(
    val id: UUID,
    val clientUuid: UUID,
    val tipo: TipoMovimiento,
    val producto: ProductoRefDto,
    val origen: UbicacionRefDto?,
    val destino: UbicacionRefDto?,
    @field:Schema(description = "Cantidad en la unidad del producto") val cantidad: BigDecimal,
    val unidad: Unidad,
    @field:Schema(description = "Cantidad tal como se registró") val cantidadOriginal: BigDecimal,
    val unidadOriginal: Unidad,
    val ocurridoEn: OffsetDateTime,
    val registradoEn: OffsetDateTime,
    val usuario: RefDto,
    val nota: String?,
    val motivo: String?,
    val fotoId: UUID?,
    val saldoResultanteOrigen: BigDecimal?,
    val saldoResultanteDestino: BigDecimal?,
)

data class SaldoDto(val ubicacion: UbicacionRefDto, val cantidad: BigDecimal)

data class MovimientoResultadoDto(
    val movimiento: MovimientoDto,
    @field:Schema(description = "Saldos resultantes en las ubicaciones afectadas") val saldos: List<SaldoDto>,
    @field:Schema(description = "true si el clientUuid ya existía: no se aplicó de nuevo") val duplicado: Boolean,
    @field:Schema(description = "true tras un CONSUMO que deja el piso en el umbral (25 % del último traslado) o menos: Android ofrece reportar el nivel")
    val sugerirReporteNivel: Boolean,
    @field:Schema(description = "Alertas abiertas por este movimiento") val alertas: List<AlertaResumenDto> = emptyList(),
)

/** Resumen de alerta para respuestas de movimientos, QR y reportes (lo llena el módulo de monitoreo). */
data class AlertaResumenDto(
    val id: UUID,
    val tipo: String,
    val severidad: String,
    val mensaje: String,
    val productoId: UUID,
    val ubicacionId: UUID?,
)

// ---- Sincronización offline ----

enum class TipoItemLote { MOVIMIENTO, REPORTE_NIVEL }

enum class ResultadoItemLote { OK, DUPLICADO, ERROR }

data class LoteRequest(
    @field:NotNull(message = "items es obligatorio")
    @field:Size(min = 1, max = 100, message = "El lote admite entre 1 y 100 ítems")
    val items: List<ItemLoteRequest>?,
)

data class ItemLoteRequest(
    @field:NotNull(message = "El tipo de ítem es obligatorio") val tipo: TipoItemLote?,
    val movimiento: MovimientoRequest? = null,
    val reporteNivel: ReporteNivelRequest? = null,
)

@Schema(description = "Reporte del nivel de un producto en un piso (0, 25, 50, 75 o 100 %)")
data class ReporteNivelRequest(
    @field:NotNull(message = "clientUuid es obligatorio") val clientUuid: UUID?,
    @field:NotNull(message = "El producto es obligatorio") val productoId: UUID?,
    @field:NotNull(message = "La ubicación es obligatoria") @field:Schema(description = "Piso o área (no la bodega)") val ubicacionId: UUID?,
    @field:NotNull(message = "El nivel es obligatorio")
    @field:Schema(allowableValues = ["0", "25", "50", "75", "100"], example = "25")
    val nivelPct: Int?,
    @field:Schema(description = "Id devuelto por POST /fotos") val fotoId: UUID? = null,
    @field:NotNull(message = "ocurridoEn es obligatorio") @field:Schema(example = "2026-10-16T15:25:00-05:00") val ocurridoEn: OffsetDateTime?,
    @field:Size(max = 500) val nota: String? = null,
)

data class ItemLoteResultadoDto(
    @field:Schema(description = "Posición del ítem en la petición original") val indice: Int,
    val clientUuid: UUID?,
    val resultado: ResultadoItemLote,
    val id: UUID? = null,
    val codigo: String? = null,
    val mensaje: String? = null,
    @field:Schema(description = "Datos extra del error (por ejemplo `disponible` en STOCK_INSUFICIENTE)")
    val detalle: Map<String, Any?>? = null,
)

data class LoteResultadoDto(val procesados: Int, val ok: Int, val duplicados: Int, val errores: Int, val items: List<ItemLoteResultadoDto>)

// ---- Conteo físico ----

data class ConteoItemRequest(
    @field:NotNull(message = "El producto es obligatorio") val productoId: UUID?,
    @field:NotNull(message = "La cantidad es obligatoria")
    @field:PositiveOrZero(message = "La cantidad no puede ser negativa")
    @field:Digits(integer = 11, fraction = 3, message = "La cantidad admite máximo 3 decimales")
    @field:Schema(description = "Lo que hay ahora, en la unidad del producto", example = "4")
    val cantidad: BigDecimal?,
)

data class ConteoRequest(
    @field:NotNull(message = "items es obligatorio")
    @field:Size(min = 1, max = 200, message = "El conteo admite entre 1 y 200 productos")
    @field:Valid
    val items: List<ConteoItemRequest>?,
    @field:Size(max = 500) val nota: String? = null,
)

data class ConteoResultadoDto(
    @field:Schema(description = "Productos cuyo saldo cambió (un AJUSTE cada uno)") val ajustados: Int,
    @field:Schema(description = "Productos que ya tenían esa cantidad") val sinCambio: Int,
    val movimientos: List<MovimientoDto>,
    val alertas: List<AlertaResumenDto>,
)

// ---- Inventario ----

enum class EstadoInventario { OK, BAJO, CRITICO, SIN_DATOS }

data class StockUbicacionDto(val ubicacion: UbicacionRefDto, val cantidad: BigDecimal)

data class InventarioItemDto(
    val producto: ProductoRefDto,
    val categoria: Categoria,
    val esConsumible: Boolean,
    val abastece: Abastece,
    val presentacion: String?,
    val stockMinimo: BigDecimal?,
    val stockBodega: BigDecimal,
    val stockPisos: List<StockUbicacionDto>,
    val stockTotal: BigDecimal,
    val consumo7Dias: BigDecimal,
    val consumo30Dias: BigDecimal,
    @field:Schema(description = "Consumo diario promedio de los últimos 14 días; null si no hay datos suficientes")
    val consumoDiarioPromedio: BigDecimal?,
    @field:Schema(description = "(stock bodega + pisos) ÷ consumo diario; null si no hay datos suficientes")
    val diasCobertura: BigDecimal?,
    val estado: EstadoInventario,
)

data class InventarioDto(
    val establecimiento: RefDto,
    val generadoEn: OffsetDateTime,
    val diasCoberturaMinimo: Int,
    val items: List<InventarioItemDto>,
)

// ---- Kardex ----

data class KardexItemDto(
    val movimiento: MovimientoDto,
    @field:Schema(description = "Con filtro ubicacionId: cantidad que entró a esa ubicación") val entrada: BigDecimal?,
    @field:Schema(description = "Con filtro ubicacionId: cantidad que salió de esa ubicación") val salida: BigDecimal?,
    @field:Schema(description = "Con filtro ubicacionId: saldo de esa ubicación después del movimiento") val saldo: BigDecimal?,
)
