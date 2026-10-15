package ec.cleantok.comun.seguridad

import ec.cleantok.identidad.dominio.Rol
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/** Identidad del usuario autenticado, tal como viene en el JWT de acceso. */
data class UsuarioAutenticado(
    val id: UUID,
    val empresaId: UUID?,
    val rol: Rol,
    /** Solo para PROVEEDOR y REPARTIDOR. */
    val proveedorId: UUID? = null,
) {
    val esAdmin: Boolean get() = rol == Rol.ADMIN_EMPRESA
    val esEncargado: Boolean get() = rol == Rol.ENCARGADO
    val esProveedor: Boolean get() = rol == Rol.PROVEEDOR
    val esRepartidor: Boolean get() = rol == Rol.REPARTIDOR
}

class AutenticacionJwt(
    private val usuario: UsuarioAutenticado,
    private val jwt: Jwt,
) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_${usuario.rol.name}"))) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = jwt

    override fun getPrincipal(): UsuarioAutenticado = usuario

    override fun getName(): String = usuario.id.toString()
}
