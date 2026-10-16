package ec.cleantok.identidad.dominio

import ec.cleantok.comun.entidad.EntidadBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** SUPERVISOR: "Supervisor de establecimiento", controla solo las sedes que tiene asignadas. */
enum class Rol { SUPER_ADMIN, ADMIN_EMPRESA, SUPERVISOR, ENCARGADO, PROVEEDOR, REPARTIDOR }

@Entity
@Table(name = "empresa")
class Empresa(
    @Column(nullable = false) var nombre: String,
    @Column(nullable = false, unique = true) var ruc: String,
) : EntidadBase() {
    /** Desactivada por la plataforma: su gente no puede entrar y sus sesiones se cierran. */
    @Column(nullable = false)
    var activo: Boolean = true

    // Datos de contacto: los ve el proveedor que cotiza o atiende sus órdenes.
    var telefono: String? = null
    var email: String? = null
    var direccion: String? = null
    var lat: BigDecimal? = null
    var lng: BigDecimal? = null
}

/**
 * Distribuidor de insumos. Atiende a una o varias empresas de limpieza y declara cómo entrega:
 * envío directo a cada establecimiento, retiro en su local o retiro en su bodega centralizada.
 */
@Entity
@Table(name = "proveedor")
class Proveedor(
    @Column(nullable = false) var nombre: String,
    @Column(nullable = false, unique = true) var ruc: String,
    var telefono: String?,
    @Column(nullable = false) var direccion: String,
    @Column(nullable = false) var lat: BigDecimal,
    @Column(nullable = false) var lng: BigDecimal,
    @Column(name = "bodega_direccion") var bodegaDireccion: String? = null,
    @Column(name = "bodega_lat") var bodegaLat: BigDecimal? = null,
    @Column(name = "bodega_lng") var bodegaLng: BigDecimal? = null,
    @Column(name = "envio_directo", nullable = false) var envioDirecto: Boolean = true,
    @Column(name = "retiro_local", nullable = false) var retiroLocal: Boolean = false,
    @Column(name = "retiro_bodega", nullable = false) var retiroBodega: Boolean = false,
    @Column(nullable = false) var activo: Boolean = true,
) : EntidadBase()

@Entity
@Table(name = "usuario")
class Usuario(
    @Column(name = "empresa_id") val empresaId: UUID?,
    @Column(nullable = false) var nombre: String,
    @Column(nullable = false) var email: String,
    @Column(name = "password_hash", nullable = false) var passwordHash: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val rol: Rol,
    @Column(nullable = false) var activo: Boolean = true,
    /** Solo PROVEEDOR y REPARTIDOR. */
    @Column(name = "proveedor_id") val proveedorId: UUID? = null,
) : EntidadBase() {
    /** La cuenta tiene una clave temporal (creada o restablecida por un admin) que debe cambiarse al entrar. */
    @Column(name = "debe_cambiar_clave", nullable = false)
    var debeCambiarClave: Boolean = false
}

/**
 * Refresh token opaco, guardado como SHA-256. Cada uso lo rota dentro de la misma [familiaId];
 * si se reusa uno ya rotado, se revoca toda la familia (posible robo).
 */
@Entity
@Table(name = "refresh_token")
class RefreshToken(
    @Column(name = "usuario_id", nullable = false) val usuarioId: UUID,
    @Column(name = "familia_id", nullable = false) val familiaId: UUID,
    @Column(name = "token_hash", nullable = false, unique = true) val tokenHash: String,
    @Column(name = "expira_en", nullable = false) val expiraEn: Instant,
) : EntidadBase() {
    @Column(name = "revocado_en")
    var revocadoEn: Instant? = null

    @Column(name = "reemplazado_por")
    var reemplazadoPor: UUID? = null
}
