package ec.cleantok.archivos.api

import ec.cleantok.archivos.dominio.TipoArchivo
import ec.cleantok.archivos.servicio.ArchivoService
import ec.cleantok.archivos.servicio.FotoService
import ec.cleantok.comun.error.NoEncontradoException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.util.UUID

data class FotoDto(val id: UUID, val mime: String, val tamano: Long, val ancho: Int, val alto: Int)

@RestController
@RequestMapping("/api/v1/fotos")
@PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO')")
@Tag(name = "Fotos")
class FotoController(
    private val fotos: FotoService,
    private val archivos: ArchivoService,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Sube una foto de evidencia (JPEG, PNG o WEBP, máximo 5 MB)",
        description = "Se recomprime a JPEG con el lado mayor ≤ 1600 px y sin metadatos. Usa el `id` en movimientos o reportes de nivel.",
    )
    fun subir(@RequestPart("archivo") archivo: MultipartFile): FotoDto = fotos.subir(archivo.bytes, archivo.originalFilename)

    @GetMapping("/{id}", produces = [MediaType.IMAGE_JPEG_VALUE])
    @Transactional(readOnly = true)
    @Operation(summary = "Descarga una foto de mi empresa")
    fun obtener(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val a = archivos.deMiEmpresa(id)
        if (a.tipo != TipoArchivo.FOTO) throw NoEncontradoException("Foto")
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
            .body(archivos.contenido(a))
    }
}
