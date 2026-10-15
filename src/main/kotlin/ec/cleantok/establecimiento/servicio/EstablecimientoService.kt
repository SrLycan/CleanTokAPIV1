package ec.cleantok.establecimiento.servicio

import ec.cleantok.comun.error.ApiException
import ec.cleantok.comun.error.CodigoError
import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.establecimiento.api.AsignarEncargadosRequest
import ec.cleantok.establecimiento.api.EncargadoAsignadoDto
import ec.cleantok.establecimiento.api.EstablecimientoDto
import ec.cleantok.establecimiento.api.EstablecimientoRequest
import ec.cleantok.establecimiento.dominio.Establecimiento
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.Ubicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.dominio.UsuarioEstablecimiento
import ec.cleantok.establecimiento.dominio.UsuarioEstablecimientoRepository
import ec.cleantok.identidad.api.EstablecimientoAsignadoDto
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.identidad.servicio.EstablecimientosDeUsuario
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class EstablecimientoService(
    private val establecimientos: EstablecimientoRepository,
    private val ubicaciones: UbicacionRepository,
    private val asignaciones: UsuarioEstablecimientoRepository,
    private val usuarios: UsuarioRepository,
    private val acceso: AccesoEstablecimiento,
    private val tokens: GeneradorTokenQr,
    private val clock: Clock,
) : EstablecimientosDeUsuario {

    /** Crea el establecimiento y su bodega en la misma transacción. */
    @Transactional
    fun crear(req: EstablecimientoRequest): EstablecimientoDto {
        val empresaId = ContextoSeguridad.empresaId()
        val e = establecimientos.save(
            Establecimiento(empresaId = empresaId, nombre = req.nombre.trim(), tipo = req.tipo, creadoPor = ContextoSeguridad.usuario().id),
        )
        aplicar(e, req)
        val bodega = ubicaciones.save(
            Ubicacion(
                empresaId = empresaId,
                establecimientoId = e.id,
                nombre = "Bodega",
                tipo = TipoUbicacion.BODEGA,
                orden = 0,
                qrToken = tokens.nuevo(),
            ),
        )
        return EstablecimientoDto.de(e, bodega.id, puedeEliminar = true)
    }

    @Transactional(readOnly = true)
    fun listar(activo: Boolean?, pageable: Pageable): Page<EstablecimientoDto> {
        val empresaId = ContextoSeguridad.empresaId()
        val pagina = if (activo == null) establecimientos.findAllByEmpresaId(empresaId, pageable)
        else establecimientos.findAllByEmpresaIdAndActivo(empresaId, activo, pageable)
        val usuario = ContextoSeguridad.usuario()
        return pagina.map { dto(it, usuario) }
    }

    @Transactional(readOnly = true)
    fun mios(): List<EstablecimientoDto> = deUsuario(ContextoSeguridad.usuario()).map { EstablecimientoDto.de(it, bodegaId(it.id)) }

    @Transactional(readOnly = true)
    fun obtener(id: UUID): EstablecimientoDto = dto(acceso.visible(id), ContextoSeguridad.usuario())

    @Transactional
    fun actualizar(id: UUID, req: EstablecimientoRequest): EstablecimientoDto {
        val e = acceso.deMiEmpresa(id)
        e.nombre = req.nombre.trim()
        e.tipo = req.tipo
        aplicar(e, req)
        return dto(e, ContextoSeguridad.usuario())
    }

    @Transactional
    fun cambiarActivo(id: UUID, activo: Boolean): EstablecimientoDto {
        val e = acceso.deMiEmpresa(id)
        e.activo = activo
        return dto(e, ContextoSeguridad.usuario())
    }

    /**
     * Eliminación lógica: desaparece de la app, sus QR dejan de funcionar y se quitan sus encargados.
     * Lo puede hacer el administrador de la plataforma o el admin que lo creó; otro admin de la misma
     * empresa recibe 403. Fuera de su empresa, 404 (no se revela que existe).
     */
    @Transactional
    fun eliminar(id: UUID) {
        val usuario = ContextoSeguridad.usuario()
        val e = if (usuario.rol == Rol.SUPER_ADMIN) {
            establecimientos.findById(id).orElse(null)?.takeIf { it.eliminadoEn == null } ?: throw NoEncontradoException("Establecimiento")
        } else acceso.deMiEmpresa(id)
        if (!puedeEliminar(e, usuario)) {
            throw ApiException(HttpStatus.FORBIDDEN, CodigoError.NO_AUTORIZADO, "Solo quien creó el establecimiento o el administrador de la plataforma puede eliminarlo")
        }
        if (establecimientos.entregasEnCurso(e.id) > 0) {
            throw ConflictoException("Tiene pedidos en camino: elimínalo cuando se hayan recibido")
        }
        e.activo = false
        e.eliminadoEn = clock.instant()
        asignaciones.findAllByEstablecimientoId(e.id).forEach { asignaciones.delete(it) }
    }

    /** Establecimientos de una empresa, para el administrador de la plataforma. */
    @Transactional(readOnly = true)
    fun deEmpresa(empresaId: UUID): List<EstablecimientoDto> =
        establecimientos.findAllByEmpresaId(empresaId, Pageable.unpaged()).sortedBy { it.nombre }.map { EstablecimientoDto.de(it, bodegaId(it.id), puedeEliminar = true) }

    private fun puedeEliminar(e: Establecimiento, usuario: UsuarioAutenticado) =
        usuario.rol == Rol.SUPER_ADMIN || (usuario.rol == Rol.ADMIN_EMPRESA && e.empresaId == usuario.empresaId && e.creadoPor == usuario.id)

    private fun dto(e: Establecimiento, usuario: UsuarioAutenticado) = EstablecimientoDto.de(e, bodegaId(e.id), puedeEliminar(e, usuario))

    // ---- Encargados ----

    @Transactional(readOnly = true)
    fun encargados(id: UUID): List<EncargadoAsignadoDto> {
        val e = acceso.deMiEmpresa(id)
        val ids = asignaciones.findAllByEstablecimientoId(e.id).map { it.usuarioId }
        return usuarios.findAllById(ids).sortedBy { it.nombre }.map { EncargadoAsignadoDto(it.id, it.nombre, it.email, it.activo) }
    }

    /** Reemplaza la lista completa de encargados. Solo acepta ENCARGADOS de la misma empresa. */
    @Transactional
    fun asignarEncargados(id: UUID, req: AsignarEncargadosRequest): List<EncargadoAsignadoDto> {
        val e = acceso.deMiEmpresa(id)
        val pedidos = req.usuarioIds.toSet()
        val validos = usuarios.findAllById(pedidos).filter { it.empresaId == e.empresaId && it.rol == Rol.ENCARGADO }
        val invalidos = pedidos - validos.map { it.id }.toSet()
        if (invalidos.isNotEmpty()) {
            throw ValidacionException("Algunos usuarios no son encargados de tu empresa", mapOf("usuarioIds" to invalidos))
        }
        val actuales = asignaciones.findAllByEstablecimientoId(e.id)
        actuales.filter { it.usuarioId !in pedidos }.forEach { asignaciones.delete(it) }
        val yaAsignados = actuales.map { it.usuarioId }.toSet()
        (pedidos - yaAsignados).forEach { asignaciones.save(UsuarioEstablecimiento(e.empresaId, it, e.id)) }
        return encargados(id)
    }

    /** Reemplaza los establecimientos de un encargado (vista "Equipo" del admin). */
    @Transactional
    fun asignarEstablecimientos(usuarioId: UUID, ids: List<UUID>): List<EstablecimientoAsignadoDto> {
        val empresaId = ContextoSeguridad.empresaId()
        val u = usuarios.findByIdAndEmpresaId(usuarioId, empresaId)?.takeIf { it.rol == Rol.ENCARGADO }
            ?: throw NoEncontradoException("Encargado")
        val pedidos = ids.toSet().map { acceso.deMiEmpresa(it) }
        val actuales = asignaciones.findAllByUsuarioId(u.id)
        actuales.filter { a -> pedidos.none { it.id == a.establecimientoId } }.forEach { asignaciones.delete(it) }
        val ya = actuales.map { it.establecimientoId }.toSet()
        pedidos.filter { it.id !in ya }.forEach { asignaciones.save(UsuarioEstablecimiento(empresaId, u.id, it.id)) }
        return pedidos.sortedBy { it.nombre }.map { EstablecimientoAsignadoDto(it.id, it.nombre) }
    }

    @Transactional
    fun quitarEncargado(id: UUID, usuarioId: UUID) {
        val e = acceso.deMiEmpresa(id)
        asignaciones.quitar(e.id, usuarioId)
    }

    // ---- Para /auth/me ----

    @Transactional(readOnly = true)
    override fun de(usuario: UsuarioAutenticado): List<EstablecimientoAsignadoDto> =
        deUsuario(usuario).map { EstablecimientoAsignadoDto(it.id, it.nombre) }

    private fun deUsuario(usuario: UsuarioAutenticado): List<Establecimiento> = when (usuario.rol) {
        Rol.ADMIN_EMPRESA -> establecimientos.findAllByEmpresaIdAndActivoTrueOrderByNombre(usuario.empresaId!!)
        Rol.ENCARGADO -> establecimientos.asignadosActivos(usuario.id)
        Rol.SUPER_ADMIN, Rol.PROVEEDOR, Rol.REPARTIDOR -> emptyList()
    }

    private fun bodegaId(establecimientoId: UUID): UUID? =
        ubicaciones.findByEstablecimientoIdAndTipo(establecimientoId, TipoUbicacion.BODEGA)?.id

    private fun aplicar(e: Establecimiento, req: EstablecimientoRequest) {
        e.entidadContratante = req.entidadContratante?.trim()?.ifBlank { null }
        e.direccion = req.direccion?.trim()?.ifBlank { null }
        e.lat = req.lat
        e.lng = req.lng
        e.areaM2 = req.areaM2
    }
}
