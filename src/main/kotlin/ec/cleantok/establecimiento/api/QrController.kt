package ec.cleantok.establecimiento.api

import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.establecimiento.servicio.QrService
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "Ubicaciones y QR")
class QrController(private val qr: QrService) {

    @GetMapping("/api/v1/qr/{token}")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO')")
    @Operation(
        summary = "Resuelve un QR escaneado",
        description = "Android intercepta `{CLEANTOK_URL_PUBLICA}/q/{token}` y llama aquí. " +
            "Errores: QR_INVALIDO (404) si el token no existe, fue regenerado o está inactivo; " +
            "QR_NO_ASIGNADO (403) si el encargado no está asignado a ese establecimiento.",
    )
    fun resolver(@PathVariable token: String): QrResolucionDto = qr.resolver(token)
}

/**
 * Rutas públicas para el deep link. La página `/q/{token}` no consulta la base ni revela nada:
 * solo indica que se abra con la app (si Android tiene la app instalada, ni siquiera llega aquí).
 */
@RestController
@Hidden
class QrPublicoController(private val props: CleanTokProperties) {

    @GetMapping("/q/{token}", produces = [MediaType.TEXT_HTML_VALUE])
    fun pagina(@PathVariable token: String): String =
        """
        <!doctype html>
        <html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>CleanTok</title>
        <style>body{font-family:system-ui,sans-serif;max-width:28rem;margin:4rem auto;padding:0 1rem;color:#1b2b26}
        h1{font-size:1.4rem}p{line-height:1.5}</style></head>
        <body><h1>Código QR de CleanTok</h1>
        <p>Este código registra el inventario de insumos de limpieza de este lugar.</p>
        <p>Ábrelo desde la app <strong>CleanTok</strong> en el celular del encargado de limpieza.</p></body></html>
        """.trimIndent()

    /** Digital Asset Links para App Links verificados de Android (solo si está configurado). */
    @GetMapping("/.well-known/assetlinks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun assetLinks(): ResponseEntity<Any> {
        val android = props.android
        if (android.paquete.isBlank() || android.sha256.none { it.isNotBlank() }) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            listOf(
                mapOf(
                    "relation" to listOf("delegate_permission/common.handle_all_urls"),
                    "target" to mapOf(
                        "namespace" to "android_app",
                        "package_name" to android.paquete,
                        "sha256_cert_fingerprints" to android.sha256.filter { it.isNotBlank() },
                    ),
                ),
            ),
        )
    }
}
