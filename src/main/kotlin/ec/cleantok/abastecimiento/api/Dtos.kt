package ec.cleantok.abastecimiento.api

import ec.cleantok.abastecimiento.dominio.EstadoEntrega
import ec.cleantok.abastecimiento.dominio.EstadoOferta
import ec.cleantok.abastecimiento.dominio.EstadoOrden
import ec.cleantok.abastecimiento.dominio.EstadoSolicitud
import ec.cleantok.abastecimiento.dominio.Modalidad
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.inventario.api.EstadoInventario
import ec.cleantok.inventario.api.ProductoRefDto
import ec.cleantok.inventario.api.RefDto
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import ec.cleantok.identidad.api.UsuarioDto
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// ---- Proveedores ----

data class ProveedorDto(
    val id: UUID,
    val nombre: String,
    val ruc: String,
    val telefono: String?,
    val direccion: String,
    val bodegaDireccion: String?,
    @field:Schema(description = "Cómo puede entregar este proveedor") val modalidades: List<Modalidad>,
    @field:Schema(description = "Preferido por la empresa (solo para el admin)") val preferido: Boolean?,
    val lat: BigDecimal? = null,
    val lng: BigDecimal? = null,
    val bodegaLat: BigDecimal? = null,
    val bodegaLng: BigDecimal? = null,
)

data class DirectorioProveedorDto(val proveedor: ProveedorDto, val vinculado: Boolean)

data class VinculoRequest(val preferido: Boolean = false)

data class ProveedorPlataformaDto(val proveedor: ProveedorDto, val activo: Boolean, val empresas: Int, val usuarios: Long)

data class ProveedorCreadoDto(
    val proveedor: ProveedorDto,
    @field:Schema(description = "Primer usuario de ventas, con su clave temporal (se muestra una sola vez)") val usuario: UsuarioDto,
)

data class CrearProveedorRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 200) val nombre: String,
    @field:Pattern(regexp = "\\d{13}", message = "El RUC debe tener 13 dígitos") val ruc: String,
    @field:Size(max = 30) val telefono: String? = null,
    @field:NotBlank(message = "La dirección del local es obligatoria") @field:Size(max = 500) val direccion: String,
    @field:NotNull(message = "Ubica el local en el mapa") @field:DecimalMin("-90") @field:DecimalMax("90") val lat: BigDecimal?,
    @field:NotNull(message = "Ubica el local en el mapa") @field:DecimalMin("-180") @field:DecimalMax("180") val lng: BigDecimal?,
    @field:Size(max = 500) val bodegaDireccion: String? = null,
    @field:DecimalMin("-90") @field:DecimalMax("90") val bodegaLat: BigDecimal? = null,
    @field:DecimalMin("-180") @field:DecimalMax("180") val bodegaLng: BigDecimal? = null,
    @field:NotEmpty(message = "Elige al menos una forma de entrega") val modalidades: List<Modalidad>?,
    @field:NotBlank(message = "El nombre del usuario es obligatorio") @field:Size(max = 200) val usuarioNombre: String,
    @field:NotBlank(message = "El email del usuario es obligatorio") @field:Email(message = "El email no es válido") val usuarioEmail: String,
)

@Schema(description = "Solo se cambian los campos enviados. `bodegaDireccion` vacía quita la bodega.")
data class ActualizarPerfilRequest(
    @field:Size(max = 30) val telefono: String? = null,
    @field:Size(max = 500) val direccion: String? = null,
    @field:DecimalMin("-90") @field:DecimalMax("90") val lat: BigDecimal? = null,
    @field:DecimalMin("-180") @field:DecimalMax("180") val lng: BigDecimal? = null,
    @field:Size(max = 500) val bodegaDireccion: String? = null,
    @field:DecimalMin("-90") @field:DecimalMax("90") val bodegaLat: BigDecimal? = null,
    @field:DecimalMin("-180") @field:DecimalMax("180") val bodegaLng: BigDecimal? = null,
    val modalidades: List<Modalidad>? = null,
)

data class MiembroRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 200) val nombre: String,
    @field:NotBlank(message = "El email es obligatorio") @field:Email(message = "El email no es válido") val email: String,
    @field:Schema(description = "REPARTIDOR (por defecto) o PROVEEDOR (ventas)") val rol: Rol? = null,
)

data class ActivoRequest(@field:NotNull val activo: Boolean?)

// ---- Sugerencias de reposición ----

