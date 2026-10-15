package ec.cleantok.establecimiento

import com.jayway.jsonpath.JsonPath
import ec.cleantok.establecimiento.api.EstablecimientoController
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@VerificaAislamiento(EstablecimientoController::class)
class EstablecimientoControllerTest : PruebaIntegracion() {

    @Autowired
    lateinit var ubicaciones: UbicacionRepository

    private val cuerpo = """
        {"nombre":"Hospital del Sur","tipo":"HOSPITAL","entidadContratante":"Ministerio de Salud",
         "direccion":"Av. Quito 123","lat":-2.170998,"lng":-79.922359,"areaM2":6956}
    """.trimIndent()

    @Test
    fun `crear establecimiento crea automáticamente una única bodega con QR`() {
        val admin = fabrica.token(fabrica.admin(fabrica.empresa()))
        val r = mvc.post("/api/v1/establecimientos") {
            header("Authorization", admin)
            contentType = MediaType.APPLICATION_JSON
            content = cuerpo
        }.andExpect {
            status { isCreated() }
            jsonPath("$.tipo") { value("HOSPITAL") }
            jsonPath("$.areaM2") { value(6956) }
            jsonPath("$.bodegaId") { exists() }
        }.andReturn().response.contentAsString
        val id = UUID.fromString(JsonPath.read(r, "$.id"))

        val lista = ubicaciones.findAllByEstablecimientoIdOrderByOrdenAscNombreAsc(id)
        assertEquals(1, lista.size)
        assertEquals(TipoUbicacion.BODEGA, lista[0].tipo)
        assertTrue(lista[0].qrToken.length >= 22)
    }

