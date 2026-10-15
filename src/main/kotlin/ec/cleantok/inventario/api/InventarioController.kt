package ec.cleantok.inventario.api

import ec.cleantok.inventario.servicio.InventarioService
import ec.cleantok.inventario.servicio.KardexService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'ENCARGADO')")
@Tag(name = "Inventario y kardex")
class InventarioController(
    private val inventario: InventarioService,
    private val kardex: KardexService,
) {
    @GetMapping("/establecimientos/{id}/inventario")
    @Operation(
        summary = "Inventario por producto: bodega, pisos, total, consumo de 7 y 30 días, cobertura y estado",
        description = "Estado: CRITICO (sin stock o cobertura < 2 días), BAJO (bodega ≤ stock mínimo o cobertura < " +
            "CLEANTOK_DIAS_COBERTURA_MIN), SIN_DATOS (menos de 3 días con consumo en los últimos 14) u OK.",
    )
    fun inventario(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") incluirInactivos: Boolean,
    ): InventarioDto = inventario.inventario(id, incluirInactivos)

    @GetMapping("/productos/{id}/kardex")
    @Operation(
        summary = "Kardex del producto (más reciente primero)",
        description = "Filtra por fecha del evento (`desde`, `hasta`, ISO-8601 con zona) y por ubicación. " +
            "Con `ubicacionId` cada fila trae entrada, salida y saldo de esa ubicación.",
    )
    fun kardex(
        @PathVariable id: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) desde: OffsetDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) hasta: OffsetDateTime?,
        @RequestParam(required = false) ubicacionId: UUID?,
        @ParameterObject @PageableDefault(size = 50, sort = ["secuencia"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): Page<KardexItemDto> = kardex.kardex(id, desde, hasta, ubicacionId, pageable)
}