data class SugerenciaDto(
    val establecimiento: RefDto,
    val producto: ProductoRefDto,
    val estado: EstadoInventario,
    val stockBodega: BigDecimal,
    val stockTotal: BigDecimal,
    val consumoDiario: BigDecimal?,
    val diasCobertura: BigDecimal?,
    @field:Schema(description = "consumo diario × (entrega + ciclo + seguridad) − stock actual, redondeado hacia arriba")
    val cantidadRecomendada: BigDecimal,
    @field:Schema(example = "Cobertura de 4,6 días (mínimo 7)") val motivo: String,
    @field:Schema(description = "Código de la solicitud u orden abierta que ya incluye este producto") val yaPedido: String?,
)

// ---- Solicitudes ----

data class LineaSolicitudRequest(
    @field:NotNull(message = "El producto es obligatorio") val productoId: UUID?,
    @field:NotNull(message = "La cantidad es obligatoria")
    @field:Positive(message = "La cantidad debe ser mayor que 0")
    @field:Digits(integer = 11, fraction = 3)
    val cantidad: BigDecimal?,
)

data class CrearSolicitudRequest(
    @field:Schema(description = "Por defecto, dentro de los días de entrega configurados") val fechaRequerida: LocalDate? = null,
    @field:Size(max = 500) val nota: String? = null,
    @field:NotEmpty(message = "Agrega al menos un producto") @field:Valid val lineas: List<LineaSolicitudRequest>?,
    @field:Schema(description = "true: la envía a cotizar de inmediato") val enviar: Boolean = false,
)

data class SolicitudResumenDto(
    val id: UUID,
    val codigo: String,
    val estado: EstadoSolicitud,
    val fechaRequerida: LocalDate,
    val creadaEn: OffsetDateTime,
    val enviadaEn: OffsetDateTime?,
    val productos: Int,
    val destinos: Int,
    val invitados: Int,
    val ofertas: Int,
)

data class DestinoDto(
    val lineaId: UUID,
    val establecimiento: RefDto,
    val productoId: UUID,
    val cantidad: BigDecimal,
    val cantidadRecomendada: BigDecimal?,
)

@Schema(description = "Una oferta para un producto, con su puntaje. Criterios: entrega 40 %, disponibilidad 30 %, costo 25 %, preferido 5 %.")
data class CotizacionDto(
    val ofertaId: UUID,
    val proveedor: RefDto,
    val precioUnitario: BigDecimal,
    val cantidadDisponible: BigDecimal,
    @field:Schema(description = "precio × lo que puede entregar") val total: BigDecimal,
    val completo: Boolean,
    val plazoDias: Int,
    val modalidad: Modalidad,
    val preferido: Boolean,
    @field:Schema(description = "0 a 100") val puntaje: Int,
    val recomendada: Boolean,
)

data class SolicitudItemDto(
    val id: UUID,
    val nombre: String,
    val unidad: Unidad,
    val cantidad: BigDecimal,
    val destinos: List<DestinoDto>,
    @field:Schema(description = "Ofertas ordenadas de mayor a menor puntaje") val cotizaciones: List<CotizacionDto>,
)

data class OfertaItemDto(val itemId: UUID, val cantidadDisponible: BigDecimal, val precioUnitario: BigDecimal)

data class OfertaDto(
    val id: UUID,
    val proveedor: RefDto,
    val modalidad: Modalidad,
    val plazoDias: Int,
    val nota: String?,
    val estado: EstadoOferta,
    val enviadaEn: OffsetDateTime,
    val total: BigDecimal,
    val items: List<OfertaItemDto>,
)

data class SolicitudDto(
    val id: UUID,
    val codigo: String,
    val estado: EstadoSolicitud,
    val fechaRequerida: LocalDate,
    val nota: String?,
    val creadaEn: OffsetDateTime,
    val enviadaEn: OffsetDateTime?,
    val adjudicadaEn: OffsetDateTime?,
    val items: List<SolicitudItemDto>,
    val invitados: List<RefDto>,
    val ofertas: List<OfertaDto>,
    val ordenes: List<OrdenResumenDto>,
)

data class AsignacionRequest(
    @field:NotNull val itemId: UUID?,
    @field:NotNull val ofertaId: UUID?,
)

data class AdjudicarRequest(
    @field:Schema(description = "Proveedor elegido por producto. Si se omite, se usa la oferta recomendada de cada uno.")
    @field:Valid
    val asignaciones: List<AsignacionRequest>? = null,
)

// ---- Vista del proveedor ----

data class DestinoProveedorDto(val establecimiento: RefDto, val direccion: String?, val cantidad: BigDecimal)

data class ItemProveedorDto(val id: UUID, val nombre: String, val unidad: Unidad, val cantidad: BigDecimal, val destinos: List<DestinoProveedorDto>)

