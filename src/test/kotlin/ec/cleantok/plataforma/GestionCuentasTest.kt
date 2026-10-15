package ec.cleantok.plataforma

import com.jayway.jsonpath.JsonPath
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID

@VerificaAislamiento(PlataformaController::class)
class GestionCuentasTest : PruebaIntegracion() {

    private fun pedir(metodo: String, url: String, token: String?, cuerpo: String? = null): ResultActionsDsl {
        val config: MockHttpServletRequestDsl.() -> Unit = {
            token?.let { header("Authorization", it) }
            if (cuerpo != null) {
                contentType = MediaType.APPLICATION_JSON
                content = cuerpo
            }
        }
        return when (metodo) {
            "GET" -> mvc.get(url, dsl = config)
            "PUT" -> mvc.put(url, dsl = config)
            "PATCH" -> mvc.patch(url, dsl = config)
            "DELETE" -> mvc.delete(url, dsl = config)
            else -> mvc.post(url, dsl = config)
        }
    }

    private fun ResultActionsDsl.json(): String = andReturn().response.contentAsString

    private fun login(email: String, clave: String): String =
        pedir("POST", "/api/v1/auth/login", null, """{"email":"$email","password":"$clave"}""").andExpect { status { isOk() } }.json()

    private fun ruc() = (1_000_000_000_000L + (Math.random() * 8_000_000_000_000L).toLong()).toString()

