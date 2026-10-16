package ec.cleantok.abastecimiento.servicio

import ec.cleantok.abastecimiento.api.EmpresaContactoDto
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.UsuarioRepository
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Ficha de la empresa cliente que ve el proveedor: razón social, RUC, teléfono, correo, dirección y ubicación.
 * Si la empresa no registró un correo, se muestra el de su primer administrador activo.
 */
@Component
class FichaEmpresa(
    private val empresas: EmpresaRepository,
    private val usuarios: UsuarioRepository,
) {
    fun de(empresaId: UUID): EmpresaContactoDto {
        val e = empresas.findById(empresaId).orElseThrow { NoEncontradoException("Empresa") }
        val correo = e.email ?: usuarios.findAllByEmpresaId(e.id, Pageable.unpaged()).content
            .filter { it.rol == Rol.ADMIN_EMPRESA && it.activo }
            .minByOrNull { it.createdAt }?.email
        return EmpresaContactoDto(e.id, e.nombre, e.ruc, e.telefono, correo, e.direccion, e.lat, e.lng)
    }
}
