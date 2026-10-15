package ec.cleantok.identidad

import ec.cleantok.identidad.api.EmpresaController
import ec.cleantok.identidad.api.UsuarioController
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@VerificaAislamiento(UsuarioController::class, EmpresaController::class)
class AislamientoUsuariosTest : PruebaIntegracion() {

    @Test
    fun `un admin no ve ni modifica usuarios de otra empresa (404, no 403)`() {
        val a = fabrica.empresa()
        val b = fabrica.empresa()
        val adminA = fabrica.token(fabrica.admin(a))
        val encargadoB = fabrica.encargado(b)

        mvc.get("/api/v1/usuarios/${encargadoB.id}") { header("Authorization", adminA) }.andExpect {
            status { isNotFound() }
            jsonPath("$.codigo") { value("NO_ENCONTRADO") }
        }
        mvc.patch("/api/v1/usuarios/${encargadoB.id}") {
            header("Authorization", adminA)
            contentType = MediaType.APPLICATION_JSON
            content = """{"activo":false}"""
        }.andExpect { status { isNotFound() } }

        mvc.get("/api/v1/usuarios?size=100") { header("Authorization", adminA) }.andExpect {
            status { isOk() }
            jsonPath("$.content[*].empresaId") { value(everyItem(`is`(a.id.toString()))) }
        }
    }

    @Test
    fun `el admin crea encargados solo en su empresa`() {
        val a = fabrica.empresa()
        val adminA = fabrica.token(fabrica.admin(a))
        mvc.post("/api/v1/usuarios") {
            header("Authorization", adminA)
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Ana Pérez","email":"ana-${a.id}@prueba.ec","password":"Segura2026"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.rol") { value("ENCARGADO") }
            jsonPath("$.empresaId") { value(a.id.toString()) }
        }
    }

    @Test
    fun `email repetido responde CONFLICTO`() {
        val a = fabrica.empresa()
        val existente = fabrica.encargado(fabrica.empresa())
        mvc.post("/api/v1/usuarios") {
            header("Authorization", fabrica.token(fabrica.admin(a)))
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Otro","email":"${existente.email.uppercase()}","password":"Segura2026"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.codigo") { value("CONFLICTO") }
        }
    }

    @Test
    fun `el encargado no puede administrar usuarios y el admin no puede crear empresas`() {
        val a = fabrica.empresa()
        mvc.get("/api/v1/usuarios") { header("Authorization", fabrica.token(fabrica.encargado(a))) }.andExpect {
            status { isForbidden() }
            jsonPath("$.codigo") { value("NO_AUTORIZADO") }
        }
        mvc.post("/api/v1/empresas") {
            header("Authorization", fabrica.token(fabrica.admin(a)))
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Intrusa","ruc":"1799999999001"}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `el super admin crea empresas y sus admins, y no opera recursos de dominio`() {
        val sa = fabrica.token(fabrica.superAdmin())
        val ruc = "09" + (System.nanoTime() % 100_000_000_000L).toString().padStart(11, '0')
        val respuesta = mvc.post("/api/v1/empresas") {
            header("Authorization", sa)
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Brillo S.A.","ruc":"$ruc"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val empresaId = com.jayway.jsonpath.JsonPath.read<String>(respuesta, "$.id")

        mvc.post("/api/v1/empresas") {
            header("Authorization", sa)
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Duplicada","ruc":"$ruc"}"""
        }.andExpect { status { isConflict() } }

        mvc.post("/api/v1/empresas/$empresaId/admins") {
            header("Authorization", sa)
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Admin Brillo","email":"admin-$ruc@prueba.ec","password":"Segura2026"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.rol") { value("ADMIN_EMPRESA") }
        }

        mvc.post("/api/v1/empresas/00000000-0000-0000-0000-000000000000/admins") {
            header("Authorization", sa)
            contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"X","email":"x-$ruc@prueba.ec","password":"Segura2026"}"""
        }.andExpect { status { isNotFound() } }

        mvc.get("/api/v1/usuarios") { header("Authorization", sa) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `desactivar un usuario revoca sus sesiones`() {
        val a = fabrica.empresa()
        val adminA = fabrica.token(fabrica.admin(a))
        val encargado = fabrica.encargado(a)
        val refresh = com.jayway.jsonpath.JsonPath.read<String>(
            mvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"${encargado.email}","password":"${ec.cleantok.soporte.Fabrica.CLAVE}"}"""
            }.andReturn().response.contentAsString,
            "$.refreshToken",
        )
        mvc.patch("/api/v1/usuarios/${encargado.id}") {
            header("Authorization", adminA)
            contentType = MediaType.APPLICATION_JSON
            content = """{"activo":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.activo") { value(false) }
        }
        mvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refresh"}"""
        }.andExpect { status { isUnauthorized() } }
    }
}
