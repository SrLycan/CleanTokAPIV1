package ec.cleantok.abastecimiento.servicio

import ec.cleantok.abastecimiento.api.ActualizarPerfilRequest
import ec.cleantok.abastecimiento.api.CrearProveedorRequest
import ec.cleantok.abastecimiento.api.DirectorioProveedorDto
import ec.cleantok.abastecimiento.api.MiembroRequest
import ec.cleantok.abastecimiento.api.ProveedorCreadoDto
import ec.cleantok.abastecimiento.api.ProveedorDto
import ec.cleantok.abastecimiento.api.ProveedorPlataformaDto
import ec.cleantok.abastecimiento.api.UsuarioRefDto
import ec.cleantok.abastecimiento.dominio.EmpresaProveedor
import ec.cleantok.abastecimiento.dominio.EmpresaProveedorRepository
import ec.cleantok.abastecimiento.dominio.Modalidad
import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.identidad.api.UsuarioDto
import ec.cleantok.identidad.dominio.Proveedor
import ec.cleantok.identidad.dominio.ProveedorRepository
import ec.cleantok.identidad.dominio.RefreshTokenRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.Usuario
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.identidad.servicio.UsuarioService
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

fun Proveedor.modalidades(): List<Modalidad> = listOfNotNull(
    Modalidad.ENVIO_DIRECTO.takeIf { envioDirecto },
    Modalidad.RETIRO_LOCAL.takeIf { retiroLocal },
    Modalidad.RETIRO_BODEGA.takeIf { retiroBodega },
)

/**
 * Proveedores en tres vistas: la plataforma los da de alta (SUPER_ADMIN), cada empresa elige con cuáles trabaja
 * (directorio y vínculo) y cada proveedor gestiona su perfil y su equipo (ventas y repartidores).
 */
