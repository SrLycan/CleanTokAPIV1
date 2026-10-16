package ec.cleantok.abastecimiento

import com.jayway.jsonpath.JsonPath
import ec.cleantok.abastecimiento.api.OrdenCompraController
import ec.cleantok.abastecimiento.api.ProveedorController
import ec.cleantok.abastecimiento.api.SolicitudController
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import ec.cleantok.soporte.moverOk
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@VerificaAislamiento(SolicitudController::class, ProveedorController::class, OrdenCompraController::class)
class AbastecimientoTest : PruebaIntegracion() {

    private fun MockMvc.enviar(metodo: String, url: String, token: String, cuerpo: String? = null): ResultActionsDsl {
        val config: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
            header("Authorization", token)
            if (cuerpo != null) {
                contentType = MediaType.APPLICATION_JSON
                content = cuerpo
            }
        }
        return when (metodo) {
            "GET" -> get(url, dsl = config)
            "PUT" -> put(url, dsl = config)
            else -> post(url, dsl = config)
        }
    }

    private fun ResultActionsDsl.json(): String = andReturn().response.contentAsString

    private fun <T> String.leer(ruta: String): T = JsonPath.read(this, ruta)

    /** Empresa con dos sitios en Quito (mismo producto en ambos), un encargado y dos proveedores invitados. */
    private inner class Escenario {
        val emp = fabrica.empresa()
        val admin = fabrica.admin(emp)
        val est1 = fabrica.establecimiento(emp, "A Hospital", lat = "-0.1806", lng = "-78.4678")
        val est2 = fabrica.establecimiento(emp, "B Torre", lat = "-0.2003", lng = "-78.4937")
        val jabon1 = fabrica.producto(est1, "Jabón líquido")
        val jabon2 = fabrica.producto(est2, "Jabón líquido")
        val encargado = fabrica.encargado(emp).also { fabrica.asignar(it, est1); fabrica.asignar(it, est2) }
        val rapido = fabrica.proveedor("Rápido", lat = "-0.1530", lng = "-78.4720")
        val barato = fabrica.proveedor("Barato", retiroLocal = true)
        val vendedor = fabrica.usuarioDe(rapido)
        val repartidor = fabrica.usuarioDe(rapido, Rol.REPARTIDOR)
        val vendedorBarato = fabrica.usuarioDe(barato)
        val tAdmin = fabrica.token(admin)
        val tEnc = fabrica.token(encargado)
        val tRapido = fabrica.token(vendedor)
        val tRep = fabrica.token(repartidor)
        val tBarato = fabrica.token(vendedorBarato)

        init {
            fabrica.vincular(emp, rapido)
            fabrica.vincular(emp, barato)
        }

        /** Solicitud enviada con 10 L para el hospital y 5 L para la torre; ofertas de ambos proveedores. */
        fun solicitudConOfertas(): Pair<String, String> {
            val s = mvc.enviar(
                "POST", "/api/v1/solicitudes", tAdmin,
                """{"enviar":true,"lineas":[{"productoId":"${jabon1.id}","cantidad":10},{"productoId":"${jabon2.id}","cantidad":5}]}""",
            ).andExpect { status { isCreated() } }.json()
            val id: String = s.leer("$.id")
            val item: String = s.leer("$.items[0].id")
            mvc.enviar(
                "PUT", "/api/v1/proveedor/solicitudes/$id/oferta", tRapido,
                """{"modalidad":"ENVIO_DIRECTO","plazoDias":1,"items":[{"itemId":"$item","cantidadDisponible":15,"precioUnitario":2.00}]}""",
            ).andExpect { status { isOk() } }
            mvc.enviar(
                "PUT", "/api/v1/proveedor/solicitudes/$id/oferta", tBarato,
                """{"modalidad":"RETIRO_LOCAL","plazoDias":4,"items":[{"itemId":"$item","cantidadDisponible":8,"precioUnitario":1.50}]}""",
            ).andExpect { status { isOk() } }
            return id to item
        }
    }

    @Test
    fun `de la solicitud consolidada a la recepción en bodega, con rastreo completo`() {
        val x = Escenario()
        val (solicitudId, _) = x.solicitudConOfertas()

        // La empresa ve las dos ofertas con puntaje; la recomendada es la completa aunque sea más cara (el plazo lo fija la fecha requerida).
        mvc.enviar("GET", "/api/v1/solicitudes/$solicitudId", x.tAdmin).andExpect {
            status { isOk() }
            jsonPath("$.codigo") { value("SC-001") }
            jsonPath("$.estado") { value("EN_COTIZACION") }
            jsonPath("$.items", hasSize<Any>(1))
            jsonPath("$.items[0].cantidad") { value(15.0) }
            jsonPath("$.items[0].destinos[*].establecimiento.nombre", contains("A Hospital", "B Torre"))
            jsonPath("$.items[0].cotizaciones[*].proveedor.nombre", contains("Rápido", "Barato"))
            jsonPath("$.items[0].cotizaciones[0].recomendada") { value(true) }
            jsonPath("$.items[0].cotizaciones[0].completo") { value(true) }
            jsonPath("$.items[0].cotizaciones[1].completo") { value(false) }
        }
        // Un proveedor solo ve su propia oferta.
        mvc.enviar("GET", "/api/v1/proveedor/solicitudes/$solicitudId", x.tBarato).andExpect {
            status { isOk() }
            jsonPath("$.miOferta.proveedor.nombre") { value("Barato") }
            jsonPath("$.ofertas") { doesNotExist() }
        }

        val adj = mvc.enviar("POST", "/api/v1/solicitudes/$solicitudId/adjudicar", x.tAdmin, "{}").andExpect {
            status { isOk() }
            jsonPath("$.estado") { value("ADJUDICADA") }
            jsonPath("$.ordenes", hasSize<Any>(1))
            jsonPath("$.ordenes[0].codigo") { value("OC-001") }
            jsonPath("$.ordenes[0].total") { value(30.0) }
            jsonPath("$.ordenes[0].destinos") { value(2) }
        }.json()
        val ordenId: String = adj.leer("$.ordenes[0].id")
        val url = "/api/v1/ordenes-compra/$ordenId"

        // El encargado todavía no puede recibir: el pedido no salió.
        val orden0 = mvc.enviar("GET", url, x.tEnc).andExpect { status { isOk() } }.json()
        val entregaHospital: String = orden0.leer("$.entregas[0].id")
        val entregaTorre: String = orden0.leer("$.entregas[1].id")
        mvc.enviar("POST", "$url/entregas/$entregaHospital/recibir", x.tEnc, "{}").andExpect { status { isConflict() } }

        // El proveedor no puede saltarse pasos.
        mvc.enviar("POST", "$url/estado", x.tRapido, """{"estado":"EMPACADA"}""").andExpect { status { isConflict() } }
        listOf("EN_PREPARACION", "EMPACADA").forEach { e ->
            mvc.enviar("POST", "$url/estado", x.tRapido, """{"estado":"$e"}""").andExpect {
                status { isOk() }
                jsonPath("$.estado") { value(e) }
            }
        }
        mvc.enviar("POST", "$url/repartidor", x.tRapido, """{"repartidorId":"${x.repartidor.id}"}""").andExpect {
            status { isOk() }
            jsonPath("$.repartidor.id") { value(x.repartidor.id.toString()) }
        }
        // El repartidor despacha y ve su ruta; sus pantallas no muestran precios.
        mvc.enviar("POST", "$url/estado", x.tRep, """{"estado":"DESPACHADA"}""").andExpect {
            status { isOk() }
            jsonPath("$.total") { value(0) }
            jsonPath("$.acciones.marcarEntregada", hasSize<Any>(2))
        }
        mvc.enviar("GET", "/api/v1/rutas/mia", x.tRep).andExpect {
            status { isOk() }
            jsonPath("$.paradas", hasSize<Any>(2))
            jsonPath("$.origen.nombre") { value("Local de Rápido") }
        }
        mvc.enviar("POST", "$url/entregas/$entregaHospital/entregar", x.tRep, "{}").andExpect {
            status { isOk() }
            jsonPath("$.estado") { value("EN_TRANSITO") }
        }

        // Recepción completa en el hospital: entra a la bodega y queda en el kardex.
        mvc.enviar("POST", "$url/entregas/$entregaHospital/recibir", x.tEnc, "{}").andExpect {
            status { isOk() }
            jsonPath("$.estado") { value("EN_TRANSITO") }
        }
        mvc.enviar("GET", "/api/v1/productos/${x.jabon1.id}/kardex", x.tEnc).andExpect {
            status { isOk() }
            jsonPath("$.content[0].movimiento.tipo") { value("INGRESO") }
            jsonPath("$.content[0].movimiento.cantidad") { value(10.0) }
            jsonPath("$.content[0].movimiento.nota") { value("Recepción OC-001 · Rápido") }
        }
        // No se recibe dos veces.
        mvc.enviar("POST", "$url/entregas/$entregaHospital/recibir", x.tEnc, "{}").andExpect { status { isConflict() } }

        // La torre recibe 4 de 5: la orden se cierra, con la entrega marcada como incompleta.
        val lineaTorre: String = mvc.enviar("GET", url, x.tEnc).json().leer("$.entregas[1].lineas[0].id")
        mvc.enviar(
            "POST", "$url/entregas/$entregaTorre/recibir", x.tEnc,
            """{"lineas":[{"lineaId":"$lineaTorre","cantidadRecibida":4}],"nota":"Un galón golpeado"}""",
        ).andExpect {
            status { isOk() }
            jsonPath("$.estado") { value("ENTREGADA") }
            jsonPath("$.entregas[1].completa") { value(false) }
            jsonPath("$.entregas[1].lineas[0].cantidadRecibida") { value(4.0) }
            jsonPath("$.pasos[*].hecho", contains(true, true, true, true, true, true, true))
            jsonPath("$.eventos[0].titulo") { value("Entregado") }
        }
        mvc.get("/api/v1/establecimientos/${x.est2.id}/inventario") { header("Authorization", x.tEnc) }.andExpect {
            jsonPath("$.items[0].stockBodega") { value(4.0) }
        }
    }

    @Test
    fun `sugerencias calculan cuánto pedir y avisan si ya está pedido`() {
        val x = Escenario()
        val bodega = fabrica.bodega(x.est1)
        val piso = fabrica.piso(x.est1)
        // 1 L/día en los últimos 14 días y 2 L en stock → cobertura 2 días (BAJO).
        mvc.moverOk(x.tAdmin, "INGRESO", x.jabon1.id, bodega.id, "16", haceDias = 13)
        mvc.moverOk(x.tAdmin, "TRASLADO", x.jabon1.id, piso.id, "16", haceDias = 13)
        (1L..7L).forEach { mvc.moverOk(x.tAdmin, "CONSUMO", x.jabon1.id, piso.id, "2", haceDias = it) }

        mvc.enviar("GET", "/api/v1/abastecimiento/sugerencias", x.tAdmin).andExpect {
            status { isOk() }
            jsonPath("$[0].producto.id") { value(x.jabon1.id.toString()) }
            // 1 L/día × (3 + 14 + 3) − 2 = 18
            jsonPath("$[0].cantidadRecomendada") { value(18) }
            jsonPath("$[0].yaPedido") { doesNotExist() }
        }
        x.solicitudConOfertas()
        mvc.enviar("GET", "/api/v1/abastecimiento/sugerencias", x.tAdmin).andExpect {
            jsonPath("$[0].yaPedido") { value("SC-001") }
        }
    }

    @Test
    fun `un producto agotado se sugiere con su consumo histórico`() {
        val x = Escenario()
        val bodega = fabrica.bodega(x.est2)
        val piso = fabrica.piso(x.est2)
        // Se consumieron 2 L/día del día 30 al 21; desde entonces no hay stock ni consumo.
        mvc.moverOk(x.tAdmin, "INGRESO", x.jabon2.id, bodega.id, "20", haceDias = 31)
        mvc.moverOk(x.tAdmin, "TRASLADO", x.jabon2.id, piso.id, "20", haceDias = 31)
        (21L..30L).forEach { mvc.moverOk(x.tAdmin, "CONSUMO", x.jabon2.id, piso.id, "2", haceDias = it) }

        mvc.enviar("GET", "/api/v1/abastecimiento/sugerencias", x.tAdmin).andExpect {
            status { isOk() }
            jsonPath("$[0].producto.id") { value(x.jabon2.id.toString()) }
            jsonPath("$[0].estado") { value("CRITICO") }
            jsonPath("$[0].motivo") { value("Sin stock") }
            jsonPath("$[0].consumoDiario") { value(2.0) }
            // 2 L/día × 20 días − 0
            jsonPath("$[0].cantidadRecomendada") { value(40) }
        }
    }

    @Test
    fun `un producto con la bodega vacía se sugiere aunque no tenga historial de consumo`() {
        val x = Escenario()
        val bodega = fabrica.bodega(x.est2)
        val piso = fabrica.piso(x.est2)
        // Todo lo que llegó está en el piso y nunca se registró consumo: no hay cobertura que calcular.
        mvc.moverOk(x.tAdmin, "INGRESO", x.jabon2.id, bodega.id, "5.5", haceDias = 2)
        mvc.moverOk(x.tAdmin, "TRASLADO", x.jabon2.id, piso.id, "5.5", haceDias = 2)

        mvc.enviar("GET", "/api/v1/abastecimiento/sugerencias", x.tAdmin).andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].producto.id") { value(x.jabon2.id.toString()) }
            jsonPath("$[0].estado") { value("SIN_DATOS") }
            // Al menos lo que hay repartido en los pisos, redondeado hacia arriba.
            jsonPath("$[0].cantidadRecomendada") { value(6) }
            jsonPath("$[0].motivo") { value("Bodega vacía · quedan 5,5 en pisos · sin historial de consumo, revisa la cantidad") }
        }
    }

    @Test
    fun `reglas de la oferta y de la adjudicación`() {
        val x = Escenario()
        val (solicitudId, item) = x.solicitudConOfertas()
        // Modalidad que el proveedor no ofrece.
        mvc.enviar(
            "PUT", "/api/v1/proveedor/solicitudes/$solicitudId/oferta", x.tRapido,
            """{"modalidad":"RETIRO_BODEGA","plazoDias":1,"items":[{"itemId":"$item","cantidadDisponible":1,"precioUnitario":1}]}""",
        ).andExpect { status { isBadRequest() } }
        // Adjudicar al barato: solo tiene 8 de 15 → cubre el hospital (10) parcialmente con 8, nada para la torre.
        val ofertaBarato = mvc.enviar("GET", "/api/v1/solicitudes/$solicitudId", x.tAdmin).json()
            .leer<List<String>>("$.items[0].cotizaciones[?(@.proveedor.nombre == 'Barato')].ofertaId").first()
        mvc.enviar(
            "POST", "/api/v1/solicitudes/$solicitudId/adjudicar", x.tAdmin,
            """{"asignaciones":[{"itemId":"$item","ofertaId":"$ofertaBarato"}]}""",
        ).andExpect {
            status { isOk() }
            jsonPath("$.ordenes[0].proveedor.nombre") { value("Barato") }
            jsonPath("$.ordenes[0].modalidad") { value("RETIRO_LOCAL") }
            jsonPath("$.ordenes[0].destinos") { value(1) }
            jsonPath("$.ordenes[0].total") { value(12.0) }
        }
        // Ya no recibe ofertas.
        mvc.enviar(
            "PUT", "/api/v1/proveedor/solicitudes/$solicitudId/oferta", x.tRapido,
            """{"modalidad":"ENVIO_DIRECTO","plazoDias":1,"items":[{"itemId":"$item","cantidadDisponible":1,"precioUnitario":1}]}""",
        ).andExpect { status { isConflict() } }
    }

    @Test
    fun `aislamiento entre empresas, proveedores y repartidores`() {
        val x = Escenario()
        val (solicitudId, _) = x.solicitudConOfertas()
        val ordenId: String = mvc.enviar("POST", "/api/v1/solicitudes/$solicitudId/adjudicar", x.tAdmin, "{}").json().leer("$.ordenes[0].id")

        val otraEmpresa = fabrica.empresa()
        val tOtroAdmin = fabrica.token(fabrica.admin(otraEmpresa))
        val otroProv = fabrica.proveedor()
        val tOtroProv = fabrica.token(fabrica.usuarioDe(otroProv))
        val tOtroRep = fabrica.token(fabrica.usuarioDe(otroProv, Rol.REPARTIDOR))
        val tEncAjeno = fabrica.token(fabrica.encargado(x.emp))

        mvc.enviar("GET", "/api/v1/solicitudes/$solicitudId", tOtroAdmin).andExpect { status { isNotFound() } }
        mvc.enviar("GET", "/api/v1/ordenes-compra/$ordenId", tOtroAdmin).andExpect { status { isNotFound() } }
        mvc.enviar("GET", "/api/v1/proveedor/solicitudes/$solicitudId", tOtroProv).andExpect { status { isNotFound() } }
        mvc.enviar("GET", "/api/v1/ordenes-compra/$ordenId", tOtroProv).andExpect { status { isNotFound() } }
        mvc.enviar("GET", "/api/v1/ordenes-compra/$ordenId", x.tBarato).andExpect { status { isNotFound() } }
        mvc.enviar("GET", "/api/v1/ordenes-compra/$ordenId", tOtroRep).andExpect { status { isNotFound() } }
        mvc.enviar("GET", "/api/v1/ordenes-compra/$ordenId", tEncAjeno).andExpect { status { isNotFound() } }
        // Antes de asignarlo, el repartidor del proveedor correcto tampoco la ve.
        mvc.enviar("GET", "/api/v1/ordenes-compra/$ordenId", x.tRep).andExpect { status { isNotFound() } }
        mvc.enviar("GET", "/api/v1/ordenes-compra", tOtroAdmin).andExpect { jsonPath("$", hasSize<Any>(0)) }
        mvc.enviar("GET", "/api/v1/proveedor/solicitudes", tOtroProv).andExpect { jsonPath("$", hasSize<Any>(0)) }
        // Por rol: un proveedor no compra y un encargado no cotiza.
        mvc.enviar("GET", "/api/v1/solicitudes", x.tRapido).andExpect { status { isForbidden() } }
        mvc.enviar("GET", "/api/v1/proveedor/solicitudes", x.tEnc).andExpect { status { isForbidden() } }
    }
}
