package ec.cleantok.monitoreo

import com.jayway.jsonpath.JsonPath
import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.monitoreo.api.MonitoreoController
import ec.cleantok.monitoreo.servicio.JobAlertas
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import ec.cleantok.soporte.mover
import ec.cleantok.soporte.moverOk
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@VerificaAislamiento(MonitoreoController::class)
class AlertasTest : PruebaIntegracion() {

    @Autowired
    lateinit var job: JobAlertas

    inner class Escenario(abastece: Abastece = Abastece.EMPRESA_LIMPIEZA, stockMinimo: BigDecimal? = null) {
        val empresa = fabrica.empresa()
        val est = fabrica.establecimiento(empresa)
        val bodega = fabrica.bodega(est)
        val piso = fabrica.piso(est, "Piso 2")
        val producto = fabrica.producto(est, "Papel toalla", abastece = abastece, stockMinimo = stockMinimo)
        val enc = fabrica.token(fabrica.encargado(empresa).also { fabrica.asignar(it, est) })
        val admin = fabrica.token(fabrica.admin(empresa))

        fun alertas(estado: String? = "ABIERTA"): ResultActionsDsl =
            mvc.get("/api/v1/establecimientos/${est.id}/alertas") { header("Authorization", admin); estado?.let { param("estado", it) } }

        fun nivel(pct: Int, clientUuid: UUID = UUID.randomUUID(), ubicacionId: UUID = piso.id): ResultActionsDsl =
            mvc.post("/api/v1/reportes-nivel") {
                header("Authorization", enc)
                contentType = MediaType.APPLICATION_JSON
                content = """{"clientUuid":"$clientUuid","productoId":"${producto.id}","ubicacionId":"$ubicacionId",
                    "nivelPct":$pct,"ocurridoEn":"${OffsetDateTime.now(ZoneOffset.ofHours(-5))}"}"""
            }
    }

