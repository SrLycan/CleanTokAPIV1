package ec.cleantok.comun.seguridad

import ec.cleantok.comun.error.NoAutorizadoException
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Acceso al usuario autenticado. Toda consulta de dominio se filtra con [empresaId];
 * los recursos de otra empresa responden 404 para no revelar que existen.
 */
object ContextoSeguridad {

    fun usuario(): UsuarioAutenticado =
        (SecurityContextHolder.getContext().authentication as? AutenticacionJwt)?.principal
            ?: throw NoAutorizadoException("Debes iniciar sesión")

    /** Empresa del usuario. El SUPER_ADMIN no tiene empresa y no opera recursos de dominio. */
    fun empresaId(): UUID = usuario().empresaId ?: throw NoAutorizadoException("Operación disponible solo para usuarios de una empresa")

    /** Proveedor del usuario (PROVEEDOR o REPARTIDOR). */
    fun proveedorId(): UUID = usuario().proveedorId ?: throw NoAutorizadoException("Operación disponible solo para usuarios de un proveedor")
}
