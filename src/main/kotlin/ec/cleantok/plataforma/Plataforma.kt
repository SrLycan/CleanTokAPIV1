package ec.cleantok.plataforma

import ec.cleantok.abastecimiento.api.ActivoRequest
import ec.cleantok.abastecimiento.api.CrearProveedorRequest
import ec.cleantok.abastecimiento.api.ProveedorCreadoDto
import ec.cleantok.abastecimiento.api.ProveedorPlataformaDto
import ec.cleantok.abastecimiento.servicio.ProveedorService
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.establecimiento.api.EstablecimientoDto
import ec.cleantok.establecimiento.api.EstablecimientoRequest
import ec.cleantok.establecimiento.servicio.EstablecimientoService
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.identidad.api.UsuarioDto
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.identidad.servicio.UsuarioService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ResumenPlataformaDto(
    val empresas: Long,
    val proveedores: Long,
    val establecimientos: Long,
    val usuariosPorRol: Map<String, Long>,
    val ordenesPorEstado: Map<String, Long>,
    val entregasRecibidas: Long,
    val solicitudesEnCotizacion: Long,
)

data class EmpresaPlataformaDto(
    val id: UUID,
    val nombre: String,
    val ruc: String,
    val activo: Boolean,
    val establecimientos: Long,
    val usuarios: Long,
    val proveedores: Long,
    val admins: List<UsuarioDto>,
    @field:io.swagger.v3.oas.annotations.media.Schema(description = "Supervisores de establecimiento, con sus sedes")
    val supervisores: List<UsuarioDto> = emptyList(),
    val telefono: String? = null,
    val email: String? = null,
    val direccion: String? = null,
    val lat: java.math.BigDecimal? = null,
    val lng: java.math.BigDecimal? = null,
)

/**
 * Administración de la plataforma (SUPER_ADMIN): da de alta empresas y proveedores con su primer responsable.
 * Cada organización gestiona después a su propia gente; aquí solo se puede restablecer la clave de esos responsables.
 */
@Service
class PlataformaService(
    private val empresas: EmpresaRepository,
    private val usuarios: UsuarioRepository,
    private val cuentas: UsuarioService,
    private val refreshTokens: ec.cleantok.identidad.dominio.RefreshTokenRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: java.time.Clock,
) {
    @Transactional(readOnly = true)
    fun resumen(): ResumenPlataformaDto {
        fun contar(sql: String) = jdbc.queryForObject(sql, emptyMap<String, Any>(), Long::class.java) ?: 0L
        fun agrupar(sql: String) = jdbc.query(sql) { rs, _ -> rs.getString(1) to rs.getLong(2) }.toMap()
        return ResumenPlataformaDto(
            empresas = contar("select count(*) from empresa where activo"),
            proveedores = contar("select count(*) from proveedor where activo"),
            establecimientos = contar("select count(*) from establecimiento where activo"),
            usuariosPorRol = agrupar("select rol, count(*) from usuario where activo group by rol order by rol"),
            ordenesPorEstado = agrupar("select estado, count(*) from orden_compra group by estado order by estado"),
            entregasRecibidas = contar("select count(*) from entrega where estado = 'RECIBIDA'"),
            solicitudesEnCotizacion = contar("select count(*) from solicitud where estado = 'EN_COTIZACION'"),
        )
    }

    @Transactional(readOnly = true)
    fun empresas(): List<EmpresaPlataformaDto> = empresas.findAll().sortedBy { it.nombre }.map(::dto)

    /** Desactivar una empresa impide que su gente entre y cierra sus sesiones; sus datos se conservan. */
    @Transactional
    fun cambiarActivoEmpresa(id: UUID, activo: Boolean): EmpresaPlataformaDto {
        val e = empresas.findById(id).orElseThrow { NoEncontradoException("Empresa") }
        e.activo = activo
        if (!activo) usuarios.findAllByEmpresaId(e.id, Pageable.unpaged()).forEach { refreshTokens.revocarDeUsuario(it.id, clock.instant()) }
        return dto(e)
    }

    /** Supervisores de establecimiento de una empresa, para asignarlos a una sede. */
    @Transactional(readOnly = true)
    fun supervisores(empresaId: UUID): List<UsuarioDto> {
        if (!empresas.existsById(empresaId)) throw NoEncontradoException("Empresa")
        return usuarios.findAllByEmpresaId(empresaId, Pageable.unpaged()).filter { it.rol == Rol.SUPERVISOR }.sortedBy { it.nombre }
            .map { cuentas.dtoDe(it) }
    }

    private fun dto(e: ec.cleantok.identidad.dominio.Empresa): EmpresaPlataformaDto {
        val p = mapOf("e" to e.id)
        val gente = usuarios.findAllByEmpresaId(e.id, Pageable.unpaged()).content
        return EmpresaPlataformaDto(
            e.id, e.nombre, e.ruc, e.activo,
            establecimientos = jdbc.queryForObject("select count(*) from establecimiento where empresa_id = :e and activo", p, Long::class.java)!!,
            usuarios = usuarios.countByEmpresaId(e.id),
            proveedores = jdbc.queryForObject("select count(*) from empresa_proveedor where empresa_id = :e", p, Long::class.java)!!,
            admins = gente.filter { it.rol == Rol.ADMIN_EMPRESA }.sortedBy { it.nombre }.map { UsuarioDto.de(it) },
            supervisores = gente.filter { it.rol == Rol.SUPERVISOR }.sortedBy { it.nombre }.map { cuentas.dtoDe(it) },
            telefono = e.telefono, email = e.email, direccion = e.direccion, lat = e.lat, lng = e.lng,
        )
    }

    /**
     * Para cuando una organización pierde el acceso de su responsable. Admins de empresa, supervisores de
     * establecimiento (los asigna la plataforma) y usuarios de ventas.
     */
    @Transactional
    fun restablecerResponsable(id: UUID): UsuarioDto {
        val u = usuarios.findById(id).orElseThrow { NoEncontradoException("Usuario") }
        if (u.rol !in setOf(Rol.ADMIN_EMPRESA, Rol.SUPERVISOR, Rol.PROVEEDOR)) {
            throw ValidacionException("Desde la plataforma solo se restablece a responsables; el resto lo gestiona su organización")
        }
        return cuentas.restablecer(u)
    }
}

