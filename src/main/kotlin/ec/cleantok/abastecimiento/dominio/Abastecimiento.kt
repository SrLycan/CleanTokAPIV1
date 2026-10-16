package ec.cleantok.abastecimiento.dominio

import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.entidad.EntidadBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class EstadoSolicitud { BORRADOR, EN_COTIZACION, ADJUDICADA, CANCELADA }

/** Cómo llega el pedido: el proveedor lo lleva a cada establecimiento, o la empresa lo retira. */
enum class Modalidad { ENVIO_DIRECTO, RETIRO_LOCAL, RETIRO_BODEGA }

enum class EstadoOferta { ENVIADA, ADJUDICADA, NO_ADJUDICADA }

/**
 * Estados de la orden de compra. Envío directo: APROBADA (pagada) → EN_PREPARACION → EMPACADA → DESPACHADA
 * ("salió de bodega") → EN_TRANSITO → ENTREGADA. Retiro: … → EMPACADA → LISTA_PARA_RETIRO → RETIRADA → ENTREGADA.
 * ENTREGADA llega sola cuando todos los destinos confirman la recepción.
 */
enum class EstadoOrden {
    APROBADA, ACEPTADA, RECHAZADA, EN_PREPARACION, EMPACADA, LISTA_PARA_RETIRO, RETIRADA, DESPACHADA, EN_TRANSITO, ENTREGADA,
}

/** PENDIENTE → ENTREGADA (el repartidor la dejó) → RECIBIDA (el encargado confirmó y entró a bodega). */
enum class EstadoEntrega { PENDIENTE, ENTREGADA, RECIBIDA }

@Entity
@Table(name = "empresa_proveedor")
class EmpresaProveedor(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "proveedor_id", nullable = false, updatable = false) val proveedorId: UUID,
    @Column(nullable = false) var preferido: Boolean = false,
) : EntidadBase()

@Entity
@Table(name = "solicitud")
class Solicitud(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(nullable = false, updatable = false) val codigo: String,
    @Column(name = "fecha_requerida", nullable = false) var fechaRequerida: LocalDate,
    var nota: String?,
    @Column(name = "creada_por", nullable = false, updatable = false) val creadaPor: UUID,
) : EntidadBase() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var estado: EstadoSolicitud = EstadoSolicitud.BORRADOR

    @Column(name = "enviada_en")
    var enviadaEn: Instant? = null

    @Column(name = "adjudicada_en")
    var adjudicadaEn: Instant? = null
}

/** Producto consolidado: suma de las líneas de todos los destinos con el mismo nombre y unidad. */
@Entity
@Table(name = "solicitud_item")
class SolicitudItem(
    @Column(name = "solicitud_id", nullable = false, updatable = false) val solicitudId: UUID,
    @Column(nullable = false) val nombre: String,
    @Column(name = "nombre_normalizado", nullable = false) val nombreNormalizado: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val unidad: Unidad,
    @Column(nullable = false) var cantidad: BigDecimal,
) : EntidadBase()

@Entity
@Table(name = "solicitud_linea")
class SolicitudLinea(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "solicitud_id", nullable = false, updatable = false) val solicitudId: UUID,
    @Column(name = "item_id", nullable = false, updatable = false) val itemId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
    @Column(name = "producto_id", nullable = false, updatable = false) val productoId: UUID,
    @Column(nullable = false) var cantidad: BigDecimal,
    @Column(name = "cantidad_recomendada") val cantidadRecomendada: BigDecimal?,
) : EntidadBase()

@Entity
@Table(name = "solicitud_proveedor")
class SolicitudProveedor(
    @Column(name = "solicitud_id", nullable = false, updatable = false) val solicitudId: UUID,
    @Column(name = "proveedor_id", nullable = false, updatable = false) val proveedorId: UUID,
) : EntidadBase()

@Entity
@Table(name = "oferta")
class Oferta(
    @Column(name = "solicitud_id", nullable = false, updatable = false) val solicitudId: UUID,
    @Column(name = "proveedor_id", nullable = false, updatable = false) val proveedorId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var modalidad: Modalidad,
    @Column(name = "plazo_dias", nullable = false) var plazoDias: Int,
    var nota: String?,
    @Column(name = "usuario_id", nullable = false) var usuarioId: UUID,
    @Column(name = "enviada_en", nullable = false) var enviadaEn: Instant,
) : EntidadBase() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var estado: EstadoOferta = EstadoOferta.ENVIADA
}