@Service
class ProveedorService(
    private val proveedores: ProveedorRepository,
    private val vinculos: EmpresaProveedorRepository,
    private val usuarios: UsuarioRepository,
    private val cuentas: UsuarioService,
    private val refreshTokens: RefreshTokenRepository,
    private val clock: Clock,
) {
    // ---------------- Empresa (admin) ----------------

    /** Proveedores con los que trabaja la empresa del admin. */
    @Transactional(readOnly = true)
    fun deMiEmpresa(): List<ProveedorDto> {
        val v = vinculos.findAllByEmpresaId(ContextoSeguridad.empresaId()).associateBy { it.proveedorId }
        return proveedores.findAllById(v.keys).filter { it.activo }.sortedBy { it.nombre }.map { dto(it, v.getValue(it.id).preferido) }
    }

    /** Todos los proveedores activos de la plataforma, indicando cuáles ya están vinculados con mi empresa. */
    @Transactional(readOnly = true)
    fun directorio(): List<DirectorioProveedorDto> {
        val v = vinculos.findAllByEmpresaId(ContextoSeguridad.empresaId()).associateBy { it.proveedorId }
        return proveedores.findAll().filter { it.activo }.sortedBy { it.nombre }.map { p ->
            DirectorioProveedorDto(dto(p, v[p.id]?.preferido), vinculado = p.id in v)
        }
    }

    @Transactional
    fun vincular(proveedorId: UUID, preferido: Boolean): DirectorioProveedorDto {
        val empresaId = ContextoSeguridad.empresaId()
        val p = proveedores.findById(proveedorId).orElse(null)?.takeIf { it.activo } ?: throw NoEncontradoException("Proveedor")
        val v = vinculos.findByEmpresaIdAndProveedorId(empresaId, p.id)?.also { it.preferido = preferido }
            ?: vinculos.save(EmpresaProveedor(empresaId, p.id, preferido))
        return DirectorioProveedorDto(dto(p, v.preferido), vinculado = true)
    }

    /** Deja de invitarlo a cotizar. Las órdenes ya emitidas siguen su curso. */
    @Transactional
    fun desvincular(proveedorId: UUID) {
        vinculos.findByEmpresaIdAndProveedorId(ContextoSeguridad.empresaId(), proveedorId)?.let { vinculos.delete(it) }
            ?: throw NoEncontradoException("Vínculo con el proveedor")
    }

    // ---------------- Proveedor ----------------

    @Transactional(readOnly = true)
    fun miProveedor(): ProveedorDto = dto(mio(), null)

    @Transactional
    fun actualizarPerfil(req: ActualizarPerfilRequest): ProveedorDto {
        val p = mio()
        aplicar(p, req.telefono, req.direccion, req.lat, req.lng, req.bodegaDireccion, req.bodegaLat, req.bodegaLng, req.modalidades)
        return dto(p, null)
    }

    @Transactional(readOnly = true)
    fun misRepartidores(): List<UsuarioRefDto> =
        usuarios.findAllByProveedorIdAndRolAndActivoTrueOrderByNombre(ContextoSeguridad.proveedorId(), Rol.REPARTIDOR)
            .map { UsuarioRefDto(it.id, it.nombre, it.email) }

    @Transactional(readOnly = true)
    fun miEquipo(): List<UsuarioDto> = usuarios.findAllByProveedorIdOrderByNombre(ContextoSeguridad.proveedorId()).map { UsuarioDto.de(it) }

    @Transactional
    fun agregarMiembro(req: MiembroRequest): UsuarioDto {
        val rol = req.rol ?: Rol.REPARTIDOR
        if (rol !in setOf(Rol.REPARTIDOR, Rol.PROVEEDOR)) throw ValidacionException("El rol debe ser REPARTIDOR o PROVEEDOR")
        return cuentas.crearCuenta(req.nombre, req.email, null, rol, proveedorId = ContextoSeguridad.proveedorId())
    }

    @Transactional
    fun cambiarActivoMiembro(id: UUID, activo: Boolean): UsuarioDto {
        val u = miembro(id)
        if (!activo && u.activo && u.rol == Rol.PROVEEDOR && usuarios.countByProveedorIdAndRolAndActivoTrue(u.proveedorId!!, Rol.PROVEEDOR) <= 1) {
            throw ConflictoException("El proveedor no puede quedarse sin un usuario de ventas activo")
        }
        u.activo = activo
        if (!activo) refreshTokens.revocarDeUsuario(u.id, clock.instant())
        return UsuarioDto.de(u)
    }

    @Transactional
    fun restablecerMiembro(id: UUID): UsuarioDto = cuentas.restablecer(miembro(id))

    private fun miembro(id: UUID): Usuario =
        usuarios.findById(id).orElse(null)?.takeIf { it.proveedorId == ContextoSeguridad.proveedorId() } ?: throw NoEncontradoException("Usuario")

    private fun mio(): Proveedor = proveedores.findById(ContextoSeguridad.proveedorId()).orElseThrow { NoEncontradoException("Proveedor") }

    // ---------------- Plataforma (SUPER_ADMIN) ----------------

    @Transactional(readOnly = true)
    fun todos(): List<ProveedorPlataformaDto> {
        val empresasPorProveedor = vinculos.findAll().groupBy { it.proveedorId }.mapValues { it.value.size }
        return proveedores.findAll().sortedBy { it.nombre }.map { p ->
            ProveedorPlataformaDto(dto(p, null), p.activo, empresasPorProveedor[p.id] ?: 0, usuarios.countByProveedorId(p.id))
        }
    }

    /** Alta de un proveedor con su primer usuario de ventas (clave temporal). */
    @Transactional
    fun crear(req: CrearProveedorRequest): ProveedorCreadoDto {
        if (proveedores.existsByRuc(req.ruc)) throw ConflictoException("Ya existe un proveedor con ese RUC")
        val p = Proveedor(req.nombre.trim(), req.ruc, null, req.direccion.trim(), req.lat!!, req.lng!!)
        aplicar(p, req.telefono, req.direccion, req.lat, req.lng, req.bodegaDireccion, req.bodegaLat, req.bodegaLng, req.modalidades)
        proveedores.save(p)
        val usuario = cuentas.crearCuenta(req.usuarioNombre, req.usuarioEmail, null, Rol.PROVEEDOR, proveedorId = p.id)
        return ProveedorCreadoDto(dto(p, null), usuario)
    }

    /** Desactivar un proveedor lo saca del directorio y de nuevas cotizaciones, y cierra las sesiones de su equipo. */
    @Transactional
    fun cambiarActivo(id: UUID, activo: Boolean): ProveedorPlataformaDto {
        val p = proveedores.findById(id).orElseThrow { NoEncontradoException("Proveedor") }
        p.activo = activo
        if (!activo) usuarios.findAllByProveedorIdOrderByNombre(p.id).forEach { refreshTokens.revocarDeUsuario(it.id, clock.instant()) }
        return ProveedorPlataformaDto(dto(p, null), p.activo, vinculos.findAll().count { it.proveedorId == p.id }, usuarios.countByProveedorId(p.id))
    }

    private fun aplicar(
        p: Proveedor,
        telefono: String?,
        direccion: String?,
        lat: java.math.BigDecimal?,
        lng: java.math.BigDecimal?,
        bodegaDireccion: String?,
        bodegaLat: java.math.BigDecimal?,
        bodegaLng: java.math.BigDecimal?,
        modalidades: List<Modalidad>?,
    ) {
        telefono?.let { p.telefono = it.trim().ifBlank { null } }
        direccion?.trim()?.takeIf { it.isNotBlank() }?.let { p.direccion = it }
        if (lat != null && lng != null) { p.lat = lat; p.lng = lng }
        val conBodega = !bodegaDireccion.isNullOrBlank()
        if (conBodega && (bodegaLat == null || bodegaLng == null)) throw ValidacionException("Ubica la bodega en el mapa")
        if (bodegaDireccion != null) {
            p.bodegaDireccion = bodegaDireccion.trim().ifBlank { null }
            p.bodegaLat = if (conBodega) bodegaLat else null
            p.bodegaLng = if (conBodega) bodegaLng else null
        }
        modalidades?.let { m ->
            if (m.isEmpty()) throw ValidacionException("Elige al menos una forma de entrega")
            if (Modalidad.RETIRO_BODEGA in m && p.bodegaDireccion == null) throw ValidacionException("Para retiro en bodega, registra la bodega")
            p.envioDirecto = Modalidad.ENVIO_DIRECTO in m
            p.retiroLocal = Modalidad.RETIRO_LOCAL in m
            p.retiroBodega = Modalidad.RETIRO_BODEGA in m
        }
    }

    private fun dto(p: Proveedor, preferido: Boolean?) = ProveedorDto(
        p.id, p.nombre, p.ruc, p.telefono, p.direccion, p.bodegaDireccion, p.modalidades(), preferido,
        p.lat, p.lng, p.bodegaLat, p.bodegaLng,
    )
}

/** Códigos legibles por empresa (SC-001, OC-001). Un candado por empresa y tipo evita repetirlos. */
@Service
class Codigos(private val jdbc: NamedParameterJdbcTemplate) {
    fun siguiente(prefijo: String, empresaId: UUID, existentes: () -> Long): String {
        jdbc.queryForList(
            "select pg_advisory_xact_lock(hashtext(:clave))",
            mapOf("clave" to "$prefijo:$empresaId"),
        )
        return "%s-%03d".format(prefijo, existentes() + 1)
    }
}