@RestController
@RequestMapping("/api/v1/plataforma")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Plataforma (administrador del sistema)")
class PlataformaController(
    private val servicio: PlataformaService,
    private val proveedores: ProveedorService,
    private val establecimientos: EstablecimientoService,
) {
    @GetMapping("/resumen")
    @Operation(summary = "Cifras de toda la plataforma")
    fun resumen(): ResumenPlataformaDto = servicio.resumen()

    @GetMapping("/empresas")
    @Operation(summary = "Empresas con sus cifras y administradores. Para crear: POST /empresas y POST /empresas/{id}/admins.")
    fun empresas(): List<EmpresaPlataformaDto> = servicio.empresas()

    @PatchMapping("/empresas/{id}")
    @Operation(summary = "Activa o desactiva una empresa (desactivada: su gente no puede entrar y se cierran sus sesiones)")
    fun activarEmpresa(@PathVariable id: UUID, @Valid @RequestBody req: ActivoRequest): EmpresaPlataformaDto =
        servicio.cambiarActivoEmpresa(id, req.activo!!)

    @GetMapping("/empresas/{id}/establecimientos")
    @Operation(summary = "Establecimientos de una empresa con su supervisor (para eliminarlos: DELETE /establecimientos/{id})")
    fun establecimientosDeEmpresa(@PathVariable id: UUID): List<EstablecimientoDto> = establecimientos.deEmpresa(id)

    @PostMapping("/empresas/{id}/establecimientos")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Crea una sede para la empresa y le asigna su supervisor de establecimiento",
        description = "`supervisorId` (un SUPERVISOR existente de la empresa) o `nuevoSupervisor` (cuenta nueva con clave temporal). " +
            "Para cambiarlo después: PUT /establecimientos/{id}/supervisor.",
    )
    fun crearEstablecimiento(@PathVariable id: UUID, @Valid @RequestBody req: EstablecimientoRequest): EstablecimientoDto =
        establecimientos.crearParaEmpresa(id, req)

    @GetMapping("/empresas/{id}/supervisores")
    @Operation(summary = "Supervisores de establecimiento de una empresa")
    fun supervisores(@PathVariable id: UUID): List<UsuarioDto> = servicio.supervisores(id)

    @GetMapping("/proveedores")
    @Operation(summary = "Proveedores de la plataforma, activos e inactivos")
    fun proveedores(): List<ProveedorPlataformaDto> = proveedores.todos()

    @PostMapping("/proveedores")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Alta de un proveedor con su primer usuario de ventas (clave temporal)")
    fun crearProveedor(@Valid @RequestBody req: CrearProveedorRequest): ProveedorCreadoDto = proveedores.crear(req)

    @PatchMapping("/proveedores/{id}")
    @Operation(summary = "Activa o desactiva un proveedor (desactivado: sale del directorio, su gente no puede entrar y se cierran sus sesiones)")
    fun activarProveedor(@PathVariable id: UUID, @Valid @RequestBody req: ActivoRequest): ProveedorPlataformaDto =
        proveedores.cambiarActivo(id, req.activo!!)

    @PostMapping("/usuarios/{id}/restablecer-clave")
    @Operation(summary = "Clave temporal nueva para el admin o un supervisor de una empresa, o el usuario de ventas de un proveedor")
    fun restablecer(@PathVariable id: UUID): UsuarioDto = servicio.restablecerResponsable(id)
}
