package ec.cleantok.identidad

import ec.cleantok.soporte.Fabrica
import ec.cleantok.soporte.PruebaIntegracion
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import com.jayway.jsonpath.JsonPath

class AuthControllerTest : PruebaIntegracion() {

    private fun login(email: String, clave: String = Fabrica.CLAVE): ResultActionsDsl =
        mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$clave"}"""
        }

    private fun refresh(token: String): ResultActionsDsl =
        mvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$token"}"""
        }

    private fun campo(r: ResultActionsDsl, ruta: String): String =
        JsonPath.read(r.andReturn().response.contentAsString, ruta)

    @Test
    fun `login correcto devuelve tokens y me devuelve usuario y empresa`() {
        val empresa = fabrica.empresa()
        val admin = fabrica.admin(empresa)

        val r = login(admin.email.uppercase()).andExpect {
            status { isOk() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiraEn") { value(900) }
            jsonPath("$.usuario.rol") { value("ADMIN_EMPRESA") }
        }
        val acceso = campo(r, "$.accessToken")

        mvc.get("/api/v1/auth/me") { header("Authorization", "Bearer $acceso") }.andExpect {
            status { isOk() }
            jsonPath("$.usuario.id") { value(admin.id.toString()) }
            jsonPath("$.empresa.id") { value(empresa.id.toString()) }
            jsonPath("$.establecimientos") { isArray() }
        }
    }

    @Test
    fun `contraseña incorrecta responde 401 NO_AUTORIZADO en formato ProblemDetail`() {
        val admin = fabrica.admin(fabrica.empresa())
        login(admin.email, "otra-clave").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.codigo") { value("NO_AUTORIZADO") }
            jsonPath("$.status") { value(401) }
            jsonPath("$.detail") { value("Email o contraseña incorrectos") }
        }
    }

    @Test
    fun `email inexistente y usuario inactivo no pueden iniciar sesión`() {
        login("nadie@prueba.ec").andExpect { status { isUnauthorized() } }
        val inactivo = fabrica.usuario(fabrica.empresa(), ec.cleantok.identidad.dominio.Rol.ENCARGADO, activo = false)
        login(inactivo.email).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `datos inválidos en login responden VALIDACION con detalle por campo`() {
        mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"no-es-email","password":""}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("VALIDACION") }
            jsonPath("$.errores[?(@.campo == 'email')]") { exists() }
            jsonPath("$.errores[?(@.campo == 'password')]") { exists() }
        }
    }

    @Test
    fun `JSON mal formado responde VALIDACION`() {
        mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email": """
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("VALIDACION") }
        }
    }

    @Test
    fun `refresh rota el token y reusar el anterior revoca toda la sesión`() {
        val admin = fabrica.admin(fabrica.empresa())
        val primero = campo(login(admin.email), "$.refreshToken")

        val segundo = campo(refresh(primero).andExpect { status { isOk() } }, "$.refreshToken")

        // Reuso del token ya rotado: se rechaza y se revoca la familia completa.
        refresh(primero).andExpect {
            status { isUnauthorized() }
            jsonPath("$.codigo") { value("NO_AUTORIZADO") }
        }
        refresh(segundo).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `logout revoca el refresh token`() {
        val admin = fabrica.admin(fabrica.empresa())
        val token = campo(login(admin.email), "$.refreshToken")
        mvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$token"}"""
        }.andExpect { status { isNoContent() } }
        refresh(token).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `sin token o con token adulterado responde 401`() {
        mvc.get("/api/v1/auth/me").andExpect {
            status { isUnauthorized() }
            jsonPath("$.codigo") { value("NO_AUTORIZADO") }
        }
        val admin = fabrica.admin(fabrica.empresa())
        val adulterado = fabrica.token(admin).dropLast(3) + "abc"
        mvc.get("/api/v1/auth/me") { header("Authorization", adulterado) }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.codigo") { value("NO_AUTORIZADO") }
        }
    }

    @Test
    fun `swagger y health son públicos`() {
        mvc.get("/v3/api-docs").andExpect { status { isOk() } }
        mvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
