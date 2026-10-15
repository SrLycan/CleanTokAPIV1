package ec.cleantok.establecimiento

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.jayway.jsonpath.JsonPath
import ec.cleantok.establecimiento.api.UbicacionController
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@VerificaAislamiento(UbicacionController::class)
class UbicacionControllerTest : PruebaIntegracion() {

    @Autowired
    lateinit var ubicaciones: UbicacionRepository

    @Test
    fun `crear pisos, listarlos en orden y reordenarlos`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val e = fabrica.establecimiento(emp)

        fun crear(nombre: String, tipo: String) = JsonPath.read<String>(
            mvc.post("/api/v1/establecimientos/${e.id}/ubicaciones") {
                header("Authorization", admin)
                contentType = MediaType.APPLICATION_JSON
                content = """{"nombre":"$nombre","tipo":"$tipo"}"""
            }.andExpect {
                status { isCreated() }
                jsonPath("$.urlQr") { value(org.hamcrest.Matchers.startsWith("http://localhost:8080/q/")) }
            }.andReturn().response.contentAsString,
            "$.id",
        )
        val p1 = crear("Piso 1", "PISO")
        val p2 = crear("Piso 2", "PISO")
        val rec = crear("Planta baja – recepción", "AREA")

        mvc.get("/api/v1/establecimientos/${e.id}/ubicaciones") { header("Authorization", admin) }.andExpect {
            jsonPath("$[*].nombre", contains("Bodega", "Piso 1", "Piso 2", "Planta baja – recepción"))
        }
        val bodega = fabrica.bodega(e).id
        mvc.put("/api/v1/establecimientos/${e.id}/ubicaciones/orden") {
            header("Authorization", admin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ubicacionIds":["$bodega","$rec","$p2","$p1"]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$[*].nombre", contains("Bodega", "Planta baja – recepción", "Piso 2", "Piso 1"))
        }
    }

    @Test
    fun `no se puede crear otra bodega, cambiarle el tipo ni desactivarla`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val e = fabrica.establecimiento(emp)
        val bodega = fabrica.bodega(e)
        mvc.post("/api/v1/establecimientos/${e.id}/ubicaciones") {
            header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Bodega 2","tipo":"BODEGA"}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.codigo") { value("VALIDACION") } }
        mvc.put("/api/v1/ubicaciones/${bodega.id}") {
            header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Bodega central","tipo":"PISO"}"""
        }.andExpect { status { isBadRequest() } }
        mvc.patch("/api/v1/ubicaciones/${bodega.id}/desactivar") { header("Authorization", admin) }
            .andExpect { status { isBadRequest() } }
        mvc.put("/api/v1/ubicaciones/${bodega.id}") {
            header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Bodega central"}"""
        }.andExpect { status { isOk() }; jsonPath("$.nombre") { value("Bodega central") } }
    }

    @Test
    fun `el PNG del QR se escanea y contiene solo la URL pública con el token`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val piso = fabrica.piso(fabrica.establecimiento(emp))

        val png = mvc.get("/api/v1/ubicaciones/${piso.id}/qr.png?tamano=400") { header("Authorization", admin) }
            .andExpect { status { isOk() }; content { contentType(MediaType.IMAGE_PNG) } }
            .andReturn().response.contentAsByteArray
        val imagen = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(400, imagen.width)
        val texto = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(imagen)))).text
        assertEquals("http://localhost:8080/q/${piso.qrToken}", texto)

        mvc.get("/api/v1/ubicaciones/${piso.id}/qr.png?tamano=5000") { header("Authorization", admin) }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `la hoja PDF trae 4 QR por página y cada QR impreso se escanea con su URL`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val e = fabrica.establecimiento(emp, nombre = "Edificio Ñandú – Matriz")
        val pisos = (1..4).map { fabrica.piso(e, "Piso $it") }
        fabrica.piso(e, "Piso inactivo", activo = false)
        val esperadas = (listOf(fabrica.bodega(e)) + pisos).map { "http://localhost:8080/q/${it.qrToken}" }.toSet()

        val pdf = mvc.get("/api/v1/establecimientos/${e.id}/qr-hoja.pdf") { header("Authorization", admin) }
            .andExpect { status { isOk() }; content { contentType(MediaType.APPLICATION_PDF) } }
            .andReturn().response.contentAsByteArray

        Loader.loadPDF(pdf).use { doc ->
            assertEquals(2, doc.numberOfPages) // 5 activas → 4 + 1
            val texto = PDFTextStripper().getText(doc)
            assertTrue("Edificio Ñandú – Matriz" in texto)
            assertTrue("Piso 4" in texto)
            assertTrue("Piso inactivo" !in texto)

            val renderer = PDFRenderer(doc)
            val leidas = (0 until doc.numberOfPages).flatMap { pagina ->
                val img = renderer.renderImageWithDPI(pagina, 150f)
                QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img)))).map { it.text }
            }.toSet()
            assertEquals(esperadas, leidas)
        }
    }

    @Test
    fun `regenerar el QR invalida el token anterior`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val piso = fabrica.piso(fabrica.establecimiento(emp))
        val anterior = piso.qrToken

        mvc.post("/api/v1/ubicaciones/${piso.id}/regenerar-qr") { header("Authorization", admin) }.andExpect { status { isOk() } }
        val nuevo = ubicaciones.findById(piso.id).get().qrToken
        assertNotEquals(anterior, nuevo)

        mvc.get("/api/v1/qr/$anterior") { header("Authorization", admin) }.andExpect {
            status { isNotFound() }
            jsonPath("$.codigo") { value("QR_INVALIDO") }
        }
        mvc.get("/api/v1/qr/$nuevo") { header("Authorization", admin) }.andExpect { status { isOk() } }
    }

    @Test
    fun `el encargado lista solo ubicaciones activas, sin URL del QR, y no puede administrarlas`() {
        val emp = fabrica.empresa()
        val e = fabrica.establecimiento(emp)
        val piso = fabrica.piso(e, "Piso 1")
        fabrica.piso(e, "Piso cerrado", activo = false)
        val enc = fabrica.encargado(emp)
        fabrica.asignar(enc, e)
        val token = fabrica.token(enc)

        mvc.get("/api/v1/establecimientos/${e.id}/ubicaciones") { header("Authorization", token) }.andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(2))
            jsonPath("$[*].urlQr") { value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())) }
        }
        mvc.get("/api/v1/ubicaciones/${piso.id}/qr.png") { header("Authorization", token) }.andExpect { status { isForbidden() } }
        mvc.post("/api/v1/ubicaciones/${piso.id}/regenerar-qr") { header("Authorization", token) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `aislamiento - ubicaciones de otra empresa responden 404`() {
        val ajeno = fabrica.establecimiento(fabrica.empresa())
        val pisoAjeno = fabrica.piso(ajeno)
        val admin = fabrica.token(fabrica.admin(fabrica.empresa()))
        listOf(
            mvc.get("/api/v1/establecimientos/${ajeno.id}/ubicaciones") { header("Authorization", admin) },
            mvc.post("/api/v1/establecimientos/${ajeno.id}/ubicaciones") {
                header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
                content = """{"nombre":"Intruso","tipo":"PISO"}"""
            },
            mvc.put("/api/v1/establecimientos/${ajeno.id}/ubicaciones/orden") {
                header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
                content = """{"ubicacionIds":["${pisoAjeno.id}"]}"""
            },
            mvc.put("/api/v1/ubicaciones/${pisoAjeno.id}") {
                header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
                content = """{"nombre":"X"}"""
            },
            mvc.patch("/api/v1/ubicaciones/${pisoAjeno.id}/desactivar") { header("Authorization", admin) },
            mvc.post("/api/v1/ubicaciones/${pisoAjeno.id}/regenerar-qr") { header("Authorization", admin) },
            mvc.get("/api/v1/ubicaciones/${pisoAjeno.id}/qr.png") { header("Authorization", admin) },
            mvc.get("/api/v1/establecimientos/${ajeno.id}/qr-hoja.pdf") { header("Authorization", admin) },
        ).forEach { it.andExpect { status { isNotFound() } } }
        assertEquals(pisoAjeno.qrToken, ubicaciones.findById(pisoAjeno.id).get().qrToken)
    }
}
