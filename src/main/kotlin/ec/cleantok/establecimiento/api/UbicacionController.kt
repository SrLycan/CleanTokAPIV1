package ec.cleantok.establecimiento.api

import ec.cleantok.establecimiento.servicio.QrService
import ec.cleantok.establecimiento.servicio.UbicacionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Ubicaciones y QR")
class UbicacionController(
    private val servicio: UbicacionService,
    private val qr: QrService,
) {
    @GetMapping("/establecimientos/{id}/ubicaciones")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'ENCARGADO')")
    @Operation(summary = "Bodega, pisos y áreas del establecimiento en orden")
    fun listar(@PathVariable id: UUID): List<UbicacionDto> = servicio.listar(id)

    @PostMapping("/establecimientos/{id}/ubicaciones")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crea un piso o área con su QR")
    fun crear(@PathVariable id: UUID, @Valid @RequestBody req: CrearUbicacionRequest): UbicacionDto = servicio.crear(id, req)

    @PutMapping("/establecimientos/{id}/ubicaciones/orden")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Reordena las ubicaciones según la lista enviada")
    fun ordenar(@PathVariable id: UUID, @Valid @RequestBody req: OrdenarUbicacionesRequest): List<UbicacionDto> =
        servicio.ordenar(id, req.ubicacionIds)

    @PutMapping("/ubicaciones/{id}")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Renombra o cambia el tipo (PISO/AREA) de una ubicación")
    fun actualizar(@PathVariable id: UUID, @Valid @RequestBody req: ActualizarUbicacionRequest): UbicacionDto =
        servicio.actualizar(id, req)

    @PatchMapping("/ubicaciones/{id}/desactivar")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Desactiva un piso o área (la bodega no se puede desactivar); su QR deja de funcionar")
    fun desactivar(@PathVariable id: UUID): UbicacionDto = servicio.cambiarActivo(id, false)

    @PatchMapping("/ubicaciones/{id}/activar")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Reactiva una ubicación")
    fun activar(@PathVariable id: UUID): UbicacionDto = servicio.cambiarActivo(id, true)

    @PostMapping("/ubicaciones/{id}/regenerar-qr")
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Genera un QR nuevo e invalida el anterior (hay que reimprimirlo)")
    fun regenerarQr(@PathVariable id: UUID): UbicacionDto = servicio.regenerarQr(id)

    @GetMapping("/ubicaciones/{id}/qr.png", produces = [MediaType.IMAGE_PNG_VALUE])
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Imagen PNG del QR de la ubicación")
    fun qrPng(@PathVariable id: UUID, @RequestParam(defaultValue = "512") tamano: Int): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(qr.png(id, tamano))

    @GetMapping("/establecimientos/{id}/qr-hoja.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize("hasRole('ADMIN_EMPRESA')")
    @Operation(summary = "Hoja A4 imprimible con los QR de todas las ubicaciones activas (4 por página)")
    fun qrHoja(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (nombre, pdf) = qr.hojaPdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(nombre).build().toString())
            .body(pdf)
    }
}
