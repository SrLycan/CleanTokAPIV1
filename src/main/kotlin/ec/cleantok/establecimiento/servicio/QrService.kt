package ec.cleantok.establecimiento.servicio

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import ec.cleantok.comun.error.ApiException
import ec.cleantok.comun.error.CodigoError
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.api.EstablecimientoResumenDto
import ec.cleantok.establecimiento.api.QrResolucionDto
import ec.cleantok.establecimiento.api.UbicacionResumenDto
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/** Extensión: los módulos de inventario y alertas completan la respuesta de `GET /qr/{token}`. */
interface ContenidoQr {
    fun completar(base: QrResolucionDto): QrResolucionDto
}

@Service
class QrService(
    private val ubicaciones: UbicacionRepository,
    private val establecimientos: EstablecimientoRepository,
    private val ubicacionService: UbicacionService,
    private val acceso: AccesoEstablecimiento,
    private val contenido: List<ContenidoQr>,
) {
    // ---- Escaneo ----

    /**
     * Resuelve el QR escaneado. Token inexistente, inactivo o de otra empresa: QR_INVALIDO (404).
     * Token de la empresa en un establecimiento no asignado al encargado: QR_NO_ASIGNADO (403).
     */
    @Transactional(readOnly = true)
    fun resolver(token: String): QrResolucionDto {
        val usuario = ContextoSeguridad.usuario()
        val u = ubicaciones.findByQrToken(token)
            ?.takeIf { it.activo && it.empresaId == usuario.empresaId }
            ?: throw qrInvalido()
        val e = establecimientos.findById(u.establecimientoId).orElseThrow { qrInvalido() }
        if (!e.activo) throw qrInvalido()
        if (!acceso.puedeOperar(usuario, e)) {
            throw ApiException(HttpStatus.FORBIDDEN, CodigoError.QR_NO_ASIGNADO, "No estás asignado a este establecimiento")
        }
        val bodega = ubicaciones.findByEstablecimientoIdAndTipo(e.id, TipoUbicacion.BODEGA)!!
        val base = QrResolucionDto(
            ubicacion = UbicacionResumenDto(u.id, u.nombre, u.tipo),
            establecimiento = EstablecimientoResumenDto(e.id, e.nombre),
            bodega = UbicacionResumenDto(bodega.id, bodega.nombre, bodega.tipo),
        )
        return contenido.fold(base) { acc, c -> c.completar(acc) }
    }

    private fun qrInvalido() = ApiException(HttpStatus.NOT_FOUND, CodigoError.QR_INVALIDO, "El código QR no es válido o fue reemplazado")

    // ---- Imágenes e impresión ----

    @Transactional(readOnly = true)
    fun png(ubicacionId: UUID, tamano: Int): ByteArray {
        if (tamano !in 128..2048) throw ValidacionException("El tamaño debe estar entre 128 y 2048 px")
        val u = acceso.ubicacionDeMiEmpresa(ubicacionId)
        val salida = ByteArrayOutputStream()
        ImageIO.write(imagen(ubicacionService.urlQr(u.qrToken), tamano), "png", salida)
        return salida.toByteArray()
    }

    /** Hoja A4 con 4 QR por página (2 × 2): establecimiento, ubicación y QR grande. Solo ubicaciones activas. */
    @Transactional(readOnly = true)
    fun hojaPdf(establecimientoId: UUID): Pair<String, ByteArray> {
        val e = acceso.deMiEmpresa(establecimientoId)
        val lista = ubicaciones.findAllByEstablecimientoIdOrderByOrdenAscNombreAsc(e.id).filter { it.activo }
        val normal = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val negrita = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        val salida = ByteArrayOutputStream()
        PDDocument().use { doc ->
            lista.chunked(4).forEach { grupo ->
                val pagina = PDPage(PDRectangle.A4)
                doc.addPage(pagina)
                val anchoCelda = PDRectangle.A4.width / 2
                val altoCelda = PDRectangle.A4.height / 2
                PDPageContentStream(doc, pagina).use { cs ->
                    // Líneas de corte punteadas.
                    cs.setLineDashPattern(floatArrayOf(4f, 4f), 0f)
                    cs.setStrokingColor(0.6f, 0.6f, 0.6f)
                    cs.moveTo(anchoCelda, 0f); cs.lineTo(anchoCelda, PDRectangle.A4.height)
                    cs.moveTo(0f, altoCelda); cs.lineTo(PDRectangle.A4.width, altoCelda)
                    cs.stroke()
                    cs.setLineDashPattern(floatArrayOf(), 0f)
                    cs.setNonStrokingColor(0f, 0f, 0f)

                    grupo.forEachIndexed { i, u ->
                        val x0 = (i % 2) * anchoCelda
                        val y0 = PDRectangle.A4.height - (i / 2 + 1) * altoCelda
                        val margen = 24f
                        val ancho = anchoCelda - 2 * margen
                        texto(cs, normal, e.nombre, 11f, x0 + margen, y0 + altoCelda - 40f, ancho, centrado = true)
                        texto(cs, negrita, u.nombre, 22f, x0 + margen, y0 + altoCelda - 70f, ancho, centrado = true)
                        val lado = 230f
                        val qr = LosslessFactory.createFromImage(doc, imagen(ubicacionService.urlQr(u.qrToken), 690))
                        cs.drawImage(qr, x0 + (anchoCelda - lado) / 2, y0 + 70f, lado, lado)
                        texto(cs, normal, "Escanea con la app CleanTok", 10f, x0 + margen, y0 + 48f, ancho, centrado = true)
                    }
                }
            }
            if (lista.isEmpty()) doc.addPage(PDPage(PDRectangle.A4))
            doc.save(salida)
        }
        return "qr-${slug(e.nombre)}.pdf" to salida.toByteArray()
    }

    private fun imagen(contenido: String, tamano: Int): BufferedImage {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val matriz = QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, tamano, tamano, hints)
        return MatrixToImageWriter.toBufferedImage(matriz)
    }

    /** Escribe una línea ajustando el tamaño (y recortando) para que quepa en [ancho]. */
    private fun texto(
        cs: PDPageContentStream,
        fuente: PDType1Font,
        original: String,
        tamano: Float,
        x: Float,
        y: Float,
        ancho: Float,
        centrado: Boolean = false,
    ) {
        var t = compatible(fuente, original)
        var size = tamano
        fun medida() = fuente.getStringWidth(t) / 1000 * size
        while (medida() > ancho && size > 9f) size -= 1f
        while (medida() > ancho && t.length > 4) t = t.dropLast(4) + "..."
        cs.beginText()
        cs.setFont(fuente, size)
        cs.newLineAtOffset(if (centrado) x + (ancho - medida()) / 2 else x, y)
        cs.showText(t)
        cs.endText()
    }

    /** Las fuentes estándar solo cubren WinAnsi (incluye tildes y ñ); otros caracteres se reemplazan. */
    private fun compatible(fuente: PDType1Font, s: String) =
        s.map { c -> if (seguro(fuente, c.toString())) c else '?' }.joinToString("")

    private fun seguro(fuente: PDType1Font, s: String) = runCatching { fuente.encode(s) }.isSuccess

    private fun slug(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "establecimiento" }
}