@Schema(description = "Empresa que cotiza o compra, como información para el proveedor")
data class EmpresaContactoDto(
    val id: UUID,
    val razonSocial: String,
    val ruc: String,
    val telefono: String?,
    @field:Schema(description = "Correo de la empresa o, si no lo registró, el de su administrador") val email: String?,
    val direccion: String?,
    val lat: BigDecimal?,
    val lng: BigDecimal?,
)

data class SolicitudProveedorDto(
    val id: UUID,
    val codigo: String,
    val empresa: RefDto,
    val estado: EstadoSolicitud,
    @field:Schema(description = "Fecha de entrega que fija la empresa; el proveedor no la cambia")
    val fechaRequerida: LocalDate,
    val nota: String?,
    val enviadaEn: OffsetDateTime?,
    val items: List<ItemProveedorDto>,
    @field:Schema(description = "Mi oferta, si ya la envié (nunca se muestran las de otros proveedores)") val miOferta: OfertaDto?,
    val modalidadesDisponibles: List<Modalidad>,
    val puedeOfertar: Boolean,
    @field:Schema(description = "Razón social, RUC, teléfono, correo, dirección y ubicación de la empresa")
    val empresaContacto: EmpresaContactoDto? = null,
    @field:Schema(description = "Días hasta la fecha requerida: es el plazo con el que se registra la oferta")
    val plazoDias: Int = 0,
)

data class OfertaItemRequest(
    @field:NotNull val itemId: UUID?,
    @field:NotNull @field:Positive(message = "La cantidad debe ser mayor que 0") @field:Digits(integer = 11, fraction = 3)
    val cantidadDisponible: BigDecimal?,
    @field:NotNull @field:Positive(message = "El precio debe ser mayor que 0") @field:Digits(integer = 8, fraction = 4)
    val precioUnitario: BigDecimal?,
)

data class OfertaRequest(
    @field:Schema(description = "Opcional: si se omite, la que ya tenía la oferta o la del proveedor (envío directo si lo ofrece)")
    val modalidad: Modalidad? = null,
    @field:Schema(description = "Se ignora: el plazo sale de la fecha requerida que fijó la empresa")
    @field:Min(0) @field:Max(60) val plazoDias: Int? = null,
    @field:Size(max = 500) val nota: String? = null,
    @field:NotEmpty(message = "Oferta al menos un producto") @field:Valid val items: List<OfertaItemRequest>?,
)

// ---- Órdenes de compra y tracking ----

data class EventoDto(
    val estado: String,
    val titulo: String,
    val detalle: String?,
    val usuario: RefDto,
    val rol: Rol,
    val ocurridoEn: OffsetDateTime,
    @field:Schema(description = "Destino al que se refiere, si aplica") val destino: RefDto?,
)

@Schema(description = "Paso de la línea de tiempo: hecho (con fecha), actual o pendiente")
data class PasoDto(val estado: EstadoOrden, val titulo: String, val fecha: OffsetDateTime?, val hecho: Boolean, val actual: Boolean)

data class LineaOrdenDto(
    val id: UUID,
    val producto: ProductoRefDto,
    val cantidad: BigDecimal,
    val unidad: Unidad,
    val precioUnitario: BigDecimal,
    val subtotal: BigDecimal,
    val cantidadRecibida: BigDecimal?,
)

data class EntregaDto(
    val id: UUID,
    val parada: Int,
    val establecimiento: RefDto,
    val direccion: String?,
    val lat: BigDecimal?,
    val lng: BigDecimal?,
    val estado: EstadoEntrega,
    val entregadaEn: OffsetDateTime?,
    val recibidaEn: OffsetDateTime?,
    val recibidaPor: RefDto?,
    val completa: Boolean?,
    val nota: String?,
    val fotoId: UUID?,
    val lineas: List<LineaOrdenDto>,
)

data class SiguienteEstadoDto(val estado: EstadoOrden, val titulo: String)

@Schema(description = "Lo que el usuario actual puede hacer con la orden")
data class AccionesOrdenDto(
    val siguientes: List<SiguienteEstadoDto>,
    val asignarRepartidor: Boolean,
    @field:Schema(description = "Entregas que puede marcar como dejadas (repartidor/proveedor)") val marcarEntregada: List<UUID>,
    @field:Schema(description = "Entregas cuya recepción puede confirmar (encargado/admin)") val recibir: List<UUID>,
)

data class PuntoDto(val nombre: String, val direccion: String?, val lat: BigDecimal?, val lng: BigDecimal?)

