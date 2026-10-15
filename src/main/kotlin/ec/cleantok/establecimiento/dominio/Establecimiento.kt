package ec.cleantok.establecimiento.dominio

import ec.cleantok.comun.entidad.EntidadBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class TipoEstablecimiento { OFICINA, HOSPITAL, FABRICA, EDUCATIVO, OTRO }

enum class TipoUbicacion { BODEGA, PISO, AREA }

/**
 * Establecimiento de una empresa. Eliminarlo es lógico ([eliminadoEn]): sale de las listas y del acceso
 * (ver [EstablecimientoRepository]), pero los pedidos y entregas antiguos siguen mostrando su nombre.
 */
@Entity
@Table(name = "establecimiento")
class Establecimiento(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(nullable = false) var nombre: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var tipo: TipoEstablecimiento,
    @Column(name = "entidad_contratante") var entidadContratante: String? = null,
    var direccion: String? = null,
    var lat: BigDecimal? = null,
    var lng: BigDecimal? = null,
    @Column(name = "area_m2") var areaM2: BigDecimal? = null,
    @Column(nullable = false) var activo: Boolean = true,
    /** Admin que lo creó: solo él (o el administrador de la plataforma) puede eliminarlo. */
    @Column(name = "creado_por", updatable = false) val creadoPor: UUID? = null,
    @Column(name = "eliminado_en") var eliminadoEn: Instant? = null,
) : EntidadBase()

@Entity
@Table(name = "usuario_establecimiento")
class UsuarioEstablecimiento(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "usuario_id", nullable = false, updatable = false) val usuarioId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
) : EntidadBase()

/**
 * Bodega, piso o área de un establecimiento. [qrToken] es opaco y aleatorio: es lo único que va en el QR
 * pegado en el lugar. Regenerarlo invalida el QR impreso anterior.
 */
@Entity
@Table(name = "ubicacion")
class Ubicacion(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
    @Column(nullable = false) var nombre: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var tipo: TipoUbicacion,
    @Column(nullable = false) var orden: Int,
    @Column(name = "qr_token", nullable = false, unique = true) var qrToken: String,
    @Column(nullable = false) var activo: Boolean = true,
) : EntidadBase() {
    val esBodega: Boolean get() = tipo == TipoUbicacion.BODEGA
}
