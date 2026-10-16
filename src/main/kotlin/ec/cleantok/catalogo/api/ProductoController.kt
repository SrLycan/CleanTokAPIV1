package ec.cleantok.catalogo.api

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.servicio.ProductoService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
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
@RequestMapping("/api/v1")
@Tag(name = "Productos")
class ProductoController(private val servicio: ProductoService) {

    @GetMapping("/establecimientos/{id}/productos")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO')")
    @Operation(summary = "Catálogo de productos del establecimiento (el encargado solo ve los activos)")
    fun listar(
        @PathVariable id: UUID,
        @RequestParam(required = false) activo: Boolean?,
        @RequestParam(required = false) categoria: Categoria?,
        @RequestParam(required = false) abastece: Abastece?,
        @RequestParam(required = false) q: String?,
        @ParameterObject @PageableDefault(size = 50, sort = ["nombre"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): Page<ProductoDto> = servicio.listar(id, activo, categoria, abastece, q, pageable)

    @PostMapping("/establecimientos/{id}/productos")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Agrega un producto manual (nace con stock 0)")
    fun crear(@PathVariable id: UUID, @Valid @RequestBody req: ProductoRequest): ProductoDto = servicio.crearManual(id, req)

    @GetMapping("/productos/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO')")
    @Operation(summary = "Obtiene un producto")
    fun obtener(@PathVariable id: UUID): ProductoDto = servicio.obtener(id)

    @PutMapping("/productos/{id}")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Edita un producto (la unidad no se puede cambiar si ya tiene movimientos)")
    fun actualizar(@PathVariable id: UUID, @Valid @RequestBody req: ProductoRequest): ProductoDto = servicio.actualizar(id, req)

    @PatchMapping("/productos/{id}/desactivar")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Desactiva el producto: deja de aceptar movimientos (el kardex se conserva)")
    fun desactivar(@PathVariable id: UUID): ProductoDto = servicio.cambiarActivo(id, false)

    @PatchMapping("/productos/{id}/activar")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Reactiva el producto")
    fun activar(@PathVariable id: UUID): ProductoDto = servicio.cambiarActivo(id, true)

    @DeleteMapping("/productos/{id}")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Borra un producto sin movimientos; si tiene movimientos responde CONFLICTO (usa desactivar)")
    fun eliminar(@PathVariable id: UUID) = servicio.eliminar(id)
}
