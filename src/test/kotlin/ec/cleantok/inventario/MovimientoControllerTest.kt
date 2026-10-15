package ec.cleantok.inventario

import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.inventario.api.MovimientoController
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import ec.cleantok.soporte.jsonMovimiento
import ec.cleantok.soporte.mover
import ec.cleantok.soporte.moverOk
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@VerificaAislamiento(MovimientoController::class)
class MovimientoControllerTest : PruebaIntegracion() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    /** Escenario base: empresa, establecimiento con bodega y un piso, encargado asignado y un producto en litros. */
    inner class Escenario(unidad: Unidad = Unidad.L) {
        val empresa = fabrica.empresa()
        val est = fabrica.establecimiento(empresa)
        val bodega = fabrica.bodega(est)
        val piso = fabrica.piso(est, "Piso 3")
        val producto = fabrica.producto(est, "Cloro líquido", unidad)
        val encargado = fabrica.encargado(empresa).also { fabrica.asignar(it, est) }
        val enc = fabrica.token(encargado)
        val admin = fabrica.token(fabrica.admin(empresa))
    }

    @Test
    fun `flujo bodega - piso - consumo - devolución con saldos correctos`() {
        val s = Escenario()
        mvc.mover(s.admin, "INGRESO", s.producto.id, s.bodega.id, "10").andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.tipo") { value("INGRESO") }
            jsonPath("$.movimiento.origen") { doesNotExist() }
            jsonPath("$.movimiento.destino.id") { value(s.bodega.id.toString()) }
            jsonPath("$.saldos[0].cantidad") { value(10.0) }
            jsonPath("$.duplicado") { value(false) }
        }
        mvc.mover(s.enc, "TRASLADO", s.producto.id, s.piso.id, "4").andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.origen.id") { value(s.bodega.id.toString()) }
            jsonPath("$.movimiento.destino.id") { value(s.piso.id.toString()) }
            jsonPath("$.movimiento.saldoResultanteOrigen") { value(6.0) }
            jsonPath("$.movimiento.saldoResultanteDestino") { value(4.0) }
        }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "3.5").andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.destino") { doesNotExist() }
            jsonPath("$.movimiento.saldoResultanteOrigen") { value(0.5) }
        }
        mvc.mover(s.enc, "DEVOLUCION", s.producto.id, s.piso.id, "0.5").andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.saldoResultanteOrigen") { value(0.0) }
            jsonPath("$.movimiento.saldoResultanteDestino") { value(6.5) }
        }
        // El QR del piso muestra el stock del piso y de la bodega.
        mvc.get("/api/v1/qr/${s.piso.qrToken}") { header("Authorization", s.enc) }.andExpect {
            status { isOk() }
            jsonPath("$.productos[0].id") { value(s.producto.id.toString()) }
            jsonPath("$.productos[0].stockUbicacion") { value(0.0) }
            jsonPath("$.productos[0].stockBodega") { value(6.5) }
            jsonPath("$.productos[0].unidadesCompatibles", hasItems("L", "ML", "GALON"))
        }
    }

    @Test
    fun `nunca stock negativo - STOCK_INSUFICIENTE trae lo disponible`() {
        val s = Escenario()
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "2") }
        mvc.mover(s.enc, "TRASLADO", s.producto.id, s.piso.id, "2.5").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.codigo") { value("STOCK_INSUFICIENTE") }
            jsonPath("$.disponible") { value(2.0) }
            jsonPath("$.unidad") { value("L") }
            jsonPath("$.ubicacionId") { value(s.bodega.id.toString()) }
        }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "0.001").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.disponible") { value(0.0) }
        }
        mvc.mover(s.admin, "AJUSTE", s.producto.id, s.bodega.id, "3", motivo = "Conteo físico", sentido = "SALIDA").andExpect {
            jsonPath("$.codigo") { value("STOCK_INSUFICIENTE") }
        }
    }

    @Test
    fun `conversión de unidades dentro de la misma magnitud y UNIDAD_INCOMPATIBLE`() {
        val s = Escenario()
        mvc.mover(s.admin, "INGRESO", s.producto.id, s.bodega.id, "2", unidad = "GALON").andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.cantidad") { value(7.57) }
            jsonPath("$.movimiento.unidad") { value("L") }
            jsonPath("$.movimiento.cantidadOriginal") { value(2.0) }
            jsonPath("$.movimiento.unidadOriginal") { value("GALON") }
        }
        mvc.mover(s.enc, "TRASLADO", s.producto.id, s.piso.id, "500", unidad = "ML").andExpect {
            jsonPath("$.movimiento.cantidad") { value(0.5) }
            jsonPath("$.movimiento.saldoResultanteOrigen") { value(7.07) }
        }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "1", unidad = "KG").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.codigo") { value("UNIDAD_INCOMPATIBLE") }
            jsonPath("$.unidadesPermitidas", hasItems("L", "ML", "GALON"))
        }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "0.4", unidad = "ML").andExpect {
            status { isBadRequest() } // 0,4 ml = 0,0004 L → se redondea a 0
        }
    }

    @Test
    fun `fracciones de unidades discretas y máximo 3 decimales`() {
        val s = Escenario(Unidad.ROLLO)
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "12") }
        s.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "2") }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "0.5").andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.saldoResultanteOrigen") { value(1.5) }
        }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "0.1234").andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("VALIDACION") }
        }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "-1").andExpect { status { isBadRequest() } }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "0.5", unidad = "L").andExpect {
            jsonPath("$.codigo") { value("UNIDAD_INCOMPATIBLE") }
        }
    }

    @Test
    fun `idempotencia - el mismo clientUuid no se aplica dos veces`() {
        val s = Escenario()
        val uuid = UUID.randomUUID()
        val primero = mvc.mover(s.admin, "INGRESO", s.producto.id, s.bodega.id, "5", clientUuid = uuid)
            .andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val id = com.jayway.jsonpath.JsonPath.read<String>(primero, "$.movimiento.id")
        repeat(2) {
            mvc.mover(s.admin, "INGRESO", s.producto.id, s.bodega.id, "5", clientUuid = uuid).andExpect {
                status { isOk() }
                jsonPath("$.duplicado") { value(true) }
                jsonPath("$.movimiento.id") { value(id) }
                jsonPath("$.saldos[0].cantidad") { value(5.0) }
            }
        }
        val saldo = jdbc.queryForObject(
            "select cantidad from stock where producto_id = ? and ubicacion_id = ?", java.math.BigDecimal::class.java, s.producto.id, s.bodega.id,
        )
        kotlin.test.assertEquals(0, saldo!!.compareTo(java.math.BigDecimal("5")))
    }

    @Test
    fun `cada tipo se registra en el QR que le corresponde`() {
        val s = Escenario()
        mvc.mover(s.admin, "INGRESO", s.producto.id, s.piso.id, "1").andExpect { status { isBadRequest() } }
        mvc.mover(s.enc, "TRASLADO", s.producto.id, s.bodega.id, "1").andExpect { status { isBadRequest() } }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.bodega.id, "1").andExpect { status { isBadRequest() } }
        mvc.mover(s.enc, "DEVOLUCION", s.producto.id, s.bodega.id, "1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `ajustes - solo admin, con motivo y sentido`() {
        val s = Escenario()
        mvc.mover(s.enc, "AJUSTE", s.producto.id, s.bodega.id, "1", motivo = "x", sentido = "ENTRADA").andExpect {
            status { isForbidden() }
            jsonPath("$.codigo") { value("NO_AUTORIZADO") }
        }
        mvc.mover(s.admin, "AJUSTE", s.producto.id, s.bodega.id, "1", sentido = "ENTRADA").andExpect {
            status { isBadRequest() }
        }
        mvc.mover(s.admin, "AJUSTE", s.producto.id, s.bodega.id, "1", motivo = "Conteo").andExpect {
            status { isBadRequest() }
        }
        mvc.mover(s.admin, "AJUSTE", s.producto.id, s.piso.id, "2", motivo = "Conteo físico", sentido = "ENTRADA").andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.destino.id") { value(s.piso.id.toString()) }
            jsonPath("$.movimiento.motivo") { value("Conteo físico") }
        }
        mvc.mover(s.admin, "AJUSTE", s.producto.id, s.piso.id, "0.5", motivo = "Derrame", sentido = "SALIDA").andExpect {
            status { isCreated() }
            jsonPath("$.movimiento.saldoResultanteOrigen") { value(1.5) }
        }
    }

    @Test
    fun `producto inactivo, ubicación inactiva y fecha futura`() {
        val s = Escenario()
        val inactivo = fabrica.producto(s.est, "Viejo", activo = false)
        mvc.mover(s.admin, "INGRESO", inactivo.id, s.bodega.id, "1").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.codigo") { value("PRODUCTO_INACTIVO") }
        }
        val cerrado = fabrica.piso(s.est, "Cerrado", activo = false)
        mvc.mover(s.enc, "TRASLADO", s.producto.id, cerrado.id, "1").andExpect { status { isBadRequest() } }
        mvc.post("/api/v1/movimientos") {
            header("Authorization", s.admin)
            contentType = MediaType.APPLICATION_JSON
            content = jsonMovimiento("INGRESO", s.producto.id, s.bodega.id, "1", ocurridoEn = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `sugiere reportar nivel cuando el piso queda en 25 por ciento o menos del último traslado`() {
        val s = Escenario()
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "10") }
        s.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "4") }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "2").andExpect { jsonPath("$.sugerirReporteNivel") { value(false) } }
        mvc.mover(s.enc, "CONSUMO", s.producto.id, s.piso.id, "1").andExpect { jsonPath("$.sugerirReporteNivel") { value(true) } }
    }

    @Test
    fun `el kardex es inmutable en la base`() {
        val s = Escenario()
        val id = s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "1") }
        assertThrows<Exception> { jdbc.update("update movimiento set cantidad = 99 where id = ?::uuid", id) }
        assertThrows<Exception> { jdbc.update("delete from movimiento where id = ?::uuid", id) }
    }

    @Test
    fun `lote offline - se ordena por ocurridoEn, resultado por ítem en el orden original`() {
        val s = Escenario()
        val base = OffsetDateTime.now(ZoneOffset.ofHours(-5)).minusHours(5)
        val repetido = UUID.randomUUID()
        val consumo = jsonMovimiento("CONSUMO", s.producto.id, s.piso.id, "1", ocurridoEn = base.plusHours(3))
        val ingreso = jsonMovimiento("INGRESO", s.producto.id, s.bodega.id, "5", ocurridoEn = base.plusHours(1), clientUuid = repetido)
        val traslado = jsonMovimiento("TRASLADO", s.producto.id, s.piso.id, "2", ocurridoEn = base.plusHours(2))
        val excesivo = jsonMovimiento("TRASLADO", s.producto.id, s.piso.id, "50", ocurridoEn = base.plusHours(4))
        val duplicado = jsonMovimiento("INGRESO", s.producto.id, s.bodega.id, "5", ocurridoEn = base.plusHours(5), clientUuid = repetido)
        val invalido = """{"clientUuid":"${UUID.randomUUID()}","tipo":"CONSUMO","productoId":"${s.producto.id}","ubicacionId":"${s.piso.id}","ocurridoEn":"$base"}"""

        mvc.post("/api/v1/sincronizacion/lote") {
            header("Authorization", s.admin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"items":[
                {"tipo":"MOVIMIENTO","movimiento":$consumo},
                {"tipo":"MOVIMIENTO","movimiento":$ingreso},
                {"tipo":"MOVIMIENTO","movimiento":$traslado},
                {"tipo":"MOVIMIENTO","movimiento":$excesivo},
                {"tipo":"MOVIMIENTO","movimiento":$duplicado},
                {"tipo":"MOVIMIENTO","movimiento":$invalido}
            ]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.procesados") { value(6) }
            jsonPath("$.ok") { value(3) }
            jsonPath("$.duplicados") { value(1) }
            jsonPath("$.errores") { value(2) }
            jsonPath("$.items[0].indice") { value(0) }
            jsonPath("$.items[0].resultado") { value("OK") }
            jsonPath("$.items[1].resultado") { value("OK") }
            jsonPath("$.items[2].resultado") { value("OK") }
            jsonPath("$.items[3].resultado") { value("ERROR") }
            jsonPath("$.items[3].codigo") { value("STOCK_INSUFICIENTE") }
            jsonPath("$.items[3].detalle.disponible") { value(3.0) }
            jsonPath("$.items[4].resultado") { value("DUPLICADO") }
            jsonPath("$.items[5].codigo") { value("VALIDACION") }
        }
    }

    @Test
    fun `lote de más de 100 ítems se rechaza`() {
        val s = Escenario()
        val item = """{"tipo":"MOVIMIENTO","movimiento":${jsonMovimiento("INGRESO", s.producto.id, s.bodega.id, "1")}}"""
        mvc.post("/api/v1/sincronizacion/lote") {
            header("Authorization", s.enc)
            contentType = MediaType.APPLICATION_JSON
            content = """{"items":[${List(101) { item }.joinToString(",")}]}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `encargado no asignado no puede mover en ese establecimiento`() {
        val s = Escenario()
        val otro = fabrica.token(fabrica.encargado(s.empresa))
        mvc.mover(otro, "INGRESO", s.producto.id, s.bodega.id, "1").andExpect { status { isNotFound() } }
    }

    @Test
    fun `aislamiento - productos, ubicaciones, movimientos y clientUuid de otra empresa`() {
        val a = Escenario()
        val b = Escenario()
        val idA = a.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "3") }

        // Ubicación ajena y producto ajeno: 404.
        mvc.mover(b.enc, "INGRESO", b.producto.id, a.bodega.id, "1").andExpect { status { isNotFound() } }
        mvc.mover(b.enc, "INGRESO", a.producto.id, b.bodega.id, "1").andExpect { status { isNotFound() } }
        mvc.mover(b.admin, "AJUSTE", a.producto.id, a.bodega.id, "1", motivo = "x", sentido = "SALIDA").andExpect { status { isNotFound() } }
        mvc.get("/api/v1/movimientos/$idA") { header("Authorization", b.admin) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/movimientos/$idA") { header("Authorization", a.enc) }.andExpect { status { isOk() } }

        // Reusar un clientUuid de otra empresa no devuelve sus datos.
        val clientA = jdbc.queryForObject("select client_uuid from movimiento where id = ?::uuid", UUID::class.java, idA)!!
        mvc.mover(b.enc, "INGRESO", b.producto.id, b.bodega.id, "1", clientUuid = clientA).andExpect {
            status { isConflict() }
            jsonPath("$.movimiento") { doesNotExist() }
        }
    }

    private fun contar(token: String, ubicacionId: UUID, vararg items: Pair<UUID, String>) =
        mvc.post("/api/v1/ubicaciones/$ubicacionId/conteo") {
            header("Authorization", token)
            contentType = MediaType.APPLICATION_JSON
            content = items.joinToString(",", "{\"items\":[", "]}") { (p, c) -> "{\"productoId\":\"$p\",\"cantidad\":$c}" }
        }

    @Test
    fun `conteo físico - en el piso lo que sube sale de la bodega y lo que baja es consumo`() {
        val s = Escenario()
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "10") }

        // Piso 0 → 4: TRASLADO de 4 desde la bodega (10 → 6).
        contar(s.enc, s.piso.id, s.producto.id to "4").andExpect {
            status { isOk() }
            jsonPath("$.ajustados") { value(1) }
            jsonPath("$.sinCambio") { value(0) }
            jsonPath("$.movimientos[0].tipo") { value("TRASLADO") }
            jsonPath("$.movimientos[0].motivo") { value("Conteo físico") }
            jsonPath("$.movimientos[0].cantidad") { value(4.0) }
            jsonPath("$.movimientos[0].origen.id") { value(s.bodega.id.toString()) }
            jsonPath("$.movimientos[0].destino.id") { value(s.piso.id.toString()) }
            jsonPath("$.movimientos[0].saldoResultanteOrigen") { value(6.0) }
            jsonPath("$.movimientos[0].saldoResultanteDestino") { value(4.0) }
        }
        // Piso 4 → 1,5: CONSUMO de 2,5; la bodega no cambia. Contar lo mismo otra vez no crea nada.
        contar(s.enc, s.piso.id, s.producto.id to "1.5").andExpect {
            jsonPath("$.movimientos[0].tipo") { value("CONSUMO") }
            jsonPath("$.movimientos[0].origen.id") { value(s.piso.id.toString()) }
            jsonPath("$.movimientos[0].cantidad") { value(2.5) }
            jsonPath("$.movimientos[0].saldoResultanteOrigen") { value(1.5) }
        }
        contar(s.enc, s.piso.id, s.producto.id to "1.5").andExpect {
            jsonPath("$.ajustados") { value(0) }
            jsonPath("$.sinCambio") { value(1) }
        }
        // Subir el piso más de lo que hay en bodega (6): se rechaza todo el conteo y nada cambia.
        contar(s.enc, s.piso.id, s.producto.id to "20").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.codigo") { value("STOCK_INSUFICIENTE") }
        }
        assertEquals(0, saldo(s.producto.id, s.bodega.id).compareTo(BigDecimal("6")))
        assertEquals(0, saldo(s.producto.id, s.piso.id).compareTo(BigDecimal("1.5")))
    }

    @Test
    fun `el stock de la bodega solo lo cambia el admin`() {
        val s = Escenario()
        s.run { mvc.moverOk(admin, "INGRESO", producto.id, bodega.id, "10") }
        mvc.mover(s.enc, "INGRESO", s.producto.id, s.bodega.id, "5").andExpect {
            status { isForbidden() }
            jsonPath("$.codigo") { value("NO_AUTORIZADO") }
        }
        contar(s.enc, s.bodega.id, s.producto.id to "3").andExpect { status { isForbidden() } }
        mvc.mover(s.enc, "AJUSTE", s.producto.id, s.bodega.id, "1", motivo = "Conteo físico", sentido = "ENTRADA").andExpect { status { isForbidden() } }
        // El admin cuenta la bodega: AJUSTE 10 → 7.
        contar(s.admin, s.bodega.id, s.producto.id to "7").andExpect {
            status { isOk() }
            jsonPath("$.movimientos[0].tipo") { value("AJUSTE") }
            jsonPath("$.movimientos[0].saldoResultanteOrigen") { value(7.0) }
        }
        // El encargado sí mueve entre bodega y piso.
        s.run { mvc.moverOk(enc, "TRASLADO", producto.id, piso.id, "2") }
        s.run { mvc.moverOk(enc, "DEVOLUCION", producto.id, piso.id, "1") }
    }

    private fun saldo(productoId: UUID, ubicacionId: UUID): BigDecimal =
        jdbc.queryForObject("select cantidad from stock where producto_id = ? and ubicacion_id = ?", BigDecimal::class.java, productoId, ubicacionId)
            ?: BigDecimal.ZERO

    @Test
    fun `conteo físico - cantidades negativas, producto de otro sitio o encargado no asignado`() {
        val s = Escenario()
        contar(s.enc, s.piso.id, s.producto.id to "-1").andExpect { status { isBadRequest() } }
        val otroSitio = fabrica.producto(fabrica.establecimiento(s.empresa), "Jabón", Unidad.L)
        contar(s.enc, s.piso.id, otroSitio.id to "1").andExpect { status { isNotFound() } }
        val noAsignado = fabrica.token(fabrica.encargado(s.empresa))
        contar(noAsignado, s.piso.id, s.producto.id to "1").andExpect { status { isNotFound() } }
    }
}
