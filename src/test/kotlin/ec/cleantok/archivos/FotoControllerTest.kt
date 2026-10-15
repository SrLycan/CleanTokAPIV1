package ec.cleantok.archivos

import com.jayway.jsonpath.JsonPath
import ec.cleantok.archivos.api.FotoController
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import ec.cleantok.soporte.jsonMovimiento
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals

@VerificaAislamiento(FotoController::class)
class FotoControllerTest : PruebaIntegracion() {

    private fun png(ancho: Int, alto: Int): ByteArray {
        val img = BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(30, 120, 200, 128)
        g.fillRect(0, 0, ancho, alto)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun subir(token: String, bytes: ByteArray, nombre: String = "nivel.png", tipo: String = "image/png") =
        mvc.multipart("/api/v1/fotos") {
            file(MockMultipartFile("archivo", nombre, tipo, bytes))
            header("Authorization", token)
        }

    @Test
    fun `una foto grande se recomprime a JPEG de 1600 px como máximo`() {
        val enc = fabrica.token(fabrica.encargado(fabrica.empresa()))
        val r = subir(enc, png(3000, 2000)).andExpect {
            status { isCreated() }
            jsonPath("$.mime") { value("image/jpeg") }
            jsonPath("$.ancho") { value(1600) }
            jsonPath("$.alto") { value(1067) }
        }.andReturn().response.contentAsString
        val id = JsonPath.read<String>(r, "$.id")

        val jpeg = mvc.get("/api/v1/fotos/$id") { header("Authorization", enc) }
            .andExpect { status { isOk() }; content { contentType(MediaType.IMAGE_JPEG) } }
            .andReturn().response.contentAsByteArray
        val img = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(1600, img.width)
    }

    @Test
    fun `rechaza formatos no soportados (por contenido, no por extensión) y archivos de más de 5 MB`() {
        val enc = fabrica.token(fabrica.encargado(fabrica.empresa()))
        subir(enc, "esto no es una imagen".toByteArray(), "foto.jpg", "image/jpeg").andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("VALIDACION") }
        }
        val gif = "GIF89a".toByteArray() + ByteArray(20)
        subir(enc, gif, "a.gif", "image/gif").andExpect { status { isBadRequest() } }
        val grande = ByteArray(5 * 1024 * 1024 + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        subir(enc, grande, "grande.jpg", "image/jpeg").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("La foto supera el máximo de 5 MB") }
        }
    }

    @Test
    fun `la foto se puede adjuntar a un movimiento de la misma empresa`() {
        val emp = fabrica.empresa()
        val e = fabrica.establecimiento(emp)
        val p = fabrica.producto(e)
        // El ingreso a bodega es del admin.
        val t = fabrica.token(fabrica.admin(emp))
        val fotoId = JsonPath.read<String>(subir(t, png(100, 100)).andReturn().response.contentAsString, "$.id")
        val cuerpo = jsonMovimiento("INGRESO", p.id, fabrica.bodega(e).id, "1").dropLast(1) + ""","fotoId":"$fotoId"}"""
        mvc.post("/api/v1/movimientos") {
            header("Authorization", t); contentType = MediaType.APPLICATION_JSON; content = cuerpo
        }.andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.fotoId") { value(fotoId) }
        }
        // Foto de otra empresa: rechazada.
        val ajena = JsonPath.read<String>(subir(fabrica.token(fabrica.encargado(fabrica.empresa())), png(10, 10)).andReturn().response.contentAsString, "$.id")
        val otro = jsonMovimiento("INGRESO", p.id, fabrica.bodega(e).id, "1").dropLast(1) + ""","fotoId":"$ajena"}"""
        mvc.post("/api/v1/movimientos") {
            header("Authorization", t); contentType = MediaType.APPLICATION_JSON; content = otro
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `aislamiento - no se puede ver la foto de otra empresa`() {
        val id = JsonPath.read<String>(
            subir(fabrica.token(fabrica.encargado(fabrica.empresa())), png(10, 10)).andReturn().response.contentAsString, "$.id",
        )
        mvc.get("/api/v1/fotos/$id") { header("Authorization", fabrica.token(fabrica.admin(fabrica.empresa()))) }
            .andExpect { status { isNotFound() } }
    }
}
