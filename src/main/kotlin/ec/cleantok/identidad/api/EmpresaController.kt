package ec.cleantok.identidad.api

import ec.cleantok.identidad.servicio.UsuarioService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/empresas")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Empresas (super admin)")
class EmpresaController(private val servicio: UsuarioService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crea una empresa de limpieza")
    fun crear(@Valid @RequestBody req: CrearEmpresaRequest): EmpresaDto = servicio.crearEmpresa(req)

    @GetMapping
    @Operation(summary = "Lista las empresas")
    fun listar(@ParameterObject pageable: Pageable): Page<EmpresaDto> = servicio.listarEmpresas(pageable)

    @PutMapping("/{id}/contacto")
    @Operation(summary = "Teléfono, correo, dirección y ubicación de la empresa (los ve el proveedor)")
    fun contacto(@PathVariable id: UUID, @Valid @RequestBody req: ContactoEmpresaRequest): EmpresaDto = servicio.actualizarContacto(id, req)

    @PostMapping("/{id}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crea un ADMIN_EMPRESA para la empresa")
    fun crearAdmin(@PathVariable id: UUID, @Valid @RequestBody req: CrearUsuarioRequest): UsuarioDto =
        servicio.crearAdmin(id, req)
}

/** Datos de la propia empresa: el admin mantiene el contacto que ven sus proveedores. */
@RestController
@RequestMapping("/api/v1/empresa")
@PreAuthorize("hasRole('ADMIN_EMPRESA')")
@Tag(name = "Usuarios (admin)")
class MiEmpresaController(private val servicio: UsuarioService) {

    @GetMapping
    @Operation(summary = "Mi empresa: razón social, RUC y contacto")
    fun obtener(): EmpresaDto = servicio.miEmpresa()

    @PutMapping
    @Operation(summary = "Actualiza teléfono, correo, dirección y ubicación de mi empresa (los ve el proveedor que cotiza)")
    fun actualizar(@Valid @RequestBody req: ContactoEmpresaRequest): EmpresaDto =
        servicio.actualizarContacto(ec.cleantok.comun.seguridad.ContextoSeguridad.empresaId(), req)
}