@Entity
@Table(name = "oferta_item")
class OfertaItem(
    @Column(name = "oferta_id", nullable = false, updatable = false) val ofertaId: UUID,
    @Column(name = "item_id", nullable = false, updatable = false) val itemId: UUID,
    @Column(name = "cantidad_disponible", nullable = false) val cantidadDisponible: BigDecimal,
    @Column(name = "precio_unitario", nullable = false) val precioUnitario: BigDecimal,
) : EntidadBase()

@Entity
@Table(name = "orden_compra")
class OrdenCompra(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "proveedor_id", nullable = false, updatable = false) val proveedorId: UUID,
    @Column(name = "solicitud_id", nullable = false, updatable = false) val solicitudId: UUID,
    @Column(name = "oferta_id", nullable = false, updatable = false) val ofertaId: UUID,
    @Column(nullable = false, updatable = false) val codigo: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val modalidad: Modalidad,
    @Column(nullable = false) var total: BigDecimal,
    @Column(name = "plazo_dias", nullable = false, updatable = false) val plazoDias: Int,
    @Column(name = "aprobada_por", nullable = false, updatable = false) val aprobadaPor: UUID,
) : EntidadBase() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var estado: EstadoOrden = EstadoOrden.APROBADA

    @Column(name = "repartidor_id")
    var repartidorId: UUID? = null

    val esEnvio: Boolean get() = modalidad == Modalidad.ENVIO_DIRECTO
}

@Entity
@Table(name = "entrega")
class Entrega(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "orden_id", nullable = false, updatable = false) val ordenId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
    @Column(nullable = false) var parada: Int,
) : EntidadBase() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var estado: EstadoEntrega = EstadoEntrega.PENDIENTE

    @Column(name = "entregada_en")
    var entregadaEn: Instant? = null

    @Column(name = "recibida_en")
    var recibidaEn: Instant? = null

    @Column(name = "recibida_por")
    var recibidaPor: UUID? = null

    var completa: Boolean? = null

    var nota: String? = null

    @Column(name = "foto_id")
    var fotoId: UUID? = null
}

@Entity
@Table(name = "orden_linea")
class OrdenLinea(
    @Column(name = "orden_id", nullable = false, updatable = false) val ordenId: UUID,
    @Column(name = "entrega_id", nullable = false, updatable = false) val entregaId: UUID,
    @Column(name = "solicitud_linea_id", nullable = false, updatable = false) val solicitudLineaId: UUID,
    @Column(name = "producto_id", nullable = false, updatable = false) val productoId: UUID,
    @Column(nullable = false, updatable = false) val cantidad: BigDecimal,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val unidad: Unidad,
    @Column(name = "precio_unitario", nullable = false, updatable = false) val precioUnitario: BigDecimal,
) : EntidadBase() {
    @Column(name = "cantidad_recibida")
    var cantidadRecibida: BigDecimal? = null

    /** INGRESO a bodega generado al confirmar la recepción: une la compra con el kardex. */
    @Column(name = "movimiento_id")
    var movimientoId: UUID? = null
}

/** Un paso del rastreo del pedido. Inalterable (trigger en la base). */
@Entity
@Immutable
@Table(name = "evento_orden")
class EventoOrden(
    @Column(name = "orden_id", nullable = false, updatable = false) val ordenId: UUID,
    @Column(name = "entrega_id", updatable = false) val entregaId: UUID?,
    @Column(nullable = false, updatable = false) val estado: String,
    @Column(nullable = false, updatable = false) val titulo: String,
    @Column(updatable = false) val detalle: String?,
    @Column(name = "usuario_id", nullable = false, updatable = false) val usuarioId: UUID,
    @Column(name = "ocurrido_en", nullable = false, updatable = false) val ocurridoEn: Instant,
) : EntidadBase() {
    @Column(insertable = false, updatable = false)
    var secuencia: Long? = null
        protected set
}