data class OrdenResumenDto(
    val id: UUID,
    val codigo: String,
    val estado: EstadoOrden,
    val modalidad: Modalidad,
    val empresa: RefDto,
    val proveedor: RefDto,
    val total: BigDecimal,
    val creadaEn: OffsetDateTime,
    val destinos: Int,
    val recibidas: Int,
    val repartidor: RefDto?,
    val ultimoEvento: EventoDto?,
)

data class OrdenDto(
    val id: UUID,
    val codigo: String,
    val estado: EstadoOrden,
    val modalidad: Modalidad,
    val empresa: RefDto,
    val proveedor: RefDto,
    val solicitud: RefDto,
    val total: BigDecimal,
    val plazoDias: Int,
    val creadaEn: OffsetDateTime,
    val repartidor: RefDto?,
    @field:Schema(description = "Dónde retirar (solo en retiro)") val puntoRetiro: PuntoDto?,
    val pasos: List<PasoDto>,
    val eventos: List<EventoDto>,
    val entregas: List<EntregaDto>,
    val acciones: AccionesOrdenDto,
    @field:Schema(description = "La cotización que originó la orden (no se muestra al repartidor)") val cotizacion: CotizacionOrdenDto? = null,
    @field:Schema(description = "Datos de la empresa compradora (para el proveedor)") val empresaContacto: EmpresaContactoDto? = null,
)

data class ItemCotizadoDto(
    val nombre: String,
    val unidad: Unidad,
    val cantidadSolicitada: BigDecimal,
    val cantidadOfertada: BigDecimal,
    val precioUnitario: BigDecimal,
    @field:Schema(description = "Este producto quedó en esta orden") val enEstaOrden: Boolean,
)

@Schema(description = "Solicitud y oferta que dieron origen a la orden")
data class CotizacionOrdenDto(
    val solicitud: RefDto,
    val fechaRequerida: LocalDate,
    val notaSolicitud: String?,
    val solicitadaEn: OffsetDateTime?,
    val ofertaEnviadaEn: OffsetDateTime,
    val ofertadaPor: RefDto?,
    val modalidad: Modalidad,
    val plazoDias: Int,
    val notaOferta: String?,
    val totalOfertado: BigDecimal,
    val items: List<ItemCotizadoDto>,
)

data class CambiarEstadoRequest(
    @field:NotNull(message = "El estado es obligatorio") val estado: EstadoOrden?,
    @field:Size(max = 500) val nota: String? = null,
)

data class AsignarRepartidorRequest(@field:NotNull val repartidorId: UUID?)

data class MarcarEntregadaRequest(@field:Size(max = 500) val nota: String? = null)

data class LineaRecibidaRequest(
    @field:NotNull val lineaId: UUID?,
    @field:NotNull @field:PositiveOrZero @field:Digits(integer = 11, fraction = 3) val cantidadRecibida: BigDecimal?,
)

data class RecibirRequest(
    @field:Schema(description = "Cantidades recibidas por línea. Si se omite, se recibió todo lo pedido.")
    @field:Valid
    val lineas: List<LineaRecibidaRequest>? = null,
    @field:Size(max = 500) val nota: String? = null,
    @field:Schema(description = "Foto de la guía de remisión (POST /fotos)") val fotoId: UUID? = null,
)

data class UsuarioRefDto(val id: UUID, val nombre: String, val email: String)

// ---- Rutas ----

data class ResumenRutaDto(val km: BigDecimal, val minutos: Int, val co2Kg: BigDecimal)

data class ParadaDto(
    val orden: Int,
    val establecimiento: RefDto,
    val direccion: String?,
    val lat: BigDecimal,
    val lng: BigDecimal,
    val estado: EstadoEntrega,
    @field:Schema(description = "Órdenes que se entregan en esta parada") val ordenes: List<RefDto>,
    @field:Schema(description = "Entregas de esta parada (para marcarlas)") val entregas: List<UUID>,
    @field:Schema(description = "Km desde la parada anterior (o el origen)") val kmTramo: BigDecimal,
)

data class RutaDto(
    val origen: PuntoDto,
    @field:Schema(description = "Paradas en el orden optimizado") val paradas: List<ParadaDto>,
    @field:Schema(description = "Orden de las paradas sin optimizar (índices de `paradas`)") val ordenBase: List<Int>,
    val base: ResumenRutaDto,
    val optimizada: ResumenRutaDto,
    val ahorroKm: BigDecimal,
    val ahorroPct: Int,
    val ahorroCo2Kg: BigDecimal,
    @field:Schema(description = "Cómo se calcula") val metodo: String,
    @field:Schema(description = "OSRM: distancias y tiempos por calles; ESTIMADA: línea recta × factor") val fuente: String,
    @field:Schema(description = "Destinos sin coordenadas: no entran en la ruta") val sinCoordenadas: List<RefDto>,
)
