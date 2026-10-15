package ec.cleantok.establecimiento

import ec.cleantok.establecimiento.api.QrController
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get

@VerificaAislamiento(QrController::class)
class QrControllerTest : PruebaIntegracion() {

    @Test
    fun `el encargado asignado resuelve el QR de un piso`() {
        val emp = fabrica.empresa()
        val e = fabrica.establecimiento(emp, nombre = "Torre Norte")
        val piso = fabrica.piso(e, "Piso 3")
        val enc = fabrica.encargado(emp)
        fabrica.asignar(enc, e)

        mvc.get("/api/v1/qr/${piso.qrToken}") { header("Authorization", fabrica.token(enc)) }.andExpect {
            status { isOk() }
            jsonPath("$.ubicacion.id") { value(piso.id.toString()) }
            jsonPath("$.ubicacion.nombre") { value("Piso 3") }
            jsonPath("$.ubicacion.tipo") { value("PISO") }
            jsonPath("$.establecimiento.nombre") { value("Torre Norte") }
            jsonPath("$.bodega.id") { value(fabrica.bodega(e).id.toString()) }
        }
    }

    @Test
    fun `encargado no asignado recibe QR_NO_ASIGNADO`() {
        val emp = fabrica.empresa()
        val piso = fabrica.piso(fabrica.establecimiento(emp))
        val enc = fabrica.encargado(emp)
        mvc.get("/api/v1/qr/${piso.qrToken}") { header("Authorization", fabrica.token(enc)) }.andExpect {
            status { isForbidden() }
            jsonPath("$.codigo") { value("QR_NO_ASIGNADO") }
        }
    }

    @Test
    fun `token inexistente, ubicación inactiva o establecimiento inactivo dan QR_INVALIDO`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val inactiva = fabrica.piso(fabrica.establecimiento(emp), activo = false)
        val deEstInactivo = fabrica.piso(fabrica.establecimiento(emp, activo = false))
        listOf("no-existe-este-token-1234567", inactiva.qrToken, deEstInactivo.qrToken).forEach { token ->
            mvc.get("/api/v1/qr/$token") { header("Authorization", admin) }.andExpect {
                status { isNotFound() }
                jsonPath("$.codigo") { value("QR_INVALIDO") }
            }
        }
    }

    @Test
    fun `aislamiento - el QR de otra empresa es QR_INVALIDO (no revela que existe)`() {
        val piso = fabrica.piso(fabrica.establecimiento(fabrica.empresa()))
        val otra = fabrica.empresa()
        listOf(fabrica.admin(otra), fabrica.encargado(otra)).forEach { u ->
            mvc.get("/api/v1/qr/${piso.qrToken}") { header("Authorization", fabrica.token(u)) }.andExpect {
                status { isNotFound() }
                jsonPath("$.codigo") { value("QR_INVALIDO") }
            }
        }
    }

    @Test
    fun `la URL pública del QR es una página sin datos y no requiere sesión`() {
        val piso = fabrica.piso(fabrica.establecimiento(fabrica.empresa(), nombre = "Secreto S.A."))
        mvc.get("/q/${piso.qrToken}").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
            content { string(containsString("CleanTok")) }
            content { string(not(containsString("Secreto"))) }
        }
        // Sin configuración de Android no se publica assetlinks.
        mvc.get("/.well-known/assetlinks.json").andExpect { status { isNotFound() } }
    }

    @Test
    fun `sin sesión el endpoint del QR responde 401`() {
        mvc.get("/api/v1/qr/cualquier-token-de-22-caracteres").andExpect { status { isUnauthorized() } }
    }
}
