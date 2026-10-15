package ec.cleantok.inventario

import ec.cleantok.comun.error.ApiException
import ec.cleantok.comun.error.CodigoError
import ec.cleantok.comun.seguridad.AutenticacionJwt
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.identidad.dominio.Usuario
import ec.cleantok.inventario.api.MovimientoRequest
import ec.cleantok.inventario.dominio.TipoMovimiento
import ec.cleantok.inventario.servicio.MovimientoService
import ec.cleantok.soporte.PruebaIntegracion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Varios encargados registrando a la vez sobre los mismos saldos. */
class ConcurrenciaMovimientosTest : PruebaIntegracion() {

    @Autowired
    lateinit var servicio: MovimientoService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun comoUsuario(u: Usuario, bloque: () -> Unit) {
        val jwt = Jwt.withTokenValue("prueba").header("alg", "HS256").subject(u.id.toString()).build()
        SecurityContextHolder.getContext().authentication = AutenticacionJwt(UsuarioAutenticado(u.id, u.empresaId, u.rol), jwt)
        try {
            bloque()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun req(tipo: TipoMovimiento, productoId: UUID, ubicacionId: UUID, cantidad: String, clientUuid: UUID = UUID.randomUUID()) =
        MovimientoRequest(clientUuid, tipo, productoId, ubicacionId, BigDecimal(cantidad), null, OffsetDateTime.now(), null)

    /** Lanza todas las tareas a la vez y devuelve los códigos de error (null = aplicado). */
    private fun enParalelo(tareas: List<() -> Unit>): List<CodigoError?> {
        val pool = Executors.newFixedThreadPool(16)
        val salida = CountDownLatch(1)
        val resultados = Collections.synchronizedList(mutableListOf<CodigoError?>())
        val inesperados = Collections.synchronizedList(mutableListOf<Throwable>())
        tareas.forEach { t ->
            pool.submit {
                salida.await()
                try {
                    t(); resultados += null
                } catch (e: ApiException) {
                    resultados += e.codigo
                } catch (e: Throwable) {
                    inesperados += e
                }
            }
        }
        salida.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "Las tareas no terminaron")
        assertTrue(inesperados.isEmpty(), "Errores inesperados: ${inesperados.map { it.toString() }}")
        return resultados
    }

    private fun saldo(productoId: UUID, ubicacionId: UUID): BigDecimal =
        jdbc.queryForObject("select cantidad from stock where producto_id = ? and ubicacion_id = ?", BigDecimal::class.java, productoId, ubicacionId)
            ?: BigDecimal.ZERO

    @Test
    fun `traslados, consumos y devoluciones concurrentes dejan saldos exactos y nunca negativos`() {
        val emp = fabrica.empresa()
        val est = fabrica.establecimiento(emp)
        val bodega = fabrica.bodega(est)
        val pisos = (1..3).map { fabrica.piso(est, "Piso $it") }
        val producto = fabrica.producto(est, "Detergente")
        val encargados = (1..4).map { fabrica.encargado(emp).also { e -> fabrica.asignar(e, est) } }
        comoUsuario(fabrica.admin(emp)) { servicio.registrar(req(TipoMovimiento.INGRESO, producto.id, bodega.id, "100")) }

        // 40 traslados de 3 L desde una bodega de 100 L: solo 33 caben.
        val traslados = (0 until 40).map { i ->
            { comoUsuario(encargados[i % 4]) { servicio.registrar(req(TipoMovimiento.TRASLADO, producto.id, pisos[i % 3].id, "3")) } }
        }
        val r1 = enParalelo(traslados)
        assertEquals(33, r1.count { it == null })
        assertTrue(r1.filterNotNull().all { it == CodigoError.STOCK_INSUFICIENTE })
        assertEquals(0, saldo(producto.id, bodega.id).compareTo(BigDecimal("1")))

        // La cadena de saldos de la bodega es estrictamente 97, 94, … 1: ningún traslado leyó un saldo viejo.
        val cadena = jdbc.queryForList(
            "select saldo_resultante_origen from movimiento where producto_id = ? and tipo = 'TRASLADO' order by secuencia",
            BigDecimal::class.java, producto.id,
        )
        assertEquals((0 until 33).map { BigDecimal(97 - 3 * it) }, cadena.map { it!!.stripTrailingZeros().let(::normal) })

        // Consumos y devoluciones mezclados sobre los pisos.
        val enPisos = pisos.associate { it.id to saldo(producto.id, it.id) }
        val mezcla = (0 until 60).map { i ->
            val piso = pisos[i % 3]
            val tipo = if (i % 5 == 0) TipoMovimiento.DEVOLUCION else TipoMovimiento.CONSUMO
            { comoUsuario(encargados[i % 4]) { servicio.registrar(req(tipo, producto.id, piso.id, "0.5")) } }
        }
        val r2 = enParalelo(mezcla)
        val aplicados = r2.count { it == null }
        assertTrue(r2.filterNotNull().all { it == CodigoError.STOCK_INSUFICIENTE })

        val totalAntes = enPisos.values.fold(BigDecimal.ZERO, BigDecimal::add).add(BigDecimal.ONE)
        val totalDespues = pisos.fold(saldo(producto.id, bodega.id)) { acc, p -> acc.add(saldo(producto.id, p.id)) }
        val consumido = jdbc.queryForObject(
            "select coalesce(sum(cantidad), 0) from movimiento where producto_id = ? and tipo = 'CONSUMO'", BigDecimal::class.java, producto.id,
        )!!
        // Conservación: lo que hay = lo que había − lo consumido.
        assertEquals(0, totalDespues.compareTo(totalAntes.subtract(consumido)))
        assertTrue(aplicados > 0)
        val negativos = jdbc.queryForObject("select count(*) from stock where cantidad < 0", Int::class.java)
        assertEquals(0, negativos)
    }

    @Test
    fun `el mismo clientUuid enviado 10 veces a la vez se aplica una sola vez`() {
        val emp = fabrica.empresa()
        val est = fabrica.establecimiento(emp)
        val bodega = fabrica.bodega(est)
        val producto = fabrica.producto(est, "Jabón")
        val admin = fabrica.admin(emp)
        val uuid = UUID.randomUUID()
        val r = enParalelo(List(10) { { comoUsuario(admin) { servicio.registrar(req(TipoMovimiento.INGRESO, producto.id, bodega.id, "2", uuid)) } } })
        assertTrue(r.all { it == null }, "Todas las peticiones deben responder bien (las repetidas como duplicado)")
        assertEquals(0, saldo(producto.id, bodega.id).compareTo(BigDecimal("2")))
        assertEquals(1, jdbc.queryForObject("select count(*) from movimiento where client_uuid = ?", Int::class.java, uuid))
    }

    private fun normal(b: BigDecimal): BigDecimal = if (b.scale() < 0) b.setScale(0) else b
}
