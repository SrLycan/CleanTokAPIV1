package ec.cleantok.inventario.api

import ec.cleantok.inventario.servicio.MovimientoService
import ec.cleantok.inventario.servicio.SincronizacionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO')")
@Tag(name = "Movimientos")
class MovimientoController(
    private val servicio: MovimientoService,
    private val sincronizacion: SincronizacionService,
) {
    @PostMapping("/movimientos")
    @Operation(
        summary = "Registra un movimiento (ingreso, traslado, consumo, devolución o ajuste)",
        description = "Idempotente por `clientUuid`: si se repite, responde 200 con el resultado original y `duplicado = true`. " +
            "Errores: STOCK_INSUFICIENTE (422, con `disponible`), PRODUCTO_INACTIVO (422), UNIDAD_INCOMPATIBLE (422, con `unidadesPermitidas`), " +
            "VALIDACION (400), NO_AUTORIZADO (403 si un encargado intenta un AJUSTE).",
    )
    @ApiResponse(responseCode = "201", description = "Movimiento aplicado")
    @ApiResponse(responseCode = "200", description = "clientUuid repetido: resultado original, sin aplicar de nuevo")
    fun registrar(@Valid @RequestBody req: MovimientoRequest): ResponseEntity<MovimientoResultadoDto> {
        val r = servicio.registrar(req)
        return ResponseEntity.status(if (r.duplicado) HttpStatus.OK else HttpStatus.CREATED).body(r)
    }

    @GetMapping("/movimientos/{id}")
    @Operation(summary = "Obtiene un movimiento")
    fun obtener(@PathVariable id: UUID): MovimientoResultadoDto = servicio.obtener(id)

    @PostMapping("/sincronizacion/lote")
    @Operation(
        summary = "Sincroniza movimientos y reportes de nivel registrados sin conexión",
        description = "Hasta 100 ítems. Se procesan en orden de `ocurridoEn`, cada uno en su propia transacción. " +
            "La respuesta trae el resultado por ítem (OK, DUPLICADO o ERROR con su código), en el orden original.",
    )
    fun lote(@Valid @RequestBody req: LoteRequest): LoteResultadoDto = sincronizacion.procesar(req)

    @PostMapping("/ubicaciones/{id}/conteo")
    @Operation(
        summary = "Conteo físico: fija la cantidad exacta de cada producto en un piso o en la bodega",
        description = "Por cada producto cuya cantidad difiere del saldo registra un AJUSTE (motivo \"Conteo físico\") por la diferencia, " +
            "en la unidad del producto. Lo pueden hacer el admin y el encargado asignado. Todo el conteo se aplica o nada.",
    )
    fun conteo(@PathVariable id: UUID, @Valid @RequestBody req: ConteoRequest): ConteoResultadoDto = servicio.contar(id, req)
}
