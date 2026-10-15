package ec.cleantok.ordenes

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.comun.Textos
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.ordenes.extraccion.ExtractorIa
import ec.cleantok.ordenes.extraccion.ExtractorSercop
import ec.cleantok.ordenes.extraccion.LectorPdf
import ec.cleantok.ordenes.extraccion.Lexico
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import ec.cleantok.ordenes.servicio.AnalizadorOrdenes
import ec.cleantok.ordenes.servicio.ClienteClaude
import ec.cleantok.soporte.Pdfs
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertTrue

/**
 * Integración real con la API de Claude (opcional): solo corre con ANTHROPIC_API_KEY.
 * Fuerza parser + IA sobre la orden A y comprueba que las reglas de A se siguen cumpliendo al combinar.
 */
@Tag("ia")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class OrdenAConIaRealTest {

    @Test
    fun `orden A con parser e IA combinados cumple sus reglas`() {
        val pdf = Pdfs.orden("orden-a-ucuenca-2021.pdf")
        assumeTrue(pdf != null, "Falta la orden A")
        val props = CleanTokProperties(
            urlPublica = "http://localhost",
            jwt = CleanTokProperties.Jwt("x".repeat(32)),
            ia = CleanTokProperties.Ia(
                habilitada = true,
                modelo = System.getenv("CLEANTOK_IA_MODELO") ?: "claude-opus-5-5",
                apiKey = System.getenv("ANTHROPIC_API_KEY"),
                timeoutSegundos = 120,
            ),
        )
        val cliente = ClienteClaude(props, JsonMapper.builder().build())
        val sercop = ExtractorSercop(Lexico.desdeClasspath())
        val analizador = AnalizadorOrdenes(sercop, ExtractorIa(cliente, sercop), cliente)

        val r = analizador.analizar(LectorPdf.leer(pdf!!), pdf, permitirPdf = false, forzarIa = true)
        assertTrue(r.avisos.none { it.startsWith("Extracción con IA no disponible") }, "La IA falló: ${r.avisos}")

        val seleccionados = r.items.filter { it.seleccionado && it.seccion in setOf(SeccionOrigen.MATERIALES, SeccionOrigen.CLAUSULA_ENTIDAD) }
        val nombres = seleccionados.map { Textos.normalizar(it.nombre) }
        listOf("cloro liquido", "amonio cuaternario", "agua destilada", "papel higienico", "gel desinfectante").forEach { esperado ->
            assertTrue(nombres.any { it.startsWith(esperado) }, "Falta $esperado en $nombres")
        }
        assertTrue(seleccionados.all { it.abastece == Abastece.ENTIDAD_CONTRATANTE }, "Todo lo de A lo provee la entidad")
        val areas = listOf("pisos", "banos", "paredes", "techos", "puertas", "ventanas", "persianas", "mesones", "escritorios", "mobiliario", "manillas")
        assertTrue(nombres.none { n -> areas.any { n.startsWith(it) } }, "No debe proponer áreas: $nombres")
    }
}
