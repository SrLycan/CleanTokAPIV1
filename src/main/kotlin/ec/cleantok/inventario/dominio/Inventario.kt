package ec.cleantok.inventario.dominio

import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.entidad.EntidadBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class TipoMovimiento { INGRESO, TRASLADO, CONSUMO, DEVOLUCION, AJUSTE }

/** Dirección de un AJUSTE: suma (ENTRADA) o resta (SALIDA) en la ubicación indicada. */
enum class SentidoAjuste { ENTRADA, SALIDA }

/** Saldo de un producto en una ubicación. Solo se modifica con la fila bloqueada (SELECT … FOR UPDATE). */
@Entity
@Table(name = "stock")
class Stock(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
    @Column(name = "producto_id", nullable = false, updatable = false) val productoId: UUID,
    @Column(name = "ubicacion_id", nullable = false, updatable = false) val ubicacionId: UUID,
    @Column(nullable = false) var cantidad: BigDecimal = BigDecimal.ZERO,
) : EntidadBase() {
    @Version
    @Column(nullable = false)
    var version: Long = 0
}

/** Registro inmutable del kardex. La base rechaza UPDATE y DELETE. */
@Entity
@Immutable
@Table(name = "movimiento")
class Movimiento(
    @Column(name = "empresa_id", nullable = false) val empresaId: UUID,
    @Column(name = "establecimiento_id", nullable = false) val establecimientoId: UUID,
    @Column(name = "client_uuid", nullable = false) val clientUuid: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val tipo: TipoMovimiento,
    @Column(name = "producto_id", nullable = false) val productoId: UUID,
    @Column(name = "ubicacion_origen_id") val ubicacionOrigenId: UUID?,
    @Column(name = "ubicacion_destino_id") val ubicacionDestinoId: UUID?,
    @Column(nullable = false) val cantidad: BigDecimal,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val unidad: Unidad,
    @Column(name = "cantidad_original", nullable = false) val cantidadOriginal: BigDecimal,
    @Enumerated(EnumType.STRING) @Column(name = "unidad_original", nullable = false) val unidadOriginal: Unidad,
    @Column(name = "usuario_id", nullable = false) val usuarioId: UUID,
    @Column(name = "ocurrido_en", nullable = false) val ocurridoEn: Instant,
    @Column(name = "registrado_en", nullable = false) val registradoEn: Instant,
    val nota: String?,
    val motivo: String?,
    @Column(name = "foto_id") val fotoId: UUID?,
    @Column(name = "saldo_resultante_origen") val saldoResultanteOrigen: BigDecimal?,
    @Column(name = "saldo_resultante_destino") val saldoResultanteDestino: BigDecimal?,
) : EntidadBase() {
    /**
     * Orden estricto de aplicación sobre los saldos (bigserial asignado por la base). Solo se usa para ordenar
     * en consultas; no se relee tras insertar porque Hibernate no admite propiedades generadas en entidades @Immutable.
     */
    @Column(insertable = false, updatable = false)
    var secuencia: Long? = null
        protected set
}
