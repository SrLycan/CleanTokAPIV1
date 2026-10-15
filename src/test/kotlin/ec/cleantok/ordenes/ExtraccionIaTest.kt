package ec.cleantok.ordenes

import com.jayway.jsonpath.JsonPath
import com.ninjasquad.springmockk.MockkBean
import ec.cleantok.ordenes.extraccion.ClienteIaMateriales
import ec.cleantok.ordenes.extraccion.EntradaIa
import ec.cleantok.ordenes.extraccion.ExtraccionIaException
import ec.cleantok.ordenes.extraccion.ItemIa
import ec.cleantok.soporte.Pdfs
import ec.cleantok.soporte.PruebaIntegracion
import io.mockk.every
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Respaldo con IA (cliente simulado): combinación, verificación literal y privacidad de lo que se envía. */
class ExtraccionIaTest : PruebaIntegracion() {

    @MockkBean
    lateinit var ia: ClienteIaMateriales

    private fun subir(token: String, est: UUID, pdf: ByteArray): String =
        JsonPath.read(
            mvc.multipart("/api/v1/establecimientos/$est/ordenes") {
                file(MockMultipartFile("archivo", "c.pdf", "application/pdf", pdf)); header("Authorization", token)
            }.andReturn().response.contentAsString,
            "$.id",
        )

    private fun esperar(token: String, id: String): String {
        repeat(150) {
            val r = mvc.get("/api/v1/ordenes/$id") { header("Authorization", token) }.andReturn().response.contentAsString
            if (JsonPath.read<String>(r, "$.estado") != "PROCESANDO") return r
            Thread.sleep(100)
        }
        fail("No terminó")
    }

    @Test
    fun `otro formato - la IA propone, lo inventado queda BAJA y no seleccionado, y no se envían datos personales`() {
        val enviado = slot<EntradaIa>()
        every { ia.disponible } returns true
        every { ia.extraer(capture(enviado)) } returns listOf(
            ItemIa("cloro", "Cloro", "QUIMICO", "L", "", "MATERIALES", "EMPRESA_LIMPIEZA", 1),
            ItemIa("jabón líquido", "Jabón líquido", "QUIMICO", "L", "", "MATERIALES", "ENTIDAD_CONTRATANTE", 1),
            ItemIa("desinfectante premium", "Desinfectante premium", "QUIMICO", "L", "", "MATERIALES", "EMPRESA_LIMPIEZA", 1),
        )
        val emp = fabrica.empresa()
        val est = fabrica.establecimiento(emp)
        val admin = fabrica.token(fabrica.admin(emp))
        val pdf = Pdfs.conTexto(
            "Contrato de limpieza. Responsable: ana.perez@ejemplo.ec, telefono 0991234567, RUC 0999999997001.",
            "Insumos requeridos: cloro, jabón líquido y fundas de basura.",
        )
        val id = subir(admin, est.id, pdf)
        val orden = esperar(admin, id)
        assertEquals("PENDIENTE_REVISION", JsonPath.read(orden, "$.estado"))
        assertEquals("IA", JsonPath.read(orden, "$.metodoExtraccion"))

        val texto = enviado.captured.texto!!
        assertFalse(listOf("ana.perez", "0991234567", "0999999997001").any { it in texto }, "Se enviaron datos personales")
        assertTrue("cloro" in texto)

        val mats = mvc.get("/api/v1/ordenes/$id/materiales") { header("Authorization", admin) }.andReturn().response.contentAsString
        val inventado = JsonPath.read<List<Map<String, Any>>>(mats, "$[0].items[?(@.nombre == 'Desinfectante premium')]").single()
        assertEquals("BAJA", inventado["confianza"])
        assertEquals(false, inventado["seleccionadoPorDefecto"])
        assertTrue((inventado["motivoRevision"] as String).contains("no verificado en el texto"))
        val jabon = JsonPath.read<List<Map<String, Any>>>(mats, "$[0].items[?(@.nombre == 'Jabón líquido')]").single()
        assertEquals("ENTIDAD_CONTRATANTE", jabon["abastece"])
        assertEquals(true, jabon["seleccionadoPorDefecto"])
    }

    @Test
    fun `si la IA falla, la orden queda en ERROR con el aviso y se puede cargar a mano`() {
        every { ia.disponible } returns true
        every { ia.extraer(any()) } throws ExtraccionIaException("No se pudo contactar al servicio de IA")
        val emp = fabrica.empresa()
        val est = fabrica.establecimiento(emp)
        val admin = fabrica.token(fabrica.admin(emp))
        val orden = esperar(admin, subir(admin, est.id, Pdfs.conTexto("Contrato de servicio de limpieza para la sede principal.", "Lista de insumos: cloro y jabón.")))
        assertEquals("ERROR", JsonPath.read(orden, "$.estado"))
        assertTrue(JsonPath.read<List<String>>(orden, "$.avisos").any { "No se pudo contactar" in it })
    }

    @Test
    fun `PDF escaneado sin autorización no se envía a la IA`() {
        every { ia.disponible } returns true
        every { ia.extraer(any()) } returns emptyList()
        val emp = fabrica.empresa()
        val est = fabrica.establecimiento(emp)
        val admin = fabrica.token(fabrica.admin(emp))
        val orden = esperar(admin, subir(admin, est.id, Pdfs.escaneado()))
        assertEquals("ERROR", JsonPath.read(orden, "$.estado"))
        assertTrue(JsonPath.read<List<String>>(orden, "$.avisos").any { "no se autorizó" in it })
        io.mockk.verify(exactly = 0) { ia.extraer(any()) }
    }
}
