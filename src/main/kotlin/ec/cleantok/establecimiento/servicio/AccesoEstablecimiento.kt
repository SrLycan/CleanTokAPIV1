package ec.cleantok.establecimiento.servicio

import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.establecimiento.dominio.Establecimiento
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.establecimiento.dominio.Ubicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.dominio.UsuarioEstablecimientoRepository
import ec.cleantok.identidad.dominio.Rol
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Regla única de acceso a un establecimiento, reutilizada por todos los módulos:
 * - ADMIN_EMPRESA: cualquier establecimiento de su empresa.
 * - ENCARGADO: solo los activos que tiene asignados.
 * Fuera de eso, 404 (no se revela si el recurso existe).
 */
@Component
class AccesoEstablecimiento(
    private val establecimientos: EstablecimientoRepository,
    private val ubicaciones: UbicacionRepository,
    private val asignaciones: UsuarioEstablecimientoRepository,
) {
    fun puedeOperar(usuario: UsuarioAutenticado, establecimiento: Establecimiento): Boolean = when (usuario.rol) {
        Rol.ADMIN_EMPRESA -> establecimiento.empresaId == usuario.empresaId
        Rol.ENCARGADO -> establecimiento.empresaId == usuario.empresaId && establecimiento.activo &&
            asignaciones.existsByUsuarioIdAndEstablecimientoId(usuario.id, establecimiento.id)
        Rol.SUPER_ADMIN, Rol.PROVEEDOR, Rol.REPARTIDOR -> false
    }

    /** Establecimiento que el usuario actual puede ver u operar. */
    fun visible(id: UUID): Establecimiento {
        val usuario = ContextoSeguridad.usuario()
        val e = establecimientos.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId())
        if (e == null || !puedeOperar(usuario, e)) throw NoEncontradoException("Establecimiento")
        return e
    }

    /** Establecimiento de la empresa del admin (incluye inactivos). El rol se exige en el controlador. */
    fun deMiEmpresa(id: UUID): Establecimiento =
        establecimientos.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Establecimiento")

    fun ubicacionDeMiEmpresa(id: UUID): Ubicacion =
        ubicaciones.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Ubicación")

    /** Ubicación que el usuario actual puede operar (vía su establecimiento). */
    fun ubicacionVisible(id: UUID): Ubicacion {
        val u = ubicacionDeMiEmpresa(id)
        visible(u.establecimientoId)
        return u
    }
}
