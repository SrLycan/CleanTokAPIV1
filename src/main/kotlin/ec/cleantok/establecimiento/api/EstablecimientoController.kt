package ec.cleantok.establecimiento.api

import ec.cleantok.establecimiento.servicio.EstablecimientoService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import ec.cleantok.identidad.api.EstablecimientoAsignadoDto
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/establecimientos")
@Tag(name = "Establecimientos")
class EstablecimientoController(private val servicio: EstablecimientoService) {

    @GetMapping("/mios")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'ENCARGADO')")
    @Operation(summary = "Establecimientos activos donde opero (asignados al encargado; todos los de la empresa para el admin)")
    fun mios(): List<EstablecimientoDto> = servicio.mios()

    @GetMapping
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Lista los establecimientos de mi empresa")
    fun listar(@RequestParam(required = false) activo: Boolean?, @ParameterObject pageable: Pageable): Page<EstablecimientoDto> =
        servicio.listar(activo, pageable)

    @PostMapping
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crea un establecimiento; su bodega se crea automáticamente")
    fun crear(@Valid @RequestBody req: EstablecimientoRequest): EstablecimientoDto = servicio.crear(req)

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'ENCARGADO')")
    @Operation(summary = "Obtiene un establecimiento")
    fun obtener(@PathVariable id: UUID): EstablecimientoDto = servicio.obtener(id)

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Actualiza un establecimiento")
    fun actualizar(@PathVariable id: UUID, @Valid @RequestBody req: EstablecimientoRequest): EstablecimientoDto =
        servicio.actualizar(id, req)

    @PatchMapping("/{id}/desactivar")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Desactiva el establecimiento: sus QR dejan de funcionar")
    fun desactivar(@PathVariable id: UUID): EstablecimientoDto = servicio.cambiarActivo(id, false)

    @PatchMapping("/{id}/activar")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Reactiva el establecimiento")
    fun activar(@PathVariable id: UUID): EstablecimientoDto = servicio.cambiarActivo(id, true)

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN_EMPRESA')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Elimina el establecimiento (lógico: sus QR dejan de funcionar; movimientos y pedidos se conservan)",
        description = "Solo el admin que lo creó o el administrador de la plataforma. Otro admin de la empresa: 403.",
    )
    fun eliminar(@PathVariable id: UUID) = servicio.eliminar(id)

    @PutMapping("/asignaciones/{usuarioId}")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Define en qué establecimientos opera un encargado (reemplaza la lista)")
    fun asignarAUsuario(@PathVariable usuarioId: UUID, @RequestBody ids: List<UUID>): List<EstablecimientoAsignadoDto> =
        servicio.asignarEstablecimientos(usuarioId, ids)

    @GetMapping("/{id}/encargados")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Encargados asignados")
    fun encargados(@PathVariable id: UUID): List<EncargadoAsignadoDto> = servicio.encargados(id)

    @PutMapping("/{id}/encargados")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Reemplaza la lista de encargados asignados")
    fun asignar(@PathVariable id: UUID, @Valid @RequestBody req: AsignarEncargadosRequest): List<EncargadoAsignadoDto> =
        servicio.asignarEncargados(id, req)

    @DeleteMapping("/{id}/encargados/{usuarioId}")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Quita un encargado del establecimiento")
    fun quitar(@PathVariable id: UUID, @PathVariable usuarioId: UUID) = servicio.quitarEncargado(id, usuarioId)
}
