package ec.cleantok.inventario

import ec.cleantok.inventario.api.InventarioController
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import ec.cleantok.soporte.moverOk
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import java.time.ZoneOffset

@VerificaAislamiento(InventarioController::class)
class InventarioControllerTest : PruebaIntegracion() {

    @Test
    fun `inventario calcula stock por ubicación, consumos, cobertura y estado`() {
        val emp = fabrica.empresa()
        val e = fabrica.establecimiento(emp)
        val bodega = fabrica.bodega(e)
        val p1 = fabrica.piso(e, "Piso 1")
        val p2 = fabrica.piso(e, "Piso 2")
        val t = fabrica.token(fabrica.admin(emp))

        // A: 7 días con consumo de 2 L en los últimos 14 → 14/14 = 1 L/día; quedan 16 L → 16 días: OK.
        val a = fabrica.producto(e, "A Desinfectante")
        mvc.moverOk(t, "INGRESO", a.id, bodega.id, "30", haceDias = 13)
        mvc.moverOk(t, "TRASLADO", a.id, p1.id, "20", haceDias = 13)
        listOf(1L, 2, 3, 4, 5, 6, 10).forEach { mvc.moverOk(t, "CONSUMO", a.id, p1.id, "2", haceDias = it) }

        // B: solo 2 días con consumo → SIN_DATOS.
        val b = fabrica.producto(e, "B Jabón")
        mvc.moverOk(t, "INGRESO", b.id, bodega.id, "5", haceDias = 3)
        mvc.moverOk(t, "TRASLADO", b.id, p2.id, "5", haceDias = 3)
        mvc.moverOk(t, "CONSUMO", b.id, p2.id, "1", haceDias = 2)
        mvc.moverOk(t, "CONSUMO", b.id, p2.id, "1", haceDias = 1)

        // C: se acabó todo → CRITICO.
        val c = fabrica.producto(e, "C Cera")
        mvc.moverOk(t, "INGRESO", c.id, bodega.id, "3", haceDias = 2)
        mvc.moverOk(t, "TRASLADO", c.id, p1.id, "3", haceDias = 2)
        mvc.moverOk(t, "CONSUMO", c.id, p1.id, "3", haceDias = 1)

        // D: bodega por debajo del stock mínimo → BAJO.
        val d = fabrica.producto(e, "D Alcohol", stockMinimo = java.math.BigDecimal("10"))
        mvc.moverOk(t, "INGRESO", d.id, bodega.id, "5")

        mvc.get("/api/v1/establecimientos/${e.id}/inventario") { header("Authorization", t) }.andExpect {
            status { isOk() }
            jsonPath("$.diasCoberturaMinimo") { value(7) }
            jsonPath("$.items[*].producto.nombre", contains("A Desinfectante", "B Jabón", "C Cera", "D Alcohol"))
            jsonPath("$.items[0].stockBodega") { value(10.0) }
            jsonPath("$.items[0].stockPisos", hasSize<Any>(2))
            jsonPath("$.items[0].stockPisos[0].ubicacion.nombre") { value("Piso 1") }
            jsonPath("$.items[0].stockPisos[0].cantidad") { value(6.0) }
            jsonPath("$.items[0].stockTotal") { value(16.0) }
            jsonPath("$.items[0].consumo7Dias") { value(12.0) }
            jsonPath("$.items[0].consumo30Dias") { value(14.0) }
            jsonPath("$.items[0].consumoDiarioPromedio") { value(1.0) }
            jsonPath("$.items[0].diasCobertura") { value(16.0) }
            jsonPath("$.items[0].estado") { value("OK") }
            jsonPath("$.items[1].consumoDiarioPromedio") { doesNotExist() }
            jsonPath("$.items[1].estado") { value("SIN_DATOS") }
            jsonPath("$.items[2].stockTotal") { value(0.0) }
            jsonPath("$.items[2].estado") { value("CRITICO") }
            jsonPath("$.items[3].estado") { value("BAJO") }
        }
    }

    @Test
    fun `kardex en orden de registro, filtrado por ubicación y por fecha`() {
        val emp = fabrica.empresa()
        val e = fabrica.establecimiento(emp)
        val bodega = fabrica.bodega(e)
        val piso = fabrica.piso(e, "Piso 1")
        val p = fabrica.producto(e, "Detergente")
        val enc = fabrica.encargado(emp).also { fabrica.asignar(it, e) }
        val t = fabrica.token(enc)
        mvc.moverOk(fabrica.token(fabrica.admin(emp)), "INGRESO", p.id, bodega.id, "10", haceDias = 5)
        mvc.moverOk(t, "TRASLADO", p.id, piso.id, "4", haceDias = 4)
        mvc.moverOk(t, "CONSUMO", p.id, piso.id, "1.5", haceDias = 2)

        mvc.get("/api/v1/productos/${p.id}/kardex") { header("Authorization", t) }.andExpect {
            status { isOk() }
            jsonPath("$.content[*].movimiento.tipo", contains("CONSUMO", "TRASLADO", "INGRESO"))
            jsonPath("$.content[0].saldo") { doesNotExist() }
            jsonPath("$.page.totalElements") { value(3) }
        }
        mvc.get("/api/v1/productos/${p.id}/kardex?ubicacionId=${piso.id}") { header("Authorization", t) }.andExpect {
            jsonPath("$.content", hasSize<Any>(2))
            jsonPath("$.content[0].salida") { value(1.5) }
            jsonPath("$.content[0].saldo") { value(2.5) }
            jsonPath("$.content[1].entrada") { value(4.0) }
            jsonPath("$.content[1].saldo") { value(4.0) }
        }
        mvc.get("/api/v1/productos/${p.id}/kardex?ubicacionId=${bodega.id}") { header("Authorization", t) }.andExpect {
            jsonPath("$.content[0].salida") { value(4.0) }
            jsonPath("$.content[0].saldo") { value(6.0) }
        }
        val desde = OffsetDateTime.now(ZoneOffset.ofHours(-5)).minusDays(3).toString()
        mvc.get("/api/v1/productos/${p.id}/kardex") { header("Authorization", t); param("desde", desde) }.andExpect {
            jsonPath("$.content[*].movimiento.tipo", contains("CONSUMO"))
        }
        mvc.get("/api/v1/productos/${p.id}/kardex?size=1&page=1") { header("Authorization", t) }.andExpect {
            jsonPath("$.content[0].movimiento.tipo") { value("TRASLADO") }
        }
    }

    @Test
    fun `aislamiento - inventario y kardex de otra empresa responden 404`() {
        val empresaAjena = fabrica.empresa()
        val ajeno = fabrica.establecimiento(empresaAjena)
        val p = fabrica.producto(ajeno)
        val emp = fabrica.empresa()
        listOf(fabrica.admin(emp), fabrica.encargado(emp)).map { fabrica.token(it) }.forEach { t ->
            mvc.get("/api/v1/establecimientos/${ajeno.id}/inventario") { header("Authorization", t) }.andExpect { status { isNotFound() } }
            mvc.get("/api/v1/productos/${p.id}/kardex") { header("Authorization", t) }.andExpect { status { isNotFound() } }
        }
        // Encargado de la misma empresa pero no asignado: tampoco.
        val enc = fabrica.encargado(empresaAjena)
        mvc.get("/api/v1/establecimientos/${ajeno.id}/inventario") { header("Authorization", fabrica.token(enc)) }
            .andExpect { status { isNotFound() } }
    }
}
