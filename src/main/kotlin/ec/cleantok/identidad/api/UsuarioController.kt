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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/usuarios")
@PreAuthorize("hasRole('ADMIN_EMPRESA')")
@Tag(name = "Usuarios (admin)")
class UsuarioController(private val servicio: UsuarioService) {

    @GetMapping
    @Operation(summary = "Lista los usuarios de mi empresa")
    fun listar(@ParameterObject pageable: Pageable): Page<UsuarioDto> = servicio.listar(pageable)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Crea un encargado (u otro admin) en mi empresa",
        description = "Sin `password`, devuelve una `claveTemporal` (una sola vez) que el usuario cambia al entrar.",
    )
    fun crear(@Valid @RequestBody req: CrearUsuarioRequest): UsuarioDto = servicio.crearEncargado(req)

    @GetMapping("/{id}")
    @Operation(summary = "Obtiene un usuario de mi empresa")
    fun obtener(@PathVariable id: UUID): UsuarioDto = servicio.obtener(id)

    @PostMapping("/{id}/restablecer-clave")
    @Operation(summary = "Genera una clave temporal nueva y cierra sus sesiones")
    fun restablecer(@PathVariable id: UUID): UsuarioDto = servicio.restablecerClave(id)

    @PatchMapping("/{id}")
    @Operation(summary = "Cambia nombre, contraseña o estado. Desactivar cierra sus sesiones.")
    fun actualizar(@PathVariable id: UUID, @Valid @RequestBody req: ActualizarUsuarioRequest): UsuarioDto =
        servicio.actualizar(id, req)
}
