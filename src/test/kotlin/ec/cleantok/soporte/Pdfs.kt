package ec.cleantok.soporte

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File

/** PDFs generados en las pruebas (sin datos reales). */
object Pdfs {
    fun conTexto(vararg lineas: String): ByteArray = generar { doc, cs ->
        cs.beginText()
        cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
        cs.setLeading(14f)
        cs.newLineAtOffset(50f, 780f)
        lineas.forEach { cs.showText(it); cs.newLine() }
        cs.endText()
    }

    /** Solo una imagen, como un PDF escaneado: sin capa de texto. */
    fun escaneado(): ByteArray = generar { doc, cs ->
        val img = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 400, 200); color = Color.BLACK; drawString("cloro, jabon, fundas", 20, 100); dispose() }
        cs.drawImage(LosslessFactory.createFromImage(doc, img), 50f, 500f)
    }

    fun cifrado(): ByteArray {
        val salida = ByteArrayOutputStream()
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.protect(StandardProtectionPolicy("dueno", "clave", AccessPermission()).apply { encryptionKeyLength = 128 })
            doc.save(salida)
        }
        return salida.toByteArray()
    }

    fun orden(nombre: String): ByteArray? = File("src/test/resources/ordenes/$nombre").takeIf { it.exists() }?.readBytes()

    private fun generar(dibujar: (PDDocument, PDPageContentStream) -> Unit): ByteArray {
        val salida = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { dibujar(doc, it) }
            doc.save(salida)
        }
        return salida.toByteArray()
    }
}