    @Test
    fun `SIN_STOCK se abre al vaciar la bodega y se cierra sola al reponer`() {
        val s = Escenario()
        // Recién creado, sin ingresos: no alerta aunque el stock sea 0.
        s.alertas().andExpect { jsonPath("$.content", hasSize<Any>(0)) }

        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "2") }
        mvc.mover(s.enc, "TRASLADO", s.producto.id, s.piso.id, "2").andExpect {
            status { isCreated() }
            jsonPath("$.alertas[0].tipo") { value("SIN_STOCK") }
            jsonPath("$.alertas[0].severidad") { value("ALTA") }
        }
        s.alertas().andExpect {
            jsonPath("$.content", hasSize<Any>(1))
            jsonPath("$.content[0].mensaje", containsString("Lo compra la empresa de limpieza."))
            jsonPath("$.content[0].ubicacion.id") { value(s.bodega.id.toString()) }
        }
        // El QR de la bodega muestra la alerta.
        mvc.get("/api/v1/qr/${s.bodega.qrToken}") { header("Authorization", s.enc) }
            .andExpect { jsonPath("$.alertas[0].tipo") { value("SIN_STOCK") } }

        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "5") }
        s.alertas().andExpect { jsonPath("$.content", hasSize<Any>(0)) }
        s.alertas("ATENDIDA").andExpect { jsonPath("$.content[0].cierre") { value("AUTOMATICO") } }
    }

    @Test
    fun `STOCK_BAJO usa el stock mínimo e indica que lo provee la entidad contratante`() {
        val s = Escenario(Abastece.ENTIDAD_CONTRATANTE, stockMinimo = BigDecimal("5"))
        mvc.mover(s.admin, "INGRESO", s.producto.id, s.bodega.id, "4").andExpect {
            jsonPath("$.alertas[0].tipo") { value("STOCK_BAJO") }
            jsonPath("$.alertas[0].severidad") { value("MEDIA") }
            jsonPath("$.alertas[0].mensaje", containsString("Lo provee la entidad contratante."))
        }
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "2") }
        s.alertas().andExpect { jsonPath("$.content", hasSize<Any>(0)) }
    }

    @Test
    fun `NIVEL_CRITICO - 25 por ciento abre MEDIA, 0 la escala a ALTA y un traslado la cierra`() {
        val s = Escenario()
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "10") }
        s.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "2") }
        s.nivel(50).andExpect { status { isCreated() }; jsonPath("$.alertas", hasSize<Any>(0)) }
        s.nivel(25).andExpect {
            status { isCreated() }
            jsonPath("$.reporte.nivelPct") { value(25) }
            jsonPath("$.reporte.verificacionIa") { value("NO_APLICA") }
            jsonPath("$.alertas[0].tipo") { value("NIVEL_CRITICO") }
            jsonPath("$.alertas[0].severidad") { value("MEDIA") }
        }
        s.nivel(0).andExpect { jsonPath("$.alertas", hasSize<Any>(0)) } // misma alerta, escalada
        s.alertas().andExpect {
            jsonPath("$.content", hasSize<Any>(1))
            jsonPath("$.content[0].severidad") { value("ALTA") }
            jsonPath("$.content[0].ubicacion.nombre") { value("Piso 2") }
        }
        mvc.get("/api/v1/qr/${s.piso.qrToken}") { header("Authorization", s.enc) }
            .andExpect { jsonPath("$.alertas[0].tipo") { value("NIVEL_CRITICO") } }

        s.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "3") }
        s.alertas().andExpect { jsonPath("$.content", hasSize<Any>(0)) }
    }

    @Test
    fun `reporte de nivel - validaciones e idempotencia`() {
        val s = Escenario()
        s.nivel(30).andExpect { status { isBadRequest() } }
        s.nivel(25, ubicacionId = s.bodega.id).andExpect { status { isBadRequest() } }
        val uuid = UUID.randomUUID()
        s.nivel(75, uuid).andExpect { status { isCreated() } }
        s.nivel(75, uuid).andExpect { status { isOk() }; jsonPath("$.duplicado") { value(true) } }
    }

    @Test
    fun `COBERTURA_BAJA según el consumo de 14 días, ALTA con menos de 2 días`() {
        val s = Escenario()
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "20", haceDias = 13) }
        s.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "20", haceDias = 13) }
        // 5 días con 3 L: consumo diario 15/14 ≈ 1,071; quedan 5 L → ≈ 4,7 días (< 7).
        (5L downTo 2L).forEach { d -> s.run { mvc.moverOk(enc, "CONSUMO", producto.id, piso.id, "3", haceDias = d) } }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "3", haceDias = 1).andExpect {
            jsonPath("$.alertas[?(@.tipo == 'COBERTURA_BAJA')].severidad") { value("MEDIA") }
        }
        // Consumir 3 L más: quedan 2 L con consumo ≈ 1,286/día → ≈ 1,6 días → ALTA.
        s.run { mvc.moverOk(enc, "CONSUMO", producto.id, piso.id, "3") }
        s.alertas().andExpect {
            jsonPath("$.content[?(@.tipo == 'COBERTURA_BAJA')].severidad") { value("ALTA") }
            jsonPath("$.content[?(@.tipo == 'COBERTURA_BAJA')].ubicacion") { value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())) }
        }
    }

    @Test
    fun `atender a mano no reabre mientras persista la condición, sí cuando reaparece`() {
        val s = Escenario()
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "2") }
        s.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "2") }
        val id = JsonPath.read<String>(s.alertas().andReturn().response.contentAsString, "$.content[0].id")
        mvc.patch("/api/v1/alertas/$id/atender") {
            header("Authorization", s.enc); contentType = MediaType.APPLICATION_JSON; content = """{"nota":"Pedido enviado"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.estado") { value("ATENDIDA") }
            jsonPath("$.cierre") { value("MANUAL") }
            jsonPath("$.notaAtencion") { value("Pedido enviado") }
            jsonPath("$.atendidaPor.id") { exists() }
        }
        // Sigue sin stock: un consumo en el piso no la reabre.
        s.run { mvc.moverOk(enc, "CONSUMO", producto.id, piso.id, "1") }
        s.alertas().andExpect { jsonPath("$.content", hasSize<Any>(0)) }
        // Repone (condición desaparece) y vuelve a vaciarse: alerta nueva.
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "1") }
        s.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "1") }
        s.alertas().andExpect { jsonPath("$.content", hasSize<Any>(1)) }
    }

    @Test
    fun `el job y la reevaluación detectan condiciones que no vinieron de un movimiento`() {
        val s = Escenario()
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "3") }
        // El admin sube el stock mínimo: editar el producto no evalúa alertas.
        mvc.put("/api/v1/productos/${s.producto.id}") {
            header("Authorization", s.admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Papel toalla","categoria":"QUIMICO","unidad":"L","stockMinimo":10}"""
        }.andExpect { status { isOk() } }
        s.alertas().andExpect { jsonPath("$.content", hasSize<Any>(0)) }
        job.ejecutar()
        s.alertas().andExpect { jsonPath("$.content[0].tipo") { value("STOCK_BAJO") } }
        mvc.post("/api/v1/establecimientos/${s.est.id}/alertas/reevaluar") { header("Authorization", s.admin) }
            .andExpect { status { isOk() }; jsonPath("$", hasSize<Any>(1)) }
    }

    @Test
    fun `lote offline con reportes de nivel`() {
        val s = Escenario()
        val rep = """{"clientUuid":"${UUID.randomUUID()}","productoId":"${s.producto.id}","ubicacionId":"${s.piso.id}","nivelPct":0,
            "ocurridoEn":"${OffsetDateTime.now(ZoneOffset.ofHours(-5)).minusHours(1)}"}"""
        mvc.post("/api/v1/sincronizacion/lote") {
            header("Authorization", s.enc); contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"tipo":"REPORTE_NIVEL","reporteNivel":$rep},{"tipo":"REPORTE_NIVEL","reporteNivel":$rep}]}"""
        }.andExpect {
            jsonPath("$.items[0].resultado") { value("OK") }
            jsonPath("$.items[1].resultado") { value("DUPLICADO") }
        }
        s.alertas().andExpect { jsonPath("$.content[0].tipo") { value("NIVEL_CRITICO") } }
    }

    @Test
    fun `aislamiento - alertas y reportes de otra empresa responden 404`() {
        val a = Escenario()
        a.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "1") }
        a.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "1") }
        val id = JsonPath.read<String>(a.alertas().andReturn().response.contentAsString, "$.content[0].id")
        val b = Escenario()
        mvc.get("/api/v1/establecimientos/${a.est.id}/alertas") { header("Authorization", b.admin) }.andExpect { status { isNotFound() } }
        mvc.patch("/api/v1/alertas/$id/atender") { header("Authorization", b.admin) }.andExpect { status { isNotFound() } }
        mvc.post("/api/v1/establecimientos/${a.est.id}/alertas/reevaluar") { header("Authorization", b.admin) }.andExpect { status { isNotFound() } }
        mvc.post("/api/v1/reportes-nivel") {
            header("Authorization", b.enc); contentType = MediaType.APPLICATION_JSON
            content = """{"clientUuid":"${UUID.randomUUID()}","productoId":"${a.producto.id}","ubicacionId":"${a.piso.id}","nivelPct":0,
                "ocurridoEn":"${OffsetDateTime.now(ZoneOffset.ofHours(-5))}"}"""
        }.andExpect { status { isNotFound() } }
        // El encargado de b no puede reevaluar (solo admin).
        mvc.post("/api/v1/establecimientos/${b.est.id}/alertas/reevaluar") { header("Authorization", b.enc) }.andExpect { status { isForbidden() } }
    }
}
