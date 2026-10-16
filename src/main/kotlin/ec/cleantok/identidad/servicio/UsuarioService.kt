package ec.cleantok.identidad.servicio

import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import org.springframework.beans.factory.ObjectProvider
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.identidad.api.ActualizarUsuarioRequest
import ec.cleantok.identidad.api.ContactoEmpresaRequest
import ec.cleantok.identidad.api.CrearEmpresaRequest
import ec.cleantok.identidad.api.CrearUsuarioRequest
import ec.cleantok.identidad.api.EmpresaDto
import ec.cleantok.identidad.api.UsuarioDto
import ec.cleantok.identidad.dominio.Empresa
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.RefreshTokenRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.Usuario
import ec.cleantok.identidad.dominio.UsuarioRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class UsuarioService(
    private val usuarios: UsuarioRepository,
    private val empresas: EmpresaRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val claves: ClaveTemporal,
    private val establecimientos: ObjectProvider<EstablecimientosDeUsuario>,
    private val clock: Clock,
) {
    // ---- SUPER_ADMIN ----

    @Transactional
    fun crearEmpresa(req: CrearEmpresaRequest): EmpresaDto {
        if (empresas.existsByRuc(req.ruc)) throw ConflictoException("Ya existe una empresa con ese RUC")
        val e = Empresa(req.nombre.trim(), req.ruc)
        aplicarContacto(e, ContactoEmpresaRequest(req.telefono, req.email, req.direccion, req.lat, req.lng))
        return EmpresaDto.de(empresas.save(e))
    }

    /** Contacto de una empresa (lo edita su admin o el administrador de la plataforma). */
    @Transactional
    fun actualizarContacto(empresaId: UUID, req: ContactoEmpresaRequest): EmpresaDto {
        val e = empresas.findById(empresaId).orElseThrow { NoEncontradoException("Empresa") }
        aplicarContacto(e, req)
        return EmpresaDto.de(e)
    }

    @Transactional(readOnly = true)
    fun miEmpresa(): EmpresaDto = EmpresaDto.de(empresas.findById(ContextoSeguridad.empresaId()).orElseThrow { NoEncontradoException("Empresa") })

    private fun aplicarContacto(e: Empresa, req: ContactoEmpresaRequest) {
        if ((req.lat == null) != (req.lng == null)) throw ValidacionException("Indica latitud y longitud, o ninguna")
        e.telefono = req.telefono?.trim()?.ifBlank { null }
        e.email = req.email?.trim()?.lowercase()?.ifBlank { null }
        e.direccion = req.direccion?.trim()?.ifBlank { null }
        e.lat = req.lat
        e.lng = req.lng
    }

    @Transactional(readOnly = true)
    fun listarEmpresas(pageable: Pageable): Page<EmpresaDto> = empresas.findAll(pageable).map(EmpresaDto::de)

    @Transactional
    fun crearAdmin(empresaId: UUID, req: CrearUsuarioRequest): UsuarioDto {
        if (!empresas.existsById(empresaId)) throw NoEncontradoException("Empresa")
        return crear(empresaId, req, Rol.ADMIN_EMPRESA)
    }

    // ---- ADMIN_EMPRESA: usuarios de su empresa ----

    /** Encargado (por defecto), supervisor de establecimiento, administrador de contrato u otro admin de mi empresa. */
    @Transactional
    fun crearEncargado(req: CrearUsuarioRequest): UsuarioDto {
        val rol = req.rol ?: Rol.ENCARGADO
        if (rol !in setOf(Rol.ENCARGADO, Rol.SUPERVISOR, Rol.ADMIN_CONTRATO, Rol.ADMIN_EMPRESA)) {
            throw ValidacionException("El rol debe ser ENCARGADO, SUPERVISOR, ADMIN_CONTRATO o ADMIN_EMPRESA")
        }
        return crear(ContextoSeguridad.empresaId(), req, rol)
    }

    @Transactional(readOnly = true)
    fun listar(pageable: Pageable): Page<UsuarioDto> =
        usuarios.findAllByEmpresaId(ContextoSeguridad.empresaId(), pageable).map { dto(it) }

    @Transactional(readOnly = true)
    fun obtener(id: UUID): UsuarioDto = dto(deMiEmpresa(id))

    @Transactional
    fun actualizar(id: UUID, req: ActualizarUsuarioRequest): UsuarioDto {
        val usuario = deMiEmpresa(id)
        req.nombre?.let { usuario.nombre = it.trim() }
        req.password?.let { usuario.passwordHash = passwordEncoder.encode(it)!! }
        if (req.activo == false && usuario.activo && usuario.rol == Rol.ADMIN_EMPRESA &&
            usuarios.countByEmpresaIdAndRolAndActivoTrue(usuario.empresaId!!, Rol.ADMIN_EMPRESA) <= 1
        ) {
            throw ConflictoException("La empresa no puede quedarse sin un administrador activo")
        }
        req.activo?.let { usuario.activo = it }
        // Al desactivar o cambiar la contraseña se cierran todas sus sesiones.
        if (req.activo == false || req.password != null) refreshTokens.revocarDeUsuario(usuario.id, clock.instant())
        return dto(usuario)
    }

    /** Nueva clave temporal (se muestra una sola vez); cierra sus sesiones. */
    @Transactional
    fun restablecerClave(id: UUID): UsuarioDto = restablecer(deMiEmpresa(id))

    fun restablecer(usuario: Usuario): UsuarioDto {
        val clave = claves.nueva()
        usuario.passwordHash = passwordEncoder.encode(clave)!!
        usuario.debeCambiarClave = true
        refreshTokens.revocarDeUsuario(usuario.id, clock.instant())
        return dto(usuario, clave)
    }

    /** El usuario con sus establecimientos (encargados y supervisores). */
    fun dtoDe(u: Usuario): UsuarioDto = dto(u)

    private fun dto(u: Usuario, clave: String? = null) = UsuarioDto.de(
        u, clave,
        if (u.rol in setOf(Rol.ENCARGADO, Rol.SUPERVISOR, Rol.ADMIN_CONTRATO)) {
            establecimientos.getIfAvailable()?.de(UsuarioAutenticado(u.id, u.empresaId, u.rol)).orEmpty()
        } else emptyList(),
    )

    /** Busca solo dentro de la empresa del usuario autenticado: si es de otra, 404. */
    fun deMiEmpresa(id: UUID): Usuario =
        usuarios.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Usuario")

    private fun crear(empresaId: UUID, req: CrearUsuarioRequest, rol: Rol): UsuarioDto =
        crearCuenta(req.nombre, req.email, req.password, rol, empresaId = empresaId)

    /**
     * Crea una cuenta de empresa o de proveedor. Sin contraseña, genera una clave temporal (que se devuelve una vez)
     * y la cuenta deberá cambiarla al entrar.
     */
    fun crearCuenta(nombre: String, email: String, password: String?, rol: Rol, empresaId: UUID? = null, proveedorId: UUID? = null): UsuarioDto {
        val limpio = email.trim().lowercase()
        if (usuarios.existeEmail(limpio)) throw ConflictoException("Ya existe un usuario con ese email")
        val temporal = if (password == null) claves.nueva() else null
        val u = usuarios.save(
            Usuario(
                empresaId = empresaId,
                nombre = nombre.trim(),
                email = limpio,
                passwordHash = passwordEncoder.encode(password ?: temporal)!!,
                rol = rol,
                proveedorId = proveedorId,
            ).apply { debeCambiarClave = temporal != null },
        )
        return dto(u, temporal)
    }
}