    @Test
    fun `el administrador del sistema crea un proveedor y su usuario cambia la clave temporal al entrar`() {
        val tSuper = fabrica.token(fabrica.superAdmin())
        val email = "ventas-${UUID.randomUUID().toString().take(6)}@nuevo.ec"
        val creado = pedir(
            "POST", "/api/v1/plataforma/proveedores", tSuper,
            """{"nombre":"Distribuidora Nueva","ruc":"${ruc()}","direccion":"Av. 10 de Agosto, Quito","lat":-0.19,"lng":-78.49,
               "modalidades":["ENVIO_DIRECTO","RETIRO_LOCAL"],"usuarioNombre":"Paola Ríos","usuarioEmail":"$email"}""",
        ).andExpect {
            status { isCreated() }
            jsonPath("$.proveedor.modalidades", hasSize<Any>(2))
            jsonPath("$.usuario.rol") { value("PROVEEDOR") }
            jsonPath("$.usuario.debeCambiarClave") { value(true) }
        }.json()
        val temporal: String = JsonPath.read(creado, "$.usuario.claveTemporal")
        assert(Regex("[A-Z2-9]{5}-[A-Z2-9]{5}").matches(temporal)) { temporal }

        val sesion = login(email, temporal)
        assert(JsonPath.read<Boolean>(sesion, "$.usuario.debeCambiarClave"))
        val token = "Bearer " + JsonPath.read<String>(sesion, "$.accessToken")
        pedir("POST", "/api/v1/auth/cambiar-clave", token, """{"actual":"mala-clave","nueva":"NuevaClave2026"}""").andExpect { status { isBadRequest() } }
        pedir("POST", "/api/v1/auth/cambiar-clave", token, """{"actual":"$temporal","nueva":"NuevaClave2026"}""").andExpect { status { isNoContent() } }
        assert(!JsonPath.read<Boolean>(login(email, "NuevaClave2026"), "$.usuario.debeCambiarClave"))

        pedir("GET", "/api/v1/plataforma/resumen", tSuper).andExpect { status { isOk() } }
        pedir("GET", "/api/v1/plataforma/proveedores", tSuper).andExpect { status { isOk() } }
        // Retiro en bodega sin bodega registrada: inválido.
        pedir(
            "POST", "/api/v1/plataforma/proveedores", tSuper,
            """{"nombre":"Sin bodega","ruc":"${ruc()}","direccion":"X","lat":-0.19,"lng":-78.49,"modalidades":["RETIRO_BODEGA"],
               "usuarioNombre":"X","usuarioEmail":"x-${UUID.randomUUID().toString().take(6)}@nuevo.ec"}""",
        ).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `una empresa o un proveedor desactivados no pueden entrar`() {
        val clave = ec.cleantok.soporte.Fabrica.CLAVE
        val tSuper = fabrica.token(fabrica.superAdmin())
        val emp = fabrica.empresa()
        val admin = fabrica.admin(emp)
        login(admin.email, clave)
        pedir("PATCH", "/api/v1/plataforma/empresas/${emp.id}", tSuper, """{"activo":false}""").andExpect { jsonPath("$.activo") { value(false) } }
        pedir("POST", "/api/v1/auth/login", null, """{"email":"${admin.email}","password":"$clave"}""").andExpect { status { isUnauthorized() } }
        pedir("PATCH", "/api/v1/plataforma/empresas/${emp.id}", tSuper, """{"activo":true}""")
        login(admin.email, clave)

        val prov = fabrica.proveedor()
        val ventas = fabrica.usuarioDe(prov)
        pedir("PATCH", "/api/v1/plataforma/proveedores/${prov.id}", tSuper, """{"activo":false}""")
        pedir("POST", "/api/v1/auth/login", null, """{"email":"${ventas.email}","password":"$clave"}""").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `solo el administrador del sistema entra a la plataforma`() {
        val emp = fabrica.empresa()
        listOf(fabrica.admin(emp), fabrica.encargado(emp), fabrica.usuarioDe(fabrica.proveedor())).forEach { u ->
            pedir("GET", "/api/v1/plataforma/resumen", fabrica.token(u)).andExpect { status { isForbidden() } }
            pedir("GET", "/api/v1/plataforma/empresas", fabrica.token(u)).andExpect { status { isForbidden() } }
        }
        // Desde la plataforma no se restablece a un encargado: lo gestiona su empresa.
        val enc = fabrica.encargado(emp)
        pedir("POST", "/api/v1/plataforma/usuarios/${enc.id}/restablecer-clave", fabrica.token(fabrica.superAdmin())).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `la empresa crea encargados con clave temporal, les asigna sitios y no se queda sin admin`() {
        val emp = fabrica.empresa()
        val admin = fabrica.admin(emp)
        val t = fabrica.token(admin)
        val e1 = fabrica.establecimiento(emp, "A Torre")
        val e2 = fabrica.establecimiento(emp, "B Hospital")
        val nuevo = pedir("POST", "/api/v1/usuarios", t, """{"nombre":"Rosa Caiza","email":"rosa-${emp.id}@prueba.ec"}""").andExpect {
            status { isCreated() }
            jsonPath("$.rol") { value("ENCARGADO") }
            jsonPath("$.claveTemporal") { exists() }
        }.json()
        val id: String = JsonPath.read(nuevo, "$.id")

        pedir("PUT", "/api/v1/establecimientos/asignaciones/$id", t, """["${e1.id}","${e2.id}"]""").andExpect {
            status { isOk() }
            jsonPath("$[*].nombre", contains("A Torre", "B Hospital"))
        }
        pedir("GET", "/api/v1/usuarios/$id", t).andExpect { jsonPath("$.establecimientos", hasSize<Any>(2)) }
        pedir("PUT", "/api/v1/establecimientos/asignaciones/$id", t, """["${e2.id}"]""").andExpect { jsonPath("$", hasSize<Any>(1)) }

        // Un sitio de otra empresa no se puede asignar; otra empresa no ve a este encargado.
        val otra = fabrica.empresa()
        val ajeno = fabrica.establecimiento(otra)
        pedir("PUT", "/api/v1/establecimientos/asignaciones/$id", t, """["${ajeno.id}"]""").andExpect { status { isNotFound() } }
        pedir("PUT", "/api/v1/establecimientos/asignaciones/$id", fabrica.token(fabrica.admin(otra)), """["${ajeno.id}"]""").andExpect { status { isNotFound() } }

        pedir("POST", "/api/v1/usuarios/$id/restablecer-clave", t).andExpect {
            status { isOk() }
            jsonPath("$.debeCambiarClave") { value(true) }
            jsonPath("$.claveTemporal") { exists() }
        }
        pedir("PATCH", "/api/v1/usuarios/${admin.id}", t, """{"activo":false}""").andExpect { status { isConflict() } }
    }

    @Test
    fun `el proveedor gestiona su equipo y su perfil, y la empresa lo elige del directorio`() {
        val prov = fabrica.proveedor("Directorio ${UUID.randomUUID().toString().take(4)}")
        val ventas = fabrica.usuarioDe(prov)
        val t = fabrica.token(ventas)
        val rep = pedir("POST", "/api/v1/proveedor/equipo", t, """{"nombre":"Kléber Ati","email":"kleber-${prov.id}@prov.ec"}""").andExpect {
            status { isCreated() }
            jsonPath("$.rol") { value("REPARTIDOR") }
            jsonPath("$.claveTemporal") { exists() }
        }.json()
        val repId: String = JsonPath.read(rep, "$.id")
        pedir("GET", "/api/v1/proveedor/repartidores", t).andExpect { jsonPath("$[0].id") { value(repId) } }

        // Otro proveedor no toca a este equipo; el último de ventas no se desactiva.
        val tOtro = fabrica.token(fabrica.usuarioDe(fabrica.proveedor()))
        pedir("PATCH", "/api/v1/proveedor/equipo/$repId", tOtro, """{"activo":false}""").andExpect { status { isNotFound() } }
        pedir("PATCH", "/api/v1/proveedor/equipo/${ventas.id}", t, """{"activo":false}""").andExpect { status { isConflict() } }
        pedir("PATCH", "/api/v1/proveedor/equipo/$repId", t, """{"activo":false}""").andExpect { jsonPath("$.activo") { value(false) } }

        pedir("PUT", "/api/v1/proveedor/perfil", t, """{"modalidades":["RETIRO_BODEGA"]}""").andExpect { status { isBadRequest() } }
        pedir(
            "PUT", "/api/v1/proveedor/perfil", t,
            """{"telefono":"099 111 2222","bodegaDireccion":"Galpón 3","bodegaLat":-0.3,"bodegaLng":-78.54,"modalidades":["ENVIO_DIRECTO","RETIRO_BODEGA"]}""",
        ).andExpect {
            status { isOk() }
            jsonPath("$.modalidades", hasSize<Any>(2))
            jsonPath("$.bodegaDireccion") { value("Galpón 3") }
        }

        val emp = fabrica.empresa()
        val tAdmin = fabrica.token(fabrica.admin(emp))
        pedir("GET", "/api/v1/proveedores/directorio", tAdmin).andExpect {
            jsonPath("$[?(@.proveedor.id == '${prov.id}')].vinculado", contains(false))
        }
        pedir("PUT", "/api/v1/proveedores/${prov.id}/vinculo", tAdmin, """{"preferido":true}""").andExpect { jsonPath("$.vinculado") { value(true) } }
        pedir("GET", "/api/v1/proveedores", tAdmin).andExpect { jsonPath("$[0].preferido") { value(true) } }
        pedir("DELETE", "/api/v1/proveedores/${prov.id}/vinculo", tAdmin).andExpect { status { isNoContent() } }
        pedir("GET", "/api/v1/proveedores", tAdmin).andExpect { jsonPath("$", hasSize<Any>(0)) }
        // Un repartidor no gestiona el equipo.
        pedir("GET", "/api/v1/proveedor/equipo", fabrica.token(fabrica.usuarioDe(prov, Rol.REPARTIDOR))).andExpect { status { isForbidden() } }
    }
}
