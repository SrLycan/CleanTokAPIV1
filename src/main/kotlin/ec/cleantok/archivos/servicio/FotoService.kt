package ec.cleantok.archivos.servicio

import ec.cleantok.archivos.api.FotoDto
import ec.cleantok.archivos.dominio.TipoArchivo
import ec.cleantok.comun.error.ValidacionException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fotos de evidencia (nivel del producto, movimientos). Acepta JPEG, PNG y WEBP de hasta 5 MB,
 * detectados por sus bytes iniciales (no por la extensión), y guarda siempre un JPEG de 1600 px como máximo.
 * Al recodificar se descartan los metadatos EXIF (incluida la ubicación GPS).
 */
@Service
class FotoService(private val archivos: ArchivoService) {

    @Transactional
    fun subir(bytes: ByteArray, nombreOriginal: String?): FotoDto {
        if (bytes.isEmpty()) throw ValidacionException("El archivo está vacío")
        if (bytes.size > MAX_BYTES) throw ValidacionException("La foto supera el máximo de 5 MB")
        detectarFormato(bytes) ?: throw ValidacionException("Formato no soportado: usa JPEG, PNG o WEBP")
        val original = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: throw ValidacionException("No se pudo leer la imagen; puede estar dañada")
        val imagen = redimensionar(original)
        val jpeg = jpeg(imagen)
        val a = archivos.guardar(TipoArchivo.FOTO, jpeg, "image/jpeg", "jpg", nombreOriginal)
        return FotoDto(a.id, a.mime, a.tamano, imagen.width, imagen.height)
    }

    /** Solo JPEG (FF D8 FF), PNG (89 50 4E 47) y WEBP (RIFF....WEBP). */
    fun detectarFormato(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpeg"
        b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "png"
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }

    /** Escala para que el lado mayor sea ≤ 1600 px y pasa a RGB (las transparencias quedan sobre blanco). */
    private fun redimensionar(img: BufferedImage): BufferedImage {
        val mayor = max(img.width, img.height)
        val factor = if (mayor > LADO_MAX) LADO_MAX.toDouble() / mayor else 1.0
        val w = max(1, (img.width * factor).roundToInt())
        val h = max(1, (img.height * factor).roundToInt())
        val salida = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = salida.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return salida
    }

    private fun jpeg(img: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val salida = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(salida).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            writer.write(null, IIOImage(img, null, null), param)
            writer.dispose()
        }
        return salida.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val LADO_MAX = 1600
    }
}
