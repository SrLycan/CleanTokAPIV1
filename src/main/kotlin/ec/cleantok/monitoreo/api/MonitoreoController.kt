package ec.cleantok.monitoreo.api

import ec.cleantok.inventario.api.ReporteNivelRequest
import ec.cleantok.monitoreo.dominio.EstadoAlerta
import ec.cleantok.monitoreo.dominio.TipoAlerta
import ec.cleantok.monitoreo.servicio.AlertaService
import ec.cleantok.monitoreo.servicio.ReporteNivelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO')")
@Tag(name = "Nivel y alertas")
class MonitoreoController(
    private val reportes: ReporteNivelService,
    private val alertas: AlertaService,
) {
    @PostMapping("/reportes-nivel")
    @Operation(
        summary = "Reporta el nivel de un producto en un piso (0/25/50/75/100 %), con foto opcional",
        description = "Idempotente por `clientUuid` (201 nuevo, 200 duplicado). Un nivel ≤ 25 % abre NIVEL_CRITICO (ALTA si es 0 %).",
    )
    fun reportar(@Valid @RequestBody req: ReporteNivelRequest): ResponseEntity<ReporteNivelResultadoDto> {
        val r = reportes.registrar(req)
        return ResponseEntity.status(if (r.duplicado) HttpStatus.OK else HttpStatus.CREATED).body(r)
    }

    @GetMapping("/establecimientos/{id}/alertas")
    @Operation(summary = "Alertas del establecimiento (más recientes primero)")
    fun listar(
        @PathVariable id: UUID,
        @RequestParam(required = false) estado: EstadoAlerta?,
        @RequestParam(required = false) tipo: TipoAlerta?,
        @ParameterObject @PageableDefault(size = 50, sort = ["creadaEn"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): Page<AlertaDto> = alertas.listar(id, estado, tipo, pageable)

    @PatchMapping("/alertas/{id}/atender")
    @Operation(
        summary = "Marca una alerta como atendida",
        description = "No se reabre mientras la condición siga igual; vuelve a abrirse si la condición desaparece y reaparece.",
    )
    fun atender(@PathVariable id: UUID, @Valid @RequestBody(required = false) req: AtenderAlertaRequest?): AlertaDto =
        alertas.atender(id, req?.nota)

    @PostMapping("/establecimientos/{id}/alertas/reevaluar")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR')")
    @Operation(summary = "Reevalúa ahora las reglas de alerta del establecimiento (lo mismo que hace el job cada hora)")
    fun reevaluar(@PathVariable id: UUID): List<AlertaDto> = alertas.reevaluar(id)
}
