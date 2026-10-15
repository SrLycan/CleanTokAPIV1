package ec.cleantok.catalogo

import ec.cleantok.catalogo.api.ProductoController
import ec.cleantok.soporte.PruebaIntegracion
import ec.cleantok.soporte.VerificaAislamiento
import ec.cleantok.soporte.moverOk
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@VerificaAislamiento(ProductoController::class)
class ProductoControllerTest : PruebaIntegracion() {

    private val cloro = """{"nombre":"Cloro líquido 5.5%","categoria":"QUIMICO","unidad":"L","presentacion":"Galón","abastece":"ENTIDAD_CONTRATANTE","stockMinimo":5}"""

    @Test
    fun `el admin crea productos con stock 0 y el nombre es único sin distinguir mayúsculas ni tildes`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val e = fabrica.establecimiento(emp)
        mvc.post("/api/v1/establecimientos/${e.id}/productos") {
            header("Authorization", admin); contentType = MediaType.APPLICATION_JSON; content = cloro
        }.andExpect {
            status { isCreated() }
            jsonPath("$.origen") { value("MANUAL") }
            jsonPath("$.esConsumible") { value(true) }
            jsonPath("$.abastece") { value("ENTIDAD_CONTRATANTE") }
            jsonPath("$.unidadesCompatibles", hasItems("L", "ML", "GALON"))
        }
        mvc.post("/api/v1/establecimientos/${e.id}/productos") {
            header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"  CLORO   liquido 5.5% ","categoria":"QUIMICO","unidad":"L"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.codigo") { value("CONFLICTO") }
        }
        mvc.get("/api/v1/establecimientos/${e.id}/inventario") { header("Authorization", admin) }.andExpect {
            jsonPath("$.items[0].stockTotal") { value(0.0) }
            jsonPath("$.items[0].estado") { value("SIN_DATOS") }
        }
    }

    @Test
    fun `listado con filtros y el encargado solo ve activos y no edita`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val e = fabrica.establecimiento(emp)
        fabrica.producto(e, "Papel higiénico jumbo", unidad = ec.cleantok.catalogo.dominio.Unidad.ROLLO, categoria = ec.cleantok.catalogo.dominio.Categoria.PAPEL_Y_HIGIENE)
        fabrica.producto(e, "Desinfectante")
        val inactivo = fabrica.producto(e, "Cera vieja", activo = false)
        val enc = fabrica.encargado(emp).also { fabrica.asignar(it, e) }
        val tEnc = fabrica.token(enc)

        mvc.get("/api/v1/establecimientos/${e.id}/productos") { header("Authorization", admin) }
            .andExpect { jsonPath("$.content", hasSize<Any>(3)); jsonPath("$.content[0].nombre") { value("Cera vieja") } }
        mvc.get("/api/v1/establecimientos/${e.id}/productos?q=PAPEL") { header("Authorization", admin) }
            .andExpect { jsonPath("$.content", hasSize<Any>(1)) }
        mvc.get("/api/v1/establecimientos/${e.id}/productos?categoria=QUIMICO&activo=true") { header("Authorization", admin) }
            .andExpect { jsonPath("$.content", hasSize<Any>(1)) }
        mvc.get("/api/v1/establecimientos/${e.id}/productos?activo=false") { header("Authorization", tEnc) }
            .andExpect { jsonPath("$.content", hasSize<Any>(2)); jsonPath("$.content[?(@.activo == false)]") { isEmpty() } }
        mvc.get("/api/v1/productos/${inactivo.id}") { header("Authorization", tEnc) }.andExpect { status { isNotFound() } }
        mvc.post("/api/v1/establecimientos/${e.id}/productos") {
            header("Authorization", tEnc); contentType = MediaType.APPLICATION_JSON; content = cloro
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `editar, desactivar y borrar respetan los movimientos`() {
        val emp = fabrica.empresa()
        val admin = fabrica.token(fabrica.admin(emp))
        val e = fabrica.establecimiento(emp)
        val usado = fabrica.producto(e, "Alcohol")
        val nuevo = fabrica.producto(e, "Lija de agua", unidad = ec.cleantok.catalogo.dominio.Unidad.UNIDAD)
        mvc.moverOk(admin, "INGRESO", usado.id, fabrica.bodega(e).id, "1")

        mvc.put("/api/v1/productos/${usado.id}") {
            header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Alcohol antiséptico","categoria":"QUIMICO","unidad":"KG"}"""
        }.andExpect { status { isBadRequest() } }
        mvc.put("/api/v1/productos/${usado.id}") {
            header("Authorization", admin); contentType = MediaType.APPLICATION_JSON
            content = """{"nombre":"Alcohol antiséptico","categoria":"QUIMICO","unidad":"L","stockMinimo":2}"""
        }.andExpect { status { isOk() }; jsonPath("$.nombre") { value("Alcohol antiséptico") } }

        mvc.delete("/api/v1/productos/${usado.id}") { header("Authorization", admin) }.andExpect { status { isConflict() } }
        mvc.patch("/api/v1/productos/${usado.id}/desactivar") { header("Authorization", admin) }
            .andExpect { jsonPath("$.activo") { value(false) } }
        mvc.delete("/api/v1/productos/${nuevo.id}") { header("Authorization", admin) }.andExpect { status { isNoContent() } }
        mvc.get("/api/v1/productos/${nuevo.id}") { header("Authorization", admin) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `aislamiento - productos de otra empresa responden 404`() {
        val ajeno = fabrica.establecimiento(fabrica.empresa())
        val pAjeno = fabrica.producto(ajeno)
        val admin = fabrica.token(fabrica.admin(fabrica.empresa()))
        listOf(
            mvc.get("/api/v1/establecimientos/${ajeno.id}/productos") { header("Authorization", admin) },
            mvc.post("/api/v1/establecimientos/${ajeno.id}/productos") {
                header("Authorization", admin); contentType = MediaType.APPLICATION_JSON; content = cloro
            },
            mvc.get("/api/v1/productos/${pAjeno.id}") { header("Authorization", admin) },
            mvc.put("/api/v1/productos/${pAjeno.id}") {
                header("Authorization", admin); contentType = MediaType.APPLICATION_JSON; content = cloro
            },
            mvc.patch("/api/v1/productos/${pAjeno.id}/desactivar") { header("Authorization", admin) },
            mvc.patch("/api/v1/productos/${pAjeno.id}/activar") { header("Authorization", admin) },
            mvc.delete("/api/v1/productos/${pAjeno.id}") { header("Authorization", admin) },
        ).forEach { it.andExpect { status { isNotFound() } } }
    }
}
