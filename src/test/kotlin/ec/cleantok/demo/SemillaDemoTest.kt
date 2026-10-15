package ec.cleantok.demo

import com.jayway.jsonpath.JsonPath
import ec.cleantok.soporte.PruebaIntegracion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ActiveProfiles("demo")
class SemillaDemoTest : PruebaIntegracion() {

    @Autowired
    lateinit var semilla: SemillaDemo

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun token(email: String): String = "Bearer " + JsonPath.read<String>(
        mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"${SemillaDemo.CLAVE}"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
        "$.accessToken",
    )

    private fun establecimientoId(token: String, nombre: String): String {
        val r = mvc.get("/api/v1/establecimientos/mios") { header("Authorization", token) }.andReturn().response.contentAsString
        return JsonPath.read<List<String>>(r, "$[?(@.nombre == '$nombre')].id").single()
    }

    private fun tiposAbiertos(token: String, estId: String): Map<String, List<String>> {
        val r = mvc.get("/api/v1/establecimientos/$estId/alertas?estado=ABIERTA&size=200") { header("Authorization", token) }
            .andReturn().response.contentAsString
        val tipos = JsonPath.read<List<String>>(r, "$.content[*].tipo")
        val productos = JsonPath.read<List<String>>(r, "$.content[*].producto.nombre")
        return tipos.zip(productos).groupBy({ it.first }, { it.second })
    }

    @Test
    fun `la semilla deja los casos de alerta de la demo listos`() {
        val brillo = token("admin@brillototal.ec")
        val andina = token("admin@andina.ec")

        val hospital = tiposAbiertos(brillo, establecimientoId(brillo, "Hospital Básico San Andrés"))
        assertTrue("Alcohol antiséptico" in hospital["SIN_STOCK"].orEmpty(), "SIN_STOCK: $hospital")

        val torre = tiposAbiertos(brillo, establecimientoId(brillo, "Edificio Torre Azul – Oficinas"))
        assertTrue("Papel higiénico jumbo" in torre["COBERTURA_BAJA"].orEmpty(), "COBERTURA_BAJA: $torre")
        assertTrue("Papel higiénico jumbo" !in torre["SIN_STOCK"].orEmpty())

        val planta = tiposAbiertos(andina, establecimientoId(andina, "Planta Textil La Esperanza"))
        assertTrue("Jabón líquido para dispensador" in planta["NIVEL_CRITICO"].orEmpty(), "NIVEL_CRITICO: $planta")

        val municipio = tiposAbiertos(andina, establecimientoId(andina, "Oficinas Municipales Centro"))
        assertTrue("Detergente" in municipio["STOCK_BAJO"].orEmpty(), "STOCK_BAJO: $municipio")

        // Los productos "normales" no quedan en alerta de cobertura.
        val normales = listOf("Cloro líquido 5.5%", "Guantes de nitrilo", "Limpiavidrios")
        listOf(hospital, torre, planta, municipio).forEach { m -> assertTrue(m["COBERTURA_BAJA"].orEmpty().none { it in normales }, "$m") }

        // El encargado ve sus establecimientos y el inventario tiene datos de consumo.
        val enc = token("maria@brillototal.ec")
        val r = mvc.get("/api/v1/establecimientos/mios") { header("Authorization", enc) }.andReturn().response.contentAsString
        assertEquals(3, JsonPath.read<List<Any>>(r, "$").size)
        val inv = mvc.get("/api/v1/establecimientos/${establecimientoId(enc, "Edificio Torre Azul – Oficinas")}/inventario") {
            header("Authorization", enc)
        }.andReturn().response.contentAsString
        assertTrue(JsonPath.read<List<String>>(inv, "$.items[*].estado").count { it == "OK" } >= 5)
    }

