package ec.cleantok.identidad.api

import ec.cleantok.identidad.dominio.Empresa
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.Usuario
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

data class LoginRequest(
    @field:NotBlank(message = "El email es obligatorio")
    @field:Email(message = "El email no es válido")
    @field:Schema(example = "admin@brillo.ec")
    val email: String,
    @field:NotBlank(message = "La contraseña es obligatoria")
    @field:Schema(example = "Demo2026!")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank(message = "El refresh token es obligatorio")
    val refreshToken: String,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    @field:Schema(example = "Bearer") val tokenType: String = "Bearer",
    @field:Schema(description = "Segundos de vida del access token", example = "900") val expiraEn: Long,
    val usuario: UsuarioResumenDto,
)

data class UsuarioResumenDto(
    val id: UUID,
    val nombre: String,
    val email: String,
    val rol: Rol,
    @field:Schema(description = "true: entró con una clave temporal y debe cambiarla antes de seguir") val debeCambiarClave: Boolean = false,
)

data class CambiarClaveRequest(
    @field:NotBlank(message = "Escribe tu contraseña actual") val actual: String,
    @field:Size(min = 8, max = 72, message = "La contraseña nueva debe tener entre 8 y 72 caracteres") val nueva: String,
)

data class EmpresaResumenDto(val id: UUID, val nombre: String)

data class EstablecimientoAsignadoDto(val id: UUID, val nombre: String)

data class MeResponse(
    val usuario: UsuarioResumenDto,
    val empresa: EmpresaResumenDto?,
    @field:Schema(description = "Proveedor del usuario (solo PROVEEDOR y REPARTIDOR)") val proveedor: EmpresaResumenDto? = null,
    @field:Schema(description = "Establecimientos donde el usuario puede operar (todos los de la empresa para el admin)")
    val establecimientos: List<EstablecimientoAsignadoDto>,
)

data class CrearEmpresaRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 200) val nombre: String,
    @field:Pattern(regexp = "\\d{13}", message = "El RUC debe tener 13 dígitos")
    @field:Schema(example = "1791234567001")
    val ruc: String,
    @field:Size(max = 30) val telefono: String? = null,
    @field:Size(max = 254) @field:Email(message = "El correo no es válido") val email: String? = null,
    @field:Size(max = 500) val direccion: String? = null,
    @field:DecimalMin("-90") @field:DecimalMax("90") val lat: BigDecimal? = null,
    @field:DecimalMin("-180") @field:DecimalMax("180") val lng: BigDecimal? = null,
)

@Schema(description = "Contacto de la empresa que ve el proveedor. Se reemplazan todos los campos (vacío borra).")
data class ContactoEmpresaRequest(
    @field:Size(max = 30) val telefono: String? = null,
    @field:Size(max = 254) @field:Email(message = "El correo no es válido") val email: String? = null,
    @field:Size(max = 500) val direccion: String? = null,
    @field:DecimalMin("-90") @field:DecimalMax("90") val lat: BigDecimal? = null,
    @field:DecimalMin("-180") @field:DecimalMax("180") val lng: BigDecimal? = null,
)

data class EmpresaDto(
    val id: UUID,
    @field:Schema(description = "Razón social") val nombre: String,
    val ruc: String,
    val telefono: String? = null,
    val email: String? = null,
    val direccion: String? = null,
    val lat: BigDecimal? = null,
    val lng: BigDecimal? = null,
) {
    companion object {
        fun de(e: Empresa) = EmpresaDto(e.id, e.nombre, e.ruc, e.telefono, e.email, e.direccion, e.lat, e.lng)
    }
}

data class CrearUsuarioRequest(
    @field:NotBlank(message = "El nombre es obligatorio") @field:Size(max = 200) val nombre: String,
    @field:NotBlank(message = "El email es obligatorio") @field:Email(message = "El email no es válido") val email: String,
    @field:Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
    @field:Schema(description = "Si se omite, se genera una clave temporal que el usuario cambia al entrar (recomendado)")
    val password: String? = null,
    @field:Schema(description = "Solo POST /usuarios: ENCARGADO (por defecto), SUPERVISOR (supervisor de establecimiento), ADMIN_CONTRATO (administrador de contrato) o ADMIN_EMPRESA")
    val rol: Rol? = null,
)

data class ActualizarUsuarioRequest(
    @field:Size(min = 1, max = 200) val nombre: String? = null,
    val activo: Boolean? = null,
    @field:Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres") val password: String? = null,
)

data class UsuarioDto(
    val id: UUID,
    val empresaId: UUID?,
    val nombre: String,
    val email: String,
    val rol: Rol,
    val activo: Boolean,
    val debeCambiarClave: Boolean = false,
    @field:Schema(description = "Solo al crear o restablecer: la clave temporal para entregar al usuario. No se vuelve a mostrar.")
    val claveTemporal: String? = null,
    @field:Schema(description = "Establecimientos donde opera (encargados y supervisores)") val establecimientos: List<EstablecimientoAsignadoDto> = emptyList(),
) {
    companion object {
        fun de(u: Usuario, claveTemporal: String? = null, establecimientos: List<EstablecimientoAsignadoDto> = emptyList()) =
            UsuarioDto(u.id, u.empresaId, u.nombre, u.email, u.rol, u.activo, u.debeCambiarClave, claveTemporal, establecimientos)
    }
}
