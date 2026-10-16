package ec.cleantok.plataforma

import com.jayway.jsonpath.JsonPath
import ec.cleantok.identidad.api.MiEmpresaController
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.notificacion.NotificacionController
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals

/** Supervisor de establecimiento, contacto de la empresa para el proveedor y notificación de cotización recibida. */
@VerificaAislamiento(MiEmpresaController::class, NotificacionController::class)
class SupervisorYNotificacionesTest : PruebaIntegracion() {

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

    @Test
    fun `el administrador de la plataforma crea la sede con su supervisor, que solo ve esa sede`() {
        val emp = fabrica.empresa()
        val otraSede = fabrica.establecimiento(emp, "Otra sede")
        val tSuper = fabrica.token(fabrica.superAdmin())

        val creada = mvc.enviar(
            "POST", "/api/v1/plataforma/empresas/${emp.id}/establecimientos", tSuper,
            """{"nombre":"Sede Norte","tipo":"OFICINA","nuevoSupervisor":{"nombre":"Sara Supervisora","email":"sara.${emp.id}@ejemplo.ec"}}""",
        ).andExpect {
            status { isCreated() }
            jsonPath("$.supervisor.nombre") { value("Sara Supervisora") }
            jsonPath("$.credencialSupervisor.rol") { value("SUPERVISOR") }
            jsonPath("$.credencialSupervisor.claveTemporal") { isString() }
        }.json()
        val sedeId: String = JsonPath.read(creada, "$.id")
        val supervisorId: String = JsonPath.read(creada, "$.supervisor.id")

        // Inicia sesión con su clave temporal: su rol y solo su sede.
        val clave: String = JsonPath.read(creada, "$.credencialSupervisor.claveTemporal")
        val login = mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"sara.${emp.id}@ejemplo.ec","password":"$clave"}"""
        }.andExpect { status { isOk() } }.json()
        val tSup = "Bearer " + JsonPath.read<String>(login, "$.accessToken")
        mvc.enviar("GET", "/api/v1/establecimientos/mios", tSup).andExpect {
            status { isOk() }
            jsonPath("$[*].nombre", contains("Sede Norte"))
        }
        mvc.enviar("GET", "/api/v1/establecimientos/$sedeId/inventario", tSup).andExpect { status { isOk() } }
        // La otra sede de la misma empresa no existe para él (ni para leer ni para administrar).
        mvc.enviar("GET", "/api/v1/establecimientos/${otraSede.id}", tSup).andExpect { status { isNotFound() } }
        mvc.enviar("GET", "/api/v1/establecimientos/${otraSede.id}/ubicaciones", tSup).andExpect { status { isNotFound() } }
        mvc.enviar("POST", "/api/v1/establecimientos/${otraSede.id}/ubicaciones", tSup, """{"nombre":"Piso 9","tipo":"PISO"}""")
            .andExpect { status { isNotFound() } }
        // En la suya administra los pisos y ve la URL de cada QR, como el admin.
        mvc.enviar("POST", "/api/v1/establecimientos/$sedeId/ubicaciones", tSup, """{"nombre":"Piso 1","tipo":"PISO"}""")
            .andExpect { status { isCreated() } }
        mvc.enviar("GET", "/api/v1/establecimientos/$sedeId/ubicaciones", tSup).andExpect {
            status { isOk() }
            jsonPath("$[0].urlQr") { isString() }
        }
        // No gestiona compras ni personal.
        mvc.enviar("GET", "/api/v1/solicitudes", tSup).andExpect { status { isForbidden() } }
        mvc.enviar("GET", "/api/v1/usuarios", tSup).andExpect { status { isForbidden() } }

        // Cambiar de supervisor: el anterior deja de ver la sede.
        val otro = fabrica.usuario(emp, Rol.SUPERVISOR)
        mvc.enviar("PUT", "/api/v1/establecimientos/$sedeId/supervisor", tSuper, """{"supervisorId":"${otro.id}"}""").andExpect {
            status { isOk() }
            jsonPath("$.supervisor.id") { value(otro.id.toString()) }
        }
        mvc.enviar("GET", "/api/v1/establecimientos/mios", tSup).andExpect { status { isOk() }; jsonPath("$", hasSize<Any>(0)) }
        val supervisores = mvc.enviar("GET", "/api/v1/plataforma/empresas/${emp.id}/supervisores", tSuper).andExpect { status { isOk() } }.json()
        assertEquals(listOf(0), JsonPath.read<List<List<Any>>>(supervisores, "$[?(@.id == '$supervisorId')].establecimientos").map { it.size })

        // Un supervisor de otra empresa no se puede asignar.
        val ajeno = fabrica.usuario(fabrica.empresa(), Rol.SUPERVISOR)
        mvc.enviar("PUT", "/api/v1/establecimientos/$sedeId/supervisor", tSuper, """{"supervisorId":"${ajeno.id}"}""")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `el proveedor ve el contacto de la empresa y el admin recibe la notificacion de la cotizacion`() {
        val emp = fabrica.empresa()
        val admin = fabrica.admin(emp)
        val tAdmin = fabrica.token(admin)
        val est = fabrica.establecimiento(emp)
        val prod = fabrica.producto(est, "Desinfectante")
        val prov = fabrica.proveedor("Proveedor ${emp.id.toString().take(6)}")
        fabrica.vincular(emp, prov)
        val tProv = fabrica.token(fabrica.usuarioDe(prov))

        mvc.enviar(
            "PUT", "/api/v1/empresa", tAdmin,
            """{"telefono":"02 222 3333","email":"compras@ejemplo.ec","direccion":"Av. Amazonas 100","lat":-0.18,"lng":-78.48}""",
        ).andExpect { status { isOk() }; jsonPath("$.telefono") { value("02 222 3333") } }

        val s = mvc.enviar(
            "POST", "/api/v1/solicitudes", tAdmin,
            """{"enviar":true,"fechaRequerida":"2099-01-01","lineas":[{"productoId":"${prod.id}","cantidad":4}]}""",
        ).andExpect { status { isCreated() } }.json()
        val id: String = JsonPath.read(s, "$.id")
        val item: String = JsonPath.read(s, "$.items[0].id")

        mvc.enviar("GET", "/api/v1/proveedor/solicitudes/$id", tProv).andExpect {
            status { isOk() }
            jsonPath("$.empresaContacto.razonSocial") { value(emp.nombre) }
            jsonPath("$.empresaContacto.ruc") { value(emp.ruc) }
            jsonPath("$.empresaContacto.email") { value("compras@ejemplo.ec") }
            jsonPath("$.empresaContacto.direccion") { value("Av. Amazonas 100") }
        }

        // Sin modalidad ni plazo: el plazo sale de la fecha que fijó la empresa (máximo 60 días).
        mvc.enviar(
            "PUT", "/api/v1/proveedor/solicitudes/$id/oferta", tProv,
            """{"plazoDias":1,"items":[{"itemId":"$item","cantidadDisponible":4,"precioUnitario":2.5}]}""",
        ).andExpect {
            status { isOk() }
            jsonPath("$.miOferta.plazoDias") { value(60) }
            jsonPath("$.miOferta.modalidad") { value("ENVIO_DIRECTO") }
        }

        val notis = mvc.enviar("GET", "/api/v1/notificaciones", tAdmin).andExpect {
            status { isOk() }
            jsonPath("$.noLeidas") { value(1) }
            jsonPath("$.recientes[0].tipo") { value("OFERTA_RECIBIDA") }
            jsonPath("$.recientes[0].referenciaId") { value(id) }
        }.json()
        val notiId: String = JsonPath.read(notis, "$.recientes[0].id")

        // Aislamiento: otro usuario no ve ni marca las notificaciones ajenas; tampoco lee el contacto de otra empresa.
        val tOtro = fabrica.token(fabrica.admin(fabrica.empresa()))
        mvc.enviar("GET", "/api/v1/notificaciones", tOtro).andExpect { status { isOk() }; jsonPath("$.noLeidas") { value(0) } }
        mvc.enviar("POST", "/api/v1/notificaciones/$notiId/leer", tOtro).andExpect { status { isNotFound() } }
        val otraEmpresa = mvc.enviar("GET", "/api/v1/empresa", tOtro).andExpect { status { isOk() } }.json()
        assertEquals(null, JsonPath.read<String?>(otraEmpresa, "$.telefono"))

        mvc.enviar("POST", "/api/v1/notificaciones/$notiId/leer", tAdmin).andExpect { status { isOk() }; jsonPath("$.leida") { value(true) } }
        mvc.enviar("GET", "/api/v1/notificaciones", tAdmin).andExpect { status { isOk() }; jsonPath("$.noLeidas") { value(0) } }
    }
}
