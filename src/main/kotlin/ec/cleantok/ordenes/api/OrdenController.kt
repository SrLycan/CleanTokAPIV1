package ec.cleantok.ordenes.api

import ec.cleantok.ordenes.servicio.OrdenService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('ADMIN_EMPRESA')")
@Tag(name = "Órdenes de compra (PDF)")
class OrdenController(private val servicio: OrdenService) {

    @PostMapping("/establecimientos/{id}/ordenes", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Sube el PDF de la orden de compra o contrato y extrae los materiales en segundo plano",
        description = "Responde 202 con la orden en PROCESANDO; consulta GET /ordenes/{id} hasta PENDIENTE_REVISION o ERROR. " +
            "Errores: PDF_INVALIDO (no es PDF, >15 MB, cifrado o dañado), PDF_DUPLICADO (409, con `ordenId`). " +
            "`permitirEnvioPdfIa` autoriza enviar el PDF completo a la IA solo si no tiene capa de texto.",
    )
    fun subir(
        @PathVariable id: UUID,
        @RequestPart("archivo") archivo: MultipartFile,
        @RequestParam(defaultValue = "false") permitirEnvioPdfIa: Boolean,
    ): ResponseEntity<OrdenDto> {
        val o = servicio.subir(id, archivo, permitirEnvioPdfIa)
        val ubicacion = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/ordenes/{id}").buildAndExpand(o.id).toUri()
        return ResponseEntity.accepted().location(ubicacion).body(o)
    }

    @GetMapping("/establecimientos/{id}/ordenes")
    @Operation(summary = "Órdenes subidas al establecimiento (más recientes primero)")
    fun listar(@PathVariable id: UUID, @ParameterObject pageable: Pageable): Page<OrdenDto> = servicio.listar(id, pageable)

    @GetMapping("/ordenes/{id}")
    @Operation(summary = "Estado, datos de cabecera, avisos y resumen de la extracción")
    fun obtener(@PathVariable id: UUID): OrdenDto = servicio.obtener(id)

    @GetMapping("/ordenes/{id}/texto")
    @Operation(summary = "Texto extraído del PDF (solo admin; puede contener datos personales)")
    fun texto(@PathVariable id: UUID): TextoOrdenDto = TextoOrdenDto(servicio.texto(id))

    @GetMapping("/ordenes/{id}/pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @Operation(summary = "Descarga el PDF original")
    fun pdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (nombre, bytes) = servicio.pdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(nombre, Charsets.UTF_8).build().toString())
            .body(bytes)
    }

    @GetMapping("/ordenes/{id}/materiales")
    @Operation(summary = "Materiales propuestos agrupados por sección, con su texto literal, página, confianza y motivo de revisión")
    fun materiales(@PathVariable id: UUID): List<GrupoMaterialesDto> = servicio.materiales(id)

    @PostMapping("/ordenes/{id}/materiales")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Agrega un material a mano (también para órdenes en ERROR o sin capa de texto)")
    fun agregar(@PathVariable id: UUID, @Valid @RequestBody req: MaterialManualRequest): MaterialDto = servicio.agregarManual(id, req)

    @PatchMapping("/materiales/{id}")
    @Operation(summary = "Edita, acepta o descarta un material propuesto")
    fun editar(@PathVariable id: UUID, @Valid @RequestBody req: EditarMaterialRequest): MaterialDto = servicio.editar(id, req)

    @PostMapping("/ordenes/{id}/materiales/fusionar")
    @Operation(summary = "Fusiona varios materiales en el primero (los demás quedan descartados)")
    fun fusionar(@PathVariable id: UUID, @Valid @RequestBody req: FusionarMaterialesRequest): List<GrupoMaterialesDto> =
        servicio.fusionar(id, req)

    @PostMapping("/ordenes/{id}/reintentar-ia")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "Vuelve a procesar la orden forzando la extracción con IA (reemplaza las propuestas actuales)",
        description = "EXTRACCION_FALLIDA (422) si la IA no está habilitada en el servidor.",
    )
    fun reintentarIa(@PathVariable id: UUID, @RequestBody(required = false) req: ReintentarIaRequest?): OrdenDto =
        servicio.reintentarIa(id, req?.permitirEnvioPdfIa)

    @PostMapping("/ordenes/{id}/confirmar")
    @Operation(
        summary = "Confirma la revisión: crea los productos seleccionados con stock 0",
        description = "Idempotente: confirmar dos veces no duplica. Después, los productos se editan desde /productos.",
    )
    fun confirmar(@PathVariable id: UUID): ConfirmacionDto = servicio.confirmar(id)
}
