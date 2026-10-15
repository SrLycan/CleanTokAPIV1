package ec.cleantok

import ec.cleantok.establecimiento.api.QrPublicoController
import ec.cleantok.identidad.api.AuthController
import ec.cleantok.soporte.VerificaAislamiento
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertTrue

/**
 * Exige que cada controlador tenga una prueba de aislamiento entre empresas
 * (una clase de prueba anotada con @VerificaAislamiento que lo nombre).
 */
class CoberturaAislamientoTest {

    /** Controladores sin recursos de una empresa: no aplican. */
    private val exentos = setOf(AuthController::class.java.name, QrPublicoController::class.java.name)

    private fun clasesCon(anotacion: Class<out Annotation>): List<Class<*>> {
        val escaner = ClassPathScanningCandidateComponentProvider(false)
        escaner.addIncludeFilter(AnnotationTypeFilter(anotacion))
        return escaner.findCandidateComponents("ec.cleantok").map { Class.forName(it.beanClassName) }
    }

    @Test
    fun `todo controlador tiene prueba de aislamiento`() {
        val controladores = clasesCon(RestController::class.java).map { it.name }.toSet() - exentos
        val cubiertos = clasesCon(VerificaAislamiento::class.java)
            .flatMap { c -> c.getAnnotation(VerificaAislamiento::class.java).controladores.map { it.java.name } }
            .toSet()
        val faltantes = controladores - cubiertos
        assertTrue(controladores.isNotEmpty(), "No se encontraron controladores")
        assertTrue(faltantes.isEmpty(), "Controladores sin prueba de aislamiento: $faltantes")
    }
}
