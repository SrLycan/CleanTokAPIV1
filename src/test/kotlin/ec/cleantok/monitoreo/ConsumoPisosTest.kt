package ec.cleantok.monitoreo

import com.jayway.jsonpath.JsonPath
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import ec.cleantok.soporte.mover
import ec.cleantok.soporte.moverOk
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

/** Administrador de contrato: consumos atípicos por piso y suficiencia de insumos, solo en sus sedes y sin modificar nada. */
@VerificaAislamiento(ConsumoPisosController::class)
class ConsumoPisosTest : PruebaIntegracion() {

    @Test
    fun `detecta el consumo atipico y el piso con insumos insuficientes`() {
        val emp = fabrica.empresa()
        val tAdmin = fabrica.token(fabrica.admin(emp))
        val est = fabrica.establecimiento(emp)
        val otra = fabrica.establecimiento(emp)
        val bodega = fabrica.bodega(est)
        val piso1 = fabrica.piso(est, "Piso 1")
        val piso2 = fabrica.piso(est, "Piso 2")
        val jabon = fabrica.producto(est, "Jabón líquido")
        val papel = fabrica.producto(est, "Papel higiénico")

        // Piso 1: 1 L diario y, ayer, 6 L (atípico). Queda suficiente.
        mvc.moverOk(tAdmin, "INGRESO", jabon.id, bodega.id, "100", haceDias = 31)
        mvc.moverOk(tAdmin, "TRASLADO", jabon.id, piso1.id, "60", haceDias = 30)
        (28L downTo 2L).forEach { mvc.moverOk(tAdmin, "CONSUMO", jabon.id, piso1.id, "1", haceDias = it) }
        mvc.moverOk(tAdmin, "CONSUMO", jabon.id, piso1.id, "6", haceDias = 1)
        // Piso 2: 1 rollo diario y solo queda 1 (alcanza 1 día < 2): insuficiente.
        mvc.moverOk(tAdmin, "INGRESO", papel.id, bodega.id, "40", haceDias = 31)
        mvc.moverOk(tAdmin, "TRASLADO", papel.id, piso2.id, "30", haceDias = 30)
        (29L downTo 1L).forEach { mvc.moverOk(tAdmin, "CONSUMO", papel.id, piso2.id, "1", haceDias = it) }

        val contrato = fabrica.usuario(emp, Rol.ADMIN_CONTRATO).also { fabrica.asignar(it, est) }
        val tContrato = fabrica.token(contrato)

        val r = mvc.get("/api/v1/establecimientos/${est.id}/consumo-pisos") { header("Authorization", tContrato) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        fun <T> leer(ruta: String): T = JsonPath.read(r, ruta)
        assertEquals(2, leer<Int>("$.resumen.pisos"))
        assertEquals(listOf(true), leer<List<Boolean>>("$.pisos[?(@.ubicacion.id == '${piso1.id}')].productos[0].anomalo"))
        assertEquals(listOf(1), leer<List<List<Any>>>("$.pisos[?(@.ubicacion.id == '${piso1.id}')].productos[0].diasAtipicos").map { it.size })
        assertEquals(listOf("SUFICIENTE"), leer<List<String>>("$.pisos[?(@.ubicacion.id == '${piso1.id}')].productos[0].suficiencia"))
        assertEquals(listOf("BAJO"), leer<List<String>>("$.pisos[?(@.ubicacion.id == '${piso2.id}')].productos[0].suficiencia"))
        assertEquals(1, leer<Int>("$.resumen.insuficientes"))
        assertEquals(30, leer<List<Any>>("$.pisos[0].productos[0].serie").size)

        // Aislamiento: otra sede de la misma empresa no existe para él; tampoco para otra empresa.
        mvc.get("/api/v1/establecimientos/${otra.id}/consumo-pisos") { header("Authorization", tContrato) }.andExpect { status { isNotFound() } }
        val ajeno = fabrica.token(fabrica.admin(fabrica.empresa()))
        mvc.get("/api/v1/establecimientos/${est.id}/consumo-pisos") { header("Authorization", ajeno) }.andExpect { status { isNotFound() } }
        // Solo lectura: ve inventario y alertas, pero no registra movimientos; el encargado no ve este análisis.
        mvc.get("/api/v1/establecimientos/${est.id}/inventario") { header("Authorization", tContrato) }.andExpect { status { isOk() } }
        mvc.get("/api/v1/establecimientos/${est.id}/alertas") { header("Authorization", tContrato) }.andExpect { status { isOk() } }
        mvc.mover(tContrato, "CONSUMO", jabon.id, piso1.id, "1").andExpect { status { isForbidden() } }
        val tEnc = fabrica.token(fabrica.encargado(emp).also { fabrica.asignar(it, est) })
        mvc.get("/api/v1/establecimientos/${est.id}/consumo-pisos") { header("Authorization", tEnc) }.andExpect { status { isForbidden() } }
    }
}