    @Test
    fun `los saldos coinciden con el kardex y nunca son negativos`() {
        val descuadres = jdbc.queryForObject(
            """
            select count(*) from stock s
            where s.establecimiento_id in (select e.id from establecimiento e join empresa em on em.id = e.empresa_id where em.ruc like '0992%')
              and s.cantidad <> (
                coalesce((select sum(m.cantidad) from movimiento m where m.producto_id = s.producto_id and m.ubicacion_destino_id = s.ubicacion_id), 0)
              - coalesce((select sum(m.cantidad) from movimiento m where m.producto_id = s.producto_id and m.ubicacion_origen_id = s.ubicacion_id), 0))
            """.trimIndent(),
            Int::class.java,
        )
        assertEquals(0, descuadres)
        // El último saldo del kardex de cada (producto, ubicación) coincide con la tabla de saldos.
        val distintos = jdbc.queryForObject(
            """
            with ultimos as (
              select distinct on (producto_id, ubicacion) producto_id, ubicacion, saldo from (
                select producto_id, ubicacion_origen_id as ubicacion, saldo_resultante_origen as saldo, secuencia from movimiento where ubicacion_origen_id is not null
                union all
                select producto_id, ubicacion_destino_id, saldo_resultante_destino, secuencia from movimiento where ubicacion_destino_id is not null
              ) x order by producto_id, ubicacion, secuencia desc)
            select count(*) from ultimos u join stock s on s.producto_id = u.producto_id and s.ubicacion_id = u.ubicacion
            where s.cantidad <> u.saldo and s.establecimiento_id in (select e.id from establecimiento e join empresa em on em.id = e.empresa_id where em.ruc like '0992%')
            """.trimIndent(),
            Int::class.java,
        )
        assertEquals(0, distintos)
        val dias = jdbc.queryForObject(
            "select count(distinct (ocurrido_en at time zone 'America/Guayaquil')::date) from movimiento m join empresa e on e.id = m.empresa_id where e.ruc like '0992%'",
            Int::class.java,
        )!!
        assertTrue(dias >= 60)
    }

    @Test
    fun `la semilla de abastecimiento deja a cada rol con algo que hacer`() {
        val admin = token("admin@brillototal.ec")
        val ordenes = mvc.get("/api/v1/ordenes-compra") { header("Authorization", admin) }.andReturn().response.contentAsString
        assertEquals(listOf("OC-003", "OC-002", "OC-001"), JsonPath.read<List<String>>(ordenes, "$[*].codigo"))
        assertEquals(listOf("APROBADA", "EN_TRANSITO", "ENTREGADA"), JsonPath.read<List<String>>(ordenes, "$[*].estado"))
        val solicitudes = mvc.get("/api/v1/solicitudes") { header("Authorization", admin) }.andReturn().response.contentAsString
        assertEquals("EN_COTIZACION", JsonPath.read<List<String>>(solicitudes, "$[?(@.codigo == 'SC-003')].estado").single())
        assertEquals(3, JsonPath.read<List<Int>>(solicitudes, "$[?(@.codigo == 'SC-003')].ofertas").single())

        // El proveedor tiene una orden nueva por aceptar.
        val proclean = token("ventas@proclean.ec")
        val oc3 = mvc.get("/api/v1/ordenes-compra") { header("Authorization", proclean) }.andReturn().response.contentAsString
        val oc3Id = JsonPath.read<List<String>>(oc3, "$[?(@.codigo == 'OC-003')].id").single()
        val detalle = mvc.get("/api/v1/ordenes-compra/$oc3Id") { header("Authorization", proclean) }.andReturn().response.contentAsString
        assertEquals(listOf("ACEPTADA", "RECHAZADA"), JsonPath.read<List<String>>(detalle, "$.acciones.siguientes[*].estado"))

        // El repartidor tiene su ruta con 4 paradas y la optimizada no es más larga.
        val ruta = mvc.get("/api/v1/rutas/mia") { header("Authorization", token("reparto@lahigiene.ec")) }.andReturn().response.contentAsString
        assertEquals(4, JsonPath.read<List<Any>>(ruta, "$.paradas").size)
        // Ya despachada (paradas reordenadas), la comparación sigue siendo contra el orden original del pedido.
        assertTrue(JsonPath.read<Double>(ruta, "$.optimizada.km") < JsonPath.read<Double>(ruta, "$.base.km"))

        // María puede confirmar la llegada a sus sitios, y el hospital ya tiene el INGRESO enlazado a la orden.
        val maria = token("maria@brillototal.ec")
        val oc2Id = JsonPath.read<List<String>>(
            mvc.get("/api/v1/ordenes-compra") { header("Authorization", maria) }.andReturn().response.contentAsString,
            "$[?(@.codigo == 'OC-002')].id",
        ).single()
        val oc2 = mvc.get("/api/v1/ordenes-compra/$oc2Id") { header("Authorization", maria) }.andReturn().response.contentAsString
        assertEquals(2, JsonPath.read<List<String>>(oc2, "$.acciones.recibir").size)
        val nota = jdbc.queryForObject(
            "select m.nota from orden_linea l join movimiento m on m.id = l.movimiento_id join orden_compra o on o.id = l.orden_id where o.codigo = 'OC-002' limit 1",
            String::class.java,
        )
        assertEquals("Recepción OC-002 · Distribuidora La Higiene Cía. Ltda.", nota)
    }

    @Test
    fun `es idempotente`() {
        val antes = jdbc.queryForObject("select count(*) from movimiento", Long::class.java)
        semilla.run(DefaultApplicationArguments())
        assertEquals(antes, jdbc.queryForObject("select count(*) from movimiento", Long::class.java))
    }
}
