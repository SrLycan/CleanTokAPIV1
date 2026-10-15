package ec.cleantok.ordenes

import com.jayway.jsonpath.JsonPath
import ec.cleantok.ordenes.api.OrdenController
import ec.cleantok.soporte.Pdfs
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@VerificaAislamiento(OrdenController::class)
class OrdenControllerTest : PruebaIntegracion() {

    inner class Escenario {
        val empresa = fabrica.empresa()
        val est = fabrica.establecimiento(empresa)
        val admin = fabrica.token(fabrica.admin(empresa))
    }

    private fun subir(token: String, estId: UUID, bytes: ByteArray, tipo: String = "application/pdf"): ResultActionsDsl =
        mvc.multipart("/api/v1/establecimientos/$estId/ordenes") {
            file(MockMultipartFile("archivo", "orden.pdf", tipo, bytes))
            header("Authorization", token)
        }

    /** Espera a que termine el procesamiento en segundo plano. */
    private fun esperar(token: String, id: String): String {
        repeat(150) {
            val r = mvc.get("/api/v1/ordenes/$id") { header("Authorization", token) }.andReturn().response.contentAsString
            if (JsonPath.read<String>(r, "$.estado") != "PROCESANDO") return r
            Thread.sleep(100)
        }
        fail("La orden $id no terminó de procesarse")
    }

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    @Test
    fun `flujo completo con la orden B - subir, revisar, editar, fusionar, confirmar e idempotencia`() {
        val pdf = Pdfs.orden("orden-b-cnig-2019.pdf")
        assumeTrue(pdf != null, "Falta la orden B")
        val s = Escenario()
        val subida = subir(s.admin, s.est.id, pdf!!).andExpect {
            status { isAccepted() }
            header { exists("Location") }
            jsonPath("$.estado") { value("PROCESANDO") }
        }.andReturn().response.contentAsString
        val ordenId = id(subida)

        val orden = esperar(s.admin, ordenId)
        assertEquals("PENDIENTE_REVISION", JsonPath.read(orden, "$.estado"))
        assertEquals("CE-20190001477721", JsonPath.read(orden, "$.numeroOrden"))
        assertEquals("SERCOP", JsonPath.read(orden, "$.formatoDetectado"))
        assertEquals("PARSER_SERCOP", JsonPath.read(orden, "$.metodoExtraccion"))
        assertEquals(33, JsonPath.read<Int>(orden, "$.resumen.seleccionados")) // 29 materiales + 4 de la entidad

        val grupos = mvc.get("/api/v1/ordenes/$ordenId/materiales") { header("Authorization", s.admin) }
            .andExpect { jsonPath("$[0].seccion") { value("MATERIALES") }; jsonPath("$[0].items", hasSize<Any>(29)) }
            .andReturn().response.contentAsString
        val idInsecticida = JsonPath.read<List<String>>(grupos, "$[0].items[?(@.nombre == 'Insecticida')].id").single()
        val idHipoclorito = JsonPath.read<List<String>>(grupos, "$[0].items[?(@.nombre == 'Hipoclorito de sodio al 10%')].id").single()
        val idAtomizador = JsonPath.read<List<String>>(grupos, "$[0].items[?(@.nombre == 'Atomizador')].id").single()
        val idEscoba1 = JsonPath.read<List<String>>(grupos, "$[0].items[?(@.nombre == 'Escoba cerda suave')].id").single()
        val idEscoba2 = JsonPath.read<List<String>>(grupos, "$[0].items[?(@.nombre == 'Escoba de coco')].id").single()
        assertEquals(listOf(5), JsonPath.read<List<Int>>(grupos, "$[0].items[?(@.nombre == 'Insecticida')].pagina"))

        // Editar: cambia unidad → EDITADO. Descartar el atomizador.
        mvc.patch("/api/v1/materiales/$idHipoclorito") {
            header("Authorization", s.admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Hipoclorito concentrado","unidad":"GALON"}"""
        }.andExpect { status { isOk() }; jsonPath("$.estado") { value("EDITADO") }; jsonPath("$.textoOriginal") { value("Hipoclorito de sodio al 10%") } }
        mvc.patch("/api/v1/materiales/$idAtomizador") {
            header("Authorization", s.admin); contentType = MediaType.APPLICATION_JSON; content = """{"estado":"DESCARTADO"}"""
        }.andExpect { jsonPath("$.seleccionado") { value(false) } }
        // Fusionar las dos escobas en una.
        mvc.post("/api/v1/ordenes/$ordenId/materiales/fusionar") {
            header("Authorization", s.admin); contentType = MediaType.APPLICATION_JSON
            content = """{"materialIds":["$idEscoba1","$idEscoba2"],"nombre":"Escoba"}"""
        }.andExpect { status { isOk() } }
        // Agregar uno manual.
        mvc.post("/api/v1/ordenes/$ordenId/materiales") {
            header("Authorization", s.admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Cera para pisos","categoria":"QUIMICO","unidad":"L"}"""
        }.andExpect { status { isCreated() }; jsonPath("$.seccion") { value("MANUAL") }; jsonPath("$.estado") { value("ACEPTADO") } }

        // 33 − atomizador − una escoba fusionada + manual = 32 productos.
        mvc.post("/api/v1/ordenes/$ordenId/confirmar") { header("Authorization", s.admin) }.andExpect {
            status { isOk() }
            jsonPath("$.estado") { value("CONFIRMADA") }
            jsonPath("$.yaConfirmada") { value(false) }
            jsonPath("$.creados") { value(32) }
            jsonPath("$.productos[?(@.nombre == 'Hipoclorito concentrado')].unidad") { value("GALON") }
            jsonPath("$.productos[?(@.nombre == 'Jabón')].abastece") { value("ENTIDAD_CONTRATANTE") }
            jsonPath("$.productos[?(@.nombre == 'Escoba')]") { exists() }
            jsonPath("$.productos[?(@.nombre == 'Insecticida')].origen") { value("PDF") }
        }
        // Idempotente.
        mvc.post("/api/v1/ordenes/$ordenId/confirmar") { header("Authorization", s.admin) }.andExpect {
            jsonPath("$.yaConfirmada") { value(true) }
            jsonPath("$.productos", hasSize<Any>(32))
        }
        // Productos con stock 0 en el inventario; las herramientas no se crearon.
        mvc.get("/api/v1/establecimientos/${s.est.id}/inventario") { header("Authorization", s.admin) }.andExpect {
            jsonPath("$.items", hasSize<Any>(32))
            jsonPath("$.items[0].stockTotal") { value(0.0) }
        }
        // Tras confirmar, la revisión queda cerrada.
        mvc.patch("/api/v1/materiales/$idInsecticida") {
            header("Authorization", s.admin); contentType = MediaType.APPLICATION_JSON; content = """{"estado":"DESCARTADO"}"""
        }.andExpect { status { isConflict() } }

        // Mismo PDF otra vez: PDF_DUPLICADO con el id de la orden existente.
        subir(s.admin, s.est.id, pdf).andExpect {
            status { isConflict() }
            jsonPath("$.codigo") { value("PDF_DUPLICADO") }
            jsonPath("$.ordenId") { value(ordenId) }
        }
        // El texto extraído solo lo ve el admin.
        mvc.get("/api/v1/ordenes/$ordenId/texto") { header("Authorization", s.admin) }.andExpect { status { isOk() } }
    }

    @Test
    fun `rechaza archivos que no son PDF y PDFs cifrados`() {
        val s = Escenario()
        subir(s.admin, s.est.id, "hola".toByteArray(), "text/plain").andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("PDF_INVALIDO") }
        }
        subir(s.admin, s.est.id, "no es un pdf, aunque diga que sí".toByteArray()).andExpect { jsonPath("$.codigo") { value("PDF_INVALIDO") } }
        subir(s.admin, s.est.id, Pdfs.cifrado()).andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("PDF_INVALIDO") }
        }
    }

    @Test
    fun `PDF sin capa de texto - queda en ERROR con aviso y se puede cargar a mano`() {
        val s = Escenario()
        val id = id(subir(s.admin, s.est.id, Pdfs.escaneado()).andReturn().response.contentAsString)
        val orden = esperar(s.admin, id)
        assertEquals("ERROR", JsonPath.read(orden, "$.estado"))
        assertEquals(false, JsonPath.read(orden, "$.tieneCapaTexto"))
        assertTrue(JsonPath.read<List<String>>(orden, "$.avisos").any { "capa de texto" in it })

        mvc.post("/api/v1/ordenes/$id/materiales") {
            header("Authorization", s.admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Cloro","categoria":"QUIMICO","unidad":"L","abastece":"ENTIDAD_CONTRATANTE"}"""
        }.andExpect { status { isCreated() } }
        mvc.get("/api/v1/ordenes/$id") { header("Authorization", s.admin) }.andExpect {
            jsonPath("$.estado") { value("PENDIENTE_REVISION") }
            jsonPath("$.metodoExtraccion") { value("MANUAL") }
        }
        mvc.post("/api/v1/ordenes/$id/confirmar") { header("Authorization", s.admin) }.andExpect { jsonPath("$.creados") { value(1) } }
    }

    @Test
    fun `PDF de otro formato sin IA - ERROR con aviso, y reintentar con IA deshabilitada es EXTRACCION_FALLIDA`() {
        val s = Escenario()
        val pdf = Pdfs.conTexto("Contrato de servicio de limpieza para la sede principal.", "Insumos requeridos: cloro, jabón líquido y fundas de basura.")
        val id = id(subir(s.admin, s.est.id, pdf).andReturn().response.contentAsString)
        val orden = esperar(s.admin, id)
        assertEquals("ERROR", JsonPath.read(orden, "$.estado"))
        assertEquals("DESCONOCIDO", JsonPath.read(orden, "$.formatoDetectado"))
        assertTrue(JsonPath.read<List<String>>(orden, "$.avisos").any { "deshabilitada" in it })

        mvc.post("/api/v1/ordenes/$id/reintentar-ia") { header("Authorization", s.admin) }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.codigo") { value("EXTRACCION_FALLIDA") }
        }
    }

    @Test
    fun `aislamiento y roles - otra empresa recibe 404 y el encargado 403`() {
        val a = Escenario()
        val id = id(subir(a.admin, a.est.id, Pdfs.conTexto("Lista de insumos: cloro")).andReturn().response.contentAsString)
        esperar(a.admin, id)
        val b = Escenario()
        listOf(
            mvc.get("/api/v1/ordenes/$id") { header("Authorization", b.admin) },
            mvc.get("/api/v1/ordenes/$id/materiales") { header("Authorization", b.admin) },
            mvc.get("/api/v1/ordenes/$id/texto") { header("Authorization", b.admin) },
            mvc.get("/api/v1/ordenes/$id/pdf") { header("Authorization", b.admin) },
            mvc.get("/api/v1/establecimientos/${a.est.id}/ordenes") { header("Authorization", b.admin) },
            mvc.post("/api/v1/ordenes/$id/confirmar") { header("Authorization", b.admin) },
            mvc.post("/api/v1/ordenes/$id/reintentar-ia") { header("Authorization", b.admin) },
            mvc.post("/api/v1/ordenes/$id/materiales/fusionar") {
                header("Authorization", b.admin); contentType = MediaType.APPLICATION_JSON
                content = """{"materialIds":["${UUID.randomUUID()}","${UUID.randomUUID()}"]}"""
            },
            mvc.post("/api/v1/ordenes/$id/materiales") {
                header("Authorization", b.admin); contentType = MediaType.APPLICATION_JSON
                content = """{"nombre":"X","categoria":"OTRO","unidad":"UNIDAD"}"""
            },
            mvc.patch("/api/v1/materiales/${UUID.randomUUID()}") {
                header("Authorization", b.admin); contentType = MediaType.APPLICATION_JSON; content = """{"estado":"ACEPTADO"}"""
            },
            subir(b.admin, a.est.id, Pdfs.conTexto("otro")),
        ).forEach { it.andExpect { status { isNotFound() } } }

        val enc = fabrica.token(fabrica.encargado(a.empresa).also { fabrica.asignar(it, a.est) })
        mvc.get("/api/v1/ordenes/$id") { header("Authorization", enc) }.andExpect { status { isForbidden() } }
        subir(enc, a.est.id, Pdfs.conTexto("otro")).andExpect { status { isForbidden() } }
    }
}