    @Test
    fun `validación de datos del establecimiento`() {
        val admin = fabrica.token(fabrica.admin(fabrica.empresa()))
        mvc.post("/api/v1/establecimientos") {
            header("Authorization", admin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"","tipo":"CASINO","lat":200}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("VALIDACION") }
        }
    }

    @Test
    fun `el encargado solo ve los establecimientos activos que tiene asignados`() {
        val emp = fabrica.empresa()
        val asignado = fabrica.establecimiento(emp)
        val noAsignado = fabrica.establecimiento(emp)
        val inactivo = fabrica.establecimiento(emp, activo = false)
        val encargado = fabrica.encargado(emp)
        fabrica.asignar(encargado, asignado)
        fabrica.asignar(encargado, inactivo)
        val token = fabrica.token(encargado)

        mvc.get("/api/v1/establecimientos/mios") { header("Authorization", token) }.andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(1))
            jsonPath("$[0].id") { value(asignado.id.toString()) }
        }
        mvc.get("/api/v1/establecimientos/${asignado.id}") { header("Authorization", token) }.andExpect { status { isOk() } }
        mvc.get("/api/v1/establecimientos/${noAsignado.id}") { header("Authorization", token) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/establecimientos/${inactivo.id}") { header("Authorization", token) }.andExpect { status { isNotFound() } }

        // /auth/me también lista sus establecimientos.
        mvc.get("/api/v1/auth/me") { header("Authorization", token) }.andExpect {
            jsonPath("$.establecimientos[*].id", containsInAnyOrder(asignado.id.toString()))
        }
    }

    @Test
    fun `el encargado no puede crear, editar ni asignar`() {
        val emp = fabrica.empresa()
        val e = fabrica.establecimiento(emp)
        val encargado = fabrica.encargado(emp)
        fabrica.asignar(encargado, e)
        val token = fabrica.token(encargado)
        mvc.post("/api/v1/establecimientos") {
            header("Authorization", token); contentType = MediaType.APPLICATION_JSON; content = cuerpo
        }.andExpect { status { isForbidden() } }
        mvc.put("/api/v1/establecimientos/${e.id}") {
            header("Authorization", token); contentType = MediaType.APPLICATION_JSON; content = cuerpo
        }.andExpect { status { isForbidden() } }
        mvc.get("/api/v1/establecimientos/${e.id}/encargados") { header("Authorization", token) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `aislamiento - un admin no ve ni modifica establecimientos de otra empresa (404)`() {
        val ajeno = fabrica.establecimiento(fabrica.empresa())
        val admin = fabrica.token(fabrica.admin(fabrica.empresa()))
        val rutas = listOf(
            mvc.get("/api/v1/establecimientos/${ajeno.id}") { header("Authorization", admin) },
            mvc.put("/api/v1/establecimientos/${ajeno.id}") {
                header("Authorization", admin); contentType = MediaType.APPLICATION_JSON; content = cuerpo
            },
            mvc.patch("/api/v1/establecimientos/${ajeno.id}/desactivar") { header("Authorization", admin) },
            mvc.get("/api/v1/establecimientos/${ajeno.id}/encargados") { header("Authorization", admin) },
            mvc.put("/api/v1/establecimientos/${ajeno.id}/encargados") {
                header("Authorization", admin); contentType = MediaType.APPLICATION_JSON; content = """{"usuarioIds":[]}"""
            },
            mvc.delete("/api/v1/establecimientos/${ajeno.id}/encargados/${UUID.randomUUID()}") { header("Authorization", admin) },
            mvc.delete("/api/v1/establecimientos/${ajeno.id}") { header("Authorization", admin) },
        )
        rutas.forEach { it.andExpect { status { isNotFound() }; jsonPath("$.codigo") { value("NO_ENCONTRADO") } } }

        mvc.get("/api/v1/establecimientos?size=200") { header("Authorization", admin) }.andExpect {
            jsonPath("$.content[?(@.id == '${ajeno.id}')]") { doesNotExist() }
        }
    }

    @Test
    fun `asignar encargados reemplaza la lista y rechaza usuarios ajenos o que no son encargados`() {
        val emp = fabrica.empresa()
        val adminUsuario = fabrica.admin(emp)
        val admin = fabrica.token(adminUsuario)
        val e = fabrica.establecimiento(emp)
        val enc1 = fabrica.encargado(emp)
        val enc2 = fabrica.encargado(emp)
        val ajeno = fabrica.encargado(fabrica.empresa())

        fun asignar(vararg ids: UUID) = mvc.put("/api/v1/establecimientos/${e.id}/encargados") {
            header("Authorization", admin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"usuarioIds":[${ids.joinToString(",") { "\"$it\"" }}]}"""
        }

        asignar(enc1.id, enc2.id).andExpect { status { isOk() }; jsonPath("$", hasSize<Any>(2)) }
        asignar(enc2.id).andExpect { status { isOk() }; jsonPath("$[*].id", containsInAnyOrder(enc2.id.toString())) }
        asignar(enc2.id, ajeno.id).andExpect {
            status { isBadRequest() }
            jsonPath("$.usuarioIds[0]") { value(ajeno.id.toString()) }
        }
        asignar(adminUsuario.id).andExpect { status { isBadRequest() } }

        mvc.delete("/api/v1/establecimientos/${e.id}/encargados/${enc2.id}") { header("Authorization", admin) }
            .andExpect { status { isNoContent() } }
        mvc.get("/api/v1/establecimientos/${e.id}/encargados") { header("Authorization", admin) }
            .andExpect { jsonPath("$", hasSize<Any>(0)) }
    }

    @Test
    fun `desactivar y reactivar`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val e = fabrica.establecimiento(emp)
        mvc.patch("/api/v1/establecimientos/${e.id}/desactivar") { header("Authorization", admin) }
            .andExpect { jsonPath("$.activo") { value(false) } }
        mvc.get("/api/v1/establecimientos?activo=true&size=200") { header("Authorization", admin) }
            .andExpect { jsonPath("$.content[?(@.id == '${e.id}')]") { doesNotExist() } }
        mvc.patch("/api/v1/establecimientos/${e.id}/activar") { header("Authorization", admin) }
            .andExpect { jsonPath("$.activo") { value(true) } }
    }

    @Test
    fun `eliminar - solo el admin que lo creó o el administrador de la plataforma`() {
        val emp = fabrica.empresa()
        val creador = fabrica.admin(emp)
        val otroAdmin = fabrica.token(fabrica.admin(emp))
        val encargado = fabrica.encargado(emp)
        val e = fabrica.establecimiento(emp, creadoPor = creador)
        val piso = fabrica.piso(e)
        fabrica.asignar(encargado, e)
        val tCreador = fabrica.token(creador)

        // La lista le dice a cada uno si puede eliminarlo.
        mvc.get("/api/v1/establecimientos/${e.id}") { header("Authorization", tCreador) }.andExpect { jsonPath("$.puedeEliminar") { value(true) } }
        mvc.get("/api/v1/establecimientos/${e.id}") { header("Authorization", otroAdmin) }.andExpect { jsonPath("$.puedeEliminar") { value(false) } }

        mvc.delete("/api/v1/establecimientos/${e.id}") { header("Authorization", otroAdmin) }.andExpect {
            status { isForbidden() }
            jsonPath("$.codigo") { value("NO_AUTORIZADO") }
        }
        mvc.delete("/api/v1/establecimientos/${e.id}") { header("Authorization", fabrica.token(encargado)) }.andExpect { status { isForbidden() } }

        mvc.delete("/api/v1/establecimientos/${e.id}") { header("Authorization", tCreador) }.andExpect { status { isNoContent() } }
        // Desaparece de todo: lista, detalle, reactivar, sus QR y el encargado ya no lo tiene.
        mvc.get("/api/v1/establecimientos?size=200") { header("Authorization", tCreador) }
            .andExpect { jsonPath("$.content[?(@.id == '${e.id}')]") { doesNotExist() } }
        mvc.get("/api/v1/establecimientos/${e.id}") { header("Authorization", tCreador) }.andExpect { status { isNotFound() } }
        mvc.patch("/api/v1/establecimientos/${e.id}/activar") { header("Authorization", tCreador) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/qr/${piso.qrToken}") { header("Authorization", tCreador) }.andExpect { status { isNotFound() } }
        mvc.get("/api/v1/establecimientos/mios") { header("Authorization", fabrica.token(encargado)) }.andExpect { jsonPath("$", hasSize<Any>(0)) }
        mvc.delete("/api/v1/establecimientos/${e.id}") { header("Authorization", tCreador) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `eliminar - el administrador de la plataforma ve y elimina los de cualquier empresa`() {
        val emp = fabrica.empresa()
        val e = fabrica.establecimiento(emp, nombre = "Sin creador conocido")
        val tSuper = fabrica.token(fabrica.superAdmin())
        mvc.get("/api/v1/plataforma/empresas/${emp.id}/establecimientos") { header("Authorization", tSuper) }.andExpect {
            jsonPath("$[0].id") { value(e.id.toString()) }
            jsonPath("$[0].puedeEliminar") { value(true) }
        }
        // Un admin no ve esa lista de la plataforma.
        mvc.get("/api/v1/plataforma/empresas/${emp.id}/establecimientos") { header("Authorization", fabrica.token(fabrica.admin(emp))) }
            .andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/establecimientos/${e.id}") { header("Authorization", tSuper) }.andExpect { status { isNoContent() } }
        mvc.get("/api/v1/plataforma/empresas/${emp.id}/establecimientos") { header("Authorization", tSuper) }.andExpect { jsonPath("$", hasSize<Any>(0)) }
    }
}
